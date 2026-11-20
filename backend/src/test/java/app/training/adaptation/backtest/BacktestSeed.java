package app.training.adaptation.backtest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A synthetic training history for the backtest tests (no personal data): {@link #DAYS} days ending today, runs four
 * days a week (easy 8 km on day 0 and 2 of each 7, a threshold run on day 5, a 20 km long run on day 6), HRV and
 * sleep every night, and an ACTIVE model with a green score every day unless told otherwise.
 */
final class BacktestSeed {
    static final int DAYS = 120;

    final JdbcClient jdbc;
    final long athlete;
    final LocalDate first;
    final String model;

    BacktestSeed(JdbcClient jdbc, long athlete, LocalDate today) {
        this.jdbc = jdbc;
        this.athlete = athlete;
        this.first = today.minusDays(DAYS - 1);
        this.model = "m-bt-" + athlete;
    }

    LocalDate day(int i) {
        return first.plusDays(i);
    }

    BacktestSeed history() {
        for (int i = 0; i < DAYS; i++) {
            int dow = i % 7;
            double km = switch (dow) {
                case 0, 2 -> 8;
                case 5 -> 12;
                case 6 -> 20;
                default -> 0;
            };
            double load = km * 9;
            jdbc.sql("""
                    INSERT INTO day_features (athlete_id, cycle_date, feature_version, day_load_edwards, day_load_banister, run_km,
                        rmssd, sleep_minutes, has_garmin_core) VALUES (?, ?, 'f3', ?, ?, ?, 55, 420, true)""")
                    .params(athlete, day(i), load, load * 0.8, km).update();
            if (km > 0) {
                double z4 = dow == 5 ? 20 : 0, z5 = dow == 5 ? 2 : 0;
                activity(day(i), 7, "running", "running", km, dow == 6 ? 110 : km * 5.5, z4, z5, load);
            }
        }
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, ?, 'ACTIVE', 'f3', '{}'::jsonb, '{}', true, '')""").params(athlete, model).update();
        for (int i = 0; i < DAYS; i++) score(i, 70, "GREEN");
        return this;
    }

    void score(int i, int pct, String band) {
        jdbc.sql("DELETE FROM derived_day_metrics WHERE athlete_id = ? AND cycle_date = ? AND model_version = ?").params(athlete, day(i), model).update();
        jdbc.sql("""
                INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, recovery_band,
                    strain, sleep_performance_pct, sleep_need_min) VALUES (?, ?, ?, 'f3', ?, ?, 10, 88, 480)""")
                .params(athlete, day(i), model, pct, band).update();
    }

    /** A Garmin activity starting at {@code hourUtc} on {@code date}, with its load and heart-rate-reserve zone minutes. */
    long activity(LocalDate date, int hourUtc, String sport, String sourceSport, double km, double minutes, double z4, double z5, double load) {
        long id = jdbc.sql("""
                INSERT INTO activity (athlete_id, source, source_id, sport, source_sport, start_at, calendar_date, duration_s, distance_m)
                VALUES (?, 'GARMIN', ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(athlete, "bt-" + athlete + "-" + date + "-" + hourUtc + "-" + System.nanoTime(), sport, sourceSport,
                        date.atTime(hourUtc, 0).atOffset(ZoneOffset.UTC), date, (int) Math.round(minutes * 60), km * 1000)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO activity_load (activity_id, athlete_id, trimp_edwards, trimp_banister, method, feature_version, hrr_zone_minutes)
                VALUES (?, ?, ?, ?, 'stream', 'f3', ?::numeric[])""")
                .params(id, athlete, load, load * 0.8, "{" + (minutes - z4 - z5) + ",0,0,0," + z4 + "," + z5 + "}").update();
        return id;
    }
}
