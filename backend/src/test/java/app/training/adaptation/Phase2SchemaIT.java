package app.training.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.IntegrationTest;
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
}
