import { useTranslation } from 'react-i18next';
import type { StoredSignals } from '../api/types';
import { formatDay } from '../today';
import { byDate, latest, shareOfTolerance, signed } from '../signals';
import { LineChart } from './LineChart';

// The adaptive engine's signals on Trends (stage 2-2, ADR 0021): running volume against tolerance over the chosen
// range, and the newest day's signals in plain words. Display only; every number is computed and stored by the server.

export function SignalsSection({ rows, dates, firstHrvDate }: { rows: StoredSignals[]; dates: string[]; firstHrvDate: string | null }) {
  const { t, i18n } = useTranslation();
  const last = latest(rows);
  if (!last) {
    return (
      <div className="card" data-testid="signals-card">
        <h2>{t('signals.title')}</h2>
        <p className="muted small">{t('signals.none')}</p>
      </div>
    );
  }
  const s = last.signals;
  const tol = s.tolerance;
  const share = shareOfTolerance(s.running, tol);
  const r = s.readiness;
  const sub = s.subjective;

  return (
    <>
      <div className="card" data-testid="volume-card">
        <LineChart
          title={t('signals.volume.title')}
          dates={dates}
          maxUnknownRun={0}
          series={[
            { id: 'km7', label: t('signals.volume.km7'), values: byDate(rows, dates, (x) => x.signals.running.shortKm), color: 'var(--chart-a)', unit: 'km', decimals: 1 },
            {
              id: 'tolerance',
              label: t('signals.volume.tolerance'),
              values: byDate(rows, dates, (x) => x.signals.tolerance?.kmPerWeek),
              color: 'var(--muted)',
              dashed: true,
              unit: 'km',
              decimals: 1,
            },
          ]}
        />
        <p className="legend small muted">
          <span className="swatch" style={{ background: 'var(--chart-a)' }} /> {t('signals.volume.km7')}
          <span className="swatch" style={{ background: 'var(--muted)' }} /> {t('signals.volume.tolerance')}
        </p>
      </div>

      <div className="card stack" data-testid="signals-card">
        <h2>{t('signals.title')}</h2>
        <p className="muted small">{t('signals.asOf', { date: formatDay(last.date, i18n.language) })}</p>
        <ul className="list small">
          <li data-testid="sig-readiness">
            {r && r.recoveryPct != null
              ? t('signals.readiness', {
                  pct: r.recoveryPct,
                  band: t(`trends.band.${r.band}`),
                  short: r.trendShort == null ? '–' : signed(r.trendShort),
                  shortDays: r.trendShortDays,
                  long: r.trendLong == null ? '–' : signed(r.trendLong),
                  longDays: r.trendLongDays,
                })
              : t('signals.readinessNone')}
            {r && r.recoveryPct != null && r.nonGreenStreakDays >= 2 && <> {t('signals.nonGreen', { n: r.nonGreenStreakDays })}</>}
          </li>
          <li data-testid="sig-sleep">
            {s.sleep && s.sleep.debtMin != null
              ? t('signals.sleep', { h: (s.sleep.debtMin / 60).toFixed(1), nights: s.sleep.nightsOfNeed?.toFixed(2) ?? '–', days: s.sleep.windowDays })
              : t('signals.sleepNone')}
          </li>
          <li data-testid="sig-acwr">
            {t('signals.acwr', {
              total: s.load.acwr?.toFixed(2) ?? '–',
              running: s.load.running.acwr?.toFixed(2) ?? '–',
            })}
          </li>
          <li data-testid="sig-foster">
            {s.foster.monotony != null
              ? t('signals.foster', { monotony: s.foster.monotony.toFixed(2), strain: Math.round(s.foster.strain ?? 0) })
              : t('signals.fosterNone', { n: s.foster.knownDays, days: s.foster.windowDays })}
          </li>
          <li data-testid="sig-volume">
            {t('signals.volume.now', {
              km7: s.running.shortKm.toFixed(1),
              avg21: s.running.longWeeklyAvgKm.toFixed(1),
              tol: tol?.kmPerWeek?.toFixed(1) ?? '–',
              share: share == null ? '–' : share,
            })}
          </li>
          {sub && (sub.illness || sub.injury || sub.niggles.length > 0) && (
            <li data-testid="sig-subjective">
              {[
                sub.illness && t('signals.illness'),
                sub.injury && t('signals.injury'),
                ...sub.niggles.map((n) => t('signals.niggle', { where: t(`checkin.location.${n.location}`, n.location), days: n.persistDays })),
              ]
                .filter(Boolean)
                .join(' · ')}
            </li>
          )}
        </ul>
        <p className="muted small">{t('signals.acwrNote')}</p>
        {firstHrvDate && <p className="muted small">{t('signals.hrvNote', { date: firstHrvDate })}</p>}
      </div>
    </>
  );
}
