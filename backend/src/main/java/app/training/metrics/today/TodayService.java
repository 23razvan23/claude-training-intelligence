package app.training.metrics.today;

import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsProperties;
import app.training.metrics.MetricsRepository;
import app.training.metrics.ModelVersion;
import app.training.metrics.ScoringContract;
import app.training.metrics.ScoringRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The Today screen (stage 1b-2a, ADR 0014). Read-only: it reads derived_day_metrics and never scores. */
@Service
public class TodayService {
    private final AthleteRepository athletes;
    private final MetricsRepository models;
    private final ScoringRepository scores;
    private final JdbcClient jdbc;
    private final TodayProperties props;
    private final MetricsProperties metrics;
    private final Clock clock;

    public TodayService(AthleteRepository athletes, MetricsRepository models, ScoringRepository scores, JdbcClient jdbc,
                        TodayProperties props, MetricsProperties metrics, Clock clock) {
        this.athletes = athletes;
        this.models = models;
        this.scores = scores;
        this.jdbc = jdbc;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    public TodayView.Today today(long athleteId) {
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        ZoneId zone = ZoneId.of(athlete.timezone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        ModelVersion model = models.active(athleteId).orElse(null);
        if (model == null) return TodayView.empty(today, zone.getId(), null);

        LocalDate newest = jdbc.sql("""
                SELECT max(cycle_date) FROM derived_day_metrics
                WHERE athlete_id = ? AND model_version = ? AND cycle_date <= ?""")
                .params(athleteId, model.version(), today)
                .query(LocalDate.class).optional().orElse(null);
        if (newest == null) return TodayView.empty(today, zone.getId(), model.version());

        var rows = scores.days(athleteId, model.version(), newest.minusDays(props.strainTargetWindowDays()), newest);
        var lnRmssd = new TreeMap<LocalDate, Double>();
        var rmssd = new HashMap<LocalDate, Double>();
        jdbc.sql("""
                SELECT cycle_date, ln_rmssd, rmssd FROM day_features
                WHERE athlete_id = ? AND feature_version = ? AND cycle_date BETWEEN ? AND ?""")
                .params(athleteId, model.featureVersion(), newest.minusDays(metrics.baselineWindowDays()), newest)
                .query(rs -> {
                    LocalDate d = rs.getObject("cycle_date", LocalDate.class);
                    var ln = rs.getBigDecimal("ln_rmssd");
                    var ms = rs.getBigDecimal("rmssd");
                    if (ln != null) lnRmssd.put(d, ln.doubleValue());
                    if (ms != null) rmssd.put(d, ms.doubleValue());
                });
        ScoringContract contract;
        try {
            contract = ScoringContract.parse(model.coefficients());
        } catch (RuntimeException e) {
            contract = null; // the "usual" values are then left out; the scores are still shown
        }
        var view = TodayView.build(today, zone.getId(), model.version(), rows, lnRmssd, rmssd, contract, props,
                metrics.baselineWindowDays(), metrics.baselineMinDays());
        return markLiveBreathing(athleteId, view);
    }

    /**
     * Marks the shown day when its night's breathing rate came from Garmin's live data (a synced
     * night, not an exported one): recovery is then approximate until the next export (ADR 0015 D12).
     */
    TodayView.Today markLiveBreathing(long athleteId, TodayView.Today view) {
        TodayView.Day shown = view.day() != null ? view.day() : view.lastScored();
        if (shown == null) return view;
        boolean live = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM sleep_session_current s JOIN import_batch b ON b.id = s.import_batch_id
                               WHERE s.athlete_id = ? AND s.source = 'GARMIN' AND NOT s.is_nap AND s.cycle_date = ?
                                 AND s.avg_respiration IS NOT NULL AND b.kind = 'GARMIN_SYNC')""")
                .params(athleteId, shown.date()).query(Boolean.class).single();
        if (!live) return view;
        return view.day() != null
                ? new TodayView.Today(view.today(), view.timezone(), view.state(), view.modelVersion(), view.lastDataDate(),
                        view.day().withLiveBreathing(true), view.lastScored())
                : new TodayView.Today(view.today(), view.timezone(), view.state(), view.modelVersion(), view.lastDataDate(),
                        view.day(), view.lastScored().withLiveBreathing(true));
    }
}
