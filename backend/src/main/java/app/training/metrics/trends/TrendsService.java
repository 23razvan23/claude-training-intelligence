package app.training.metrics.trends;

import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsRepository;
import app.training.metrics.ModelVersion;
import app.training.metrics.ScoringRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The Trends screen (stage 1b-2b, ADR 0015). Read-only: it reads day_features and derived_day_metrics, writes nothing. */
@Service
public class TrendsService {
    private final AthleteRepository athletes;
    private final MetricsRepository models;
    private final ScoringRepository scores;
    private final JdbcClient jdbc;
    private final TrendsProperties props;
    private final Clock clock;

    public TrendsService(AthleteRepository athletes, MetricsRepository models, ScoringRepository scores, JdbcClient jdbc,
                         TrendsProperties props, Clock clock) {
        this.athletes = athletes;
        this.models = models;
        this.scores = scores;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public TrendsView.Trends trends(long athleteId) {
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        LocalDate to = LocalDate.now(clock.withZone(ZoneId.of(athlete.timezone())));
        LocalDate from = to.minusDays((long) props.historyDays() + props.warmupDays());
        ModelVersion model = models.active(athleteId).orElse(null);
        if (model == null) return TrendsView.empty(from, to, props.historyDays());

        var features = new TreeMap<LocalDate, TrendsView.Features>();
        jdbc.sql("""
                SELECT cycle_date, rmssd, rhr, sleep_minutes, day_load_edwards FROM day_features
                WHERE athlete_id = ? AND feature_version = ? AND cycle_date BETWEEN ? AND ?""")
                .params(athleteId, model.featureVersion(), from, to)
                .query(rs -> {
                    var rmssd = rs.getBigDecimal("rmssd");
                    var load = rs.getBigDecimal("day_load_edwards");
                    features.put(rs.getObject("cycle_date", LocalDate.class), new TrendsView.Features(
                            rmssd == null ? null : rmssd.doubleValue(), (Integer) rs.getObject("rhr"),
                            (Integer) rs.getObject("sleep_minutes"), load == null ? null : load.doubleValue()));
                });
        LocalDate firstHrv = jdbc.sql("""
                SELECT min(cycle_date) FROM day_features
                WHERE athlete_id = ? AND feature_version = ? AND rmssd IS NOT NULL""")
                .params(athleteId, model.featureVersion())
                .query(LocalDate.class).optional().orElse(null);
        var stored = scores.days(athleteId, model.version(), from, to);
        return TrendsView.build(model.version(), model.featureVersion(), from, to, firstHrv, props.historyDays(), features, stored);
    }
}
