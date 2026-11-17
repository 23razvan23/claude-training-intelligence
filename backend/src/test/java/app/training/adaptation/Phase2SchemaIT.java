package app.training.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * R-13 (ADR 0018): the Phase 2 tables, before anything writes to them. Synthetic rows only.
 */
class Phase2SchemaIT extends IntegrationTest {

    private long session(long athlete, String date) {
        return jdbc.sql("""
                INSERT INTO planned_session (athlete_id, session_date, session_type, demand, title)
                VALUES (?, ?::date, 'threshold', 'KEY', 'Tempo') RETURNING id""")
                .params(athlete, date).query(Long.class).single();
    }

    private void pending(long athlete, long session) {
        jdbc.sql("""
                INSERT INTO adaptation_suggestion (athlete_id, planned_session_id, for_date, action, rules_version)
                VALUES (?, ?, '2026-12-01', 'REDUCE', 'r1')""")
                .params(athlete, session).update();
    }

    private long suggestion(long athlete, long session, Long counterpart, String action, Long settings, String inputs) {
        return jdbc.sql("""
                INSERT INTO adaptation_suggestion (athlete_id, planned_session_id, counterpart_session_id, for_date, action,
                    rules_version, settings_version, signals_version, engine_run_date, inputs_snapshot)
                VALUES (?, ?, ?, '2026-12-01', ?, 'r1', ?, 's1', '2026-12-01', ?::jsonb) RETURNING id""")
                .params(athlete, session, counterpart, action, settings, inputs).query(Long.class).single();
    }

    private long settings(String json) {
        return jdbc.sql("INSERT INTO settings_version (settings) VALUES (?::jsonb) RETURNING id").param(json).query(Long.class).single();
    }

    @Test
    void theSamePendingSuggestionCannotBeStoredTwice() {
        long a = newAthlete("Dedup");
        long s = session(a, "2026-12-01");
        pending(a, s);

        assertThatThrownBy(() -> pending(a, s))
                .as("a repeated morning run must not add the same pending suggestion again")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM adaptation_suggestion WHERE athlete_id = ?", a)).isEqualTo(1);
    }

    @Test
    void dedupKeyIgnoresInputsButNotSettingsActionOrCounterpart() {
        long a = newAthlete("Dedup key");
        long s = session(a, "2026-12-01");
        long easy = session(a, "2026-12-03");
        long v1 = settings("{\"acwr_high\": 1.3}");
        long v2 = settings("{\"acwr_high\": 1.4}");
        suggestion(a, s, null, "REDUCE", v1, "{\"recovery\": 30}");

        assertThatThrownBy(() -> suggestion(a, s, null, "REDUCE", v1, "{\"recovery\": 28}"))
                .as("fresher inputs alone do not make a new suggestion (coach, 2026-11-17)")
                .isInstanceOf(DataIntegrityViolationException.class);
        suggestion(a, s, null, "REDUCE", v2, "{}");
        suggestion(a, s, null, "REPLACE", v1, "{}");
        suggestion(a, s, easy, "SWAP", v1, "{}");
        long other = newAthlete("Other athlete");
        suggestion(other, session(other, "2026-12-01"), null, "REDUCE", v1, "{}");
        assertThat(count("SELECT count(*) FROM adaptation_suggestion WHERE athlete_id = ?", a)).isEqualTo(4);
    }

    @Test
    void onlyPendingSuggestionsAreUnique() {
        long a = newAthlete("Decided");
        long s = session(a, "2026-12-01");
        long first = suggestion(a, s, null, "REDUCE", null, "{}");
        jdbc.sql("UPDATE adaptation_suggestion SET status = 'REJECTED' WHERE id = ?").param(first).update();

        suggestion(a, s, null, "REDUCE", null, "{}");
        assertThat(count("SELECT count(*) FROM adaptation_suggestion WHERE athlete_id = ?", a)).isEqualTo(2);
    }

    @Test
    void isKeyIsGoneAndDemandIsTheOnlyKeyMarker() {
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'planned_session' AND column_name = 'is_key'""")).isZero();
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'planned_session' AND column_name = 'demand'""")).isEqualTo(1);
    }

    @Test
    void acceptedSuggestionLinksTheChangedSessionAndItsRevision() {
        long a = newAthlete("Links");
        long s = session(a, "2026-12-01");
        long sug = suggestion(a, s, null, "REPLACE", null, "{}");
        long replacement = jdbc.sql("""
                INSERT INTO planned_session (athlete_id, session_date, session_type, title, changed_by_suggestion_id, replaces_session_id)
                VALUES (?, '2026-12-01', 'easy', 'Easy 40 min', ?, ?) RETURNING id""")
                .params(a, sug, s).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO planned_session_revision (athlete_id, planned_session_id, change_kind, before, after, suggestion_id)
                VALUES (?, ?, 'EDIT', '{"title": "Tempo"}', '{"status": "REPLACED"}', ?)""")
                .params(a, s, sug).update();

        assertThat(count("SELECT replaces_session_id FROM planned_session WHERE id = ?", replacement)).isEqualTo(s);
        assertThat(count("SELECT changed_by_suggestion_id FROM planned_session WHERE id = ?", replacement)).isEqualTo(sug);
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE suggestion_id = ?", sug)).isEqualTo(1);
    }

    @Test
    void revisionsAndSettingsVersionsAreAppendOnlyButGoWithTheAthlete() {
        long a = newAthlete("Append only");
        long s = session(a, "2026-12-01");
        jdbc.sql("INSERT INTO planned_session_revision (athlete_id, planned_session_id, change_kind, after) VALUES (?, ?, 'CREATE', '{}')")
                .params(a, s).update();
        long v = settings("{\"acwr_high\": 1.3}");

        for (String sql : List.of(
                "UPDATE planned_session_revision SET after = '{\"x\": 1}' WHERE athlete_id = " + a,
                "DELETE FROM planned_session_revision WHERE athlete_id = " + a,
                "UPDATE settings_version SET settings = '{}' WHERE id = " + v,
                "DELETE FROM settings_version WHERE id = " + v)) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }

        jdbc.sql("DELETE FROM athlete WHERE id = ?").param(a).update();
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE athlete_id = ?", a))
                .as("deleting an athlete still removes their history").isZero();
    }

    @Test
    void daySignalsAreKeyedBySignalsAndSettingsVersion() {
        long a = newAthlete("Signals");
        long v1 = settings("{}");
        long v2 = settings("{\"x\": 1}");
        String insert = "INSERT INTO day_signals (athlete_id, cycle_date, signals_version, settings_version, signals)"
                + " VALUES (?, '2026-12-01', ?, ?, '{\"acwr\": 1.1}')";
        jdbc.sql(insert).params(a, "s1", v1).update();
        jdbc.sql(insert).params(a, "s1", v2).update();
        jdbc.sql(insert).params(a, "s2", v1).update();

        assertThatThrownBy(() -> jdbc.sql(insert).params(a, "s1", v1).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subjectiveCurrentKeepsTheLatestCheckInPerDayAndScope() {
        long a = newAthlete("Check-ins");
        long s = session(a, "2026-12-01");
        String insert = """
                INSERT INTO subjective_log (athlete_id, log_date, planned_session_id, rpe, soreness, injury, niggle_resolved, created_at)
                VALUES (?, '2026-12-01', ?, ?, 2, ?, FALSE, ?::timestamptz)""";
        jdbc.sql(insert).params(a, null, 3, false, "2026-12-01T07:00:00Z").update();      // the day, first
        jdbc.sql(insert).params(a, null, 4, true, "2026-12-01T08:00:00Z").update();       // the day, corrected
        jdbc.sql(insert).params(a, s, 8, false, "2026-12-01T18:00:00Z").update();         // the session
        jdbc.sql(insert).params(a, s, 7, false, "2026-12-01T18:00:00Z").update();         // same time: higher id wins

        var rows = jdbc.sql("SELECT scope_key, rpe, injury FROM subjective_current WHERE athlete_id = ? ORDER BY scope_key")
                .param(a).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("scope_key", "day").containsEntry("rpe", 4).containsEntry("injury", true);
        assertThat(rows.get(1)).containsEntry("scope_key", "session:" + s).containsEntry("rpe", 7);
    }
}
