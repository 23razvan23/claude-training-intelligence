import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTrends } from '../api/hooks';
import type { Trends } from '../api/types';
import { useAthleteSelection } from '../athleteContext';
import { LineChart } from '../components/LineChart';
import { formatDay } from '../today';
import {
  ACUTE_DAYS,
  CHRONIC_DAYS,
  LOAD_MAX_UNKNOWN_RUN,
  RANGES,
  RANGE_DAYS,
  align,
  dateGrid,
  ewma,
  lastKnown,
  loadRatio,
  tail,
  type Range,
} from '../trends';

// Trends (stage 1b-2b, ADR 0015). It only shows stored values. Days without data are gaps, never zeros.

export function TrendsPage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const { data, error, isLoading } = useTrends(athleteId);

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  if (isLoading) return <p className="muted">{t('common.loading')}</p>;
  if (error) return <p className="warn">{t('common.error', { message: error.message })}</p>;
  if (!data) return null;
  return <TrendsView data={data} />;
}

export function TrendsView({ data }: { data: Trends }) {
  const { t, i18n } = useTranslation();
  const [range, setRange] = useState<Range>('3m');

  const model = useMemo(() => {
    if (data.days.length === 0) return null;
    // The whole fetched window (warm-up included) on a full date grid; absent days are null.
    const all = dateGrid(data.from, data.to);
    const col = (pick: (d: Trends['days'][number]) => number | null | undefined) => align(all, data.days, pick);
    const load = col((d) => d.dayLoadEdwards);
    const acute = ewma(load, ACUTE_DAYS);
    const chronic = ewma(load, CHRONIC_DAYS);
    return {
      all,
      rmssd: col((d) => d.rmssd),
      rhr: col((d) => d.rhr),
      recovery: col((d) => d.recoveryPct),
      bands: align(all, data.days, (d: Trends['days'][number]) => d.band ?? null),
      strain: col((d) => d.strain),
      lowCov: all.map((dt) => data.days.find((d) => d.date === dt)?.strainLowCoverage ?? false),
      sleepPerf: col((d) => d.sleepPerformancePct),
      sleepHours: col((d) => (d.sleepMinutes == null ? null : d.sleepMinutes / 60)),
      needHours: col((d) => (d.sleepNeedMin == null ? null : d.sleepNeedMin / 60)),
      acute,
      chronic,
      ratio: loadRatio(acute, chronic),
    };
  }, [data]);

  if (!model) return <section className="stack trends"><h1>{t('trends.title')}</h1><p className="card muted">{t('trends.noData')}</p></section>;

  const days = RANGE_DAYS[range];
  const v = <T,>(a: T[]) => tail(a, days);
  const dates = v(model.all);
  const hrvStart = data.firstHrvDate ? dates.indexOf(data.firstHrvDate) : -1;
  const hrvNote = !data.firstHrvDate
    ? t('trends.hrvNone')
    : dates[0] < data.firstHrvDate
      ? t('trends.hrvFrom', { date: data.firstHrvDate })
      : null;
  const acute = v(model.acute);
  const chronic = v(model.chronic);
  const ratio = v(model.ratio);
  const lastRatio = lastKnown(ratio);
  const chronicShown = chronic.some((x) => x != null);

  return (
    <section className="stack trends">
      <div className="row wrap">
        <h1>{t('trends.title')}</h1>
        <span className="spacer" />
        <div className="range-switch" role="group" aria-label={t('trends.range')}>
          {RANGES.map((r) => (
            <button key={r} type="button" aria-pressed={range === r} className={range === r ? 'active' : ''} onClick={() => setRange(r)}>
              {t(`trends.ranges.${r}`)}
            </button>
          ))}
        </div>
      </div>
      <p className="muted small">{t('trends.gapsNote')}</p>

      <div className="card">
        <LineChart
          title={t('trends.hrv.title')}
          dates={dates}
          maxUnknownRun={0}
          series={[{ id: 'hrv', label: t('trends.hrv.title'), values: v(model.rmssd), color: 'var(--chart-a)', unit: 'ms', decimals: 0 }]}
          markerIndex={hrvStart}
          markerLabel={t('trends.hrvStartLabel')}
        />
        {hrvNote && <p className="muted small" data-testid="hrv-note">{hrvNote}</p>}
      </div>

      <div className="card">
        <LineChart
          title={t('trends.rhr.title')}
          dates={dates}
          maxUnknownRun={0}
          series={[{ id: 'rhr', label: t('trends.rhr.title'), values: v(model.rhr), color: 'var(--chart-b)', unit: t('trends.bpm'), decimals: 0 }]}
        />
      </div>

      <div className="card">
        <LineChart
          title={t('trends.recovery.title')}
          dates={dates}
          maxUnknownRun={0}
          domain={[0, 100]}
          series={[
            { id: 'recovery', label: t('trends.recovery.title'), values: v(model.recovery), color: 'var(--muted)', line: false, bands: v(model.bands), unit: '%', decimals: 0 },
          ]}
        />
        <p className="legend small muted">
          <span className="swatch" style={{ background: 'var(--band-green)' }} /> {t('trends.band.GREEN')}
          <span className="swatch" style={{ background: 'var(--band-yellow)' }} /> {t('trends.band.YELLOW')}
          <span className="swatch" style={{ background: 'var(--band-red)' }} /> {t('trends.band.RED')}
        </p>
      </div>

      <div className="card">
        <LineChart
          title={t('trends.strain.title')}
          dates={dates}
          maxUnknownRun={0}
          domain={[0, 21]}
          series={[{ id: 'strain', label: t('trends.strain.title'), values: v(model.strain), color: 'var(--chart-a)', hollow: v(model.lowCov), decimals: 1 }]}
        />
        <p className="legend small muted">
          <span className="swatch hollow" /> {t('trends.lowerConfidence')}: {t('trends.strain.lowCovHelp')}
        </p>
      </div>

      <div className="card">
        <LineChart
          title={t('trends.sleepPerf.title')}
          dates={dates}
          maxUnknownRun={0}
          domain={[0, 100]}
          series={[{ id: 'sleepPerf', label: t('trends.sleepPerf.title'), values: v(model.sleepPerf), color: 'var(--chart-b)', unit: '%', decimals: 0 }]}
        />
      </div>

      <div className="card">
        <LineChart
          title={t('trends.sleepDur.title')}
          dates={dates}
          maxUnknownRun={0}
          series={[
            { id: 'sleepHours', label: t('trends.sleepDur.slept'), values: v(model.sleepHours), color: 'var(--chart-b)', unit: 'h', decimals: 1 },
            { id: 'needHours', label: t('trends.sleepDur.need'), values: v(model.needHours), color: 'var(--muted)', dashed: true, unit: 'h', decimals: 1 },
          ]}
        />
      </div>

      <div className="card" data-testid="load-card">
        <LineChart
          title={t('trends.load.title')}
          dates={dates}
          maxUnknownRun={LOAD_MAX_UNKNOWN_RUN}
          domain={undefined}
          series={[
            { id: 'acute', label: t('trends.load.acute', { days: ACUTE_DAYS }), values: acute, color: 'var(--chart-a)', decimals: 0 },
            { id: 'chronic', label: t('trends.load.chronic', { days: CHRONIC_DAYS }), values: chronic, color: 'var(--chart-b)', decimals: 0 },
          ]}
        />
        <p className="legend small muted">
          <span className="swatch" style={{ background: 'var(--chart-a)' }} /> {t('trends.load.acute', { days: ACUTE_DAYS })}
          <span className="swatch" style={{ background: 'var(--chart-b)' }} /> {t('trends.load.chronic', { days: CHRONIC_DAYS })}
        </p>
        {!chronicShown && <p className="muted small" data-testid="chronic-hidden">{t('trends.load.notEnough', { days: CHRONIC_DAYS })}</p>}
        {lastRatio >= 0 && (
          <p className="ratio" data-testid="ratio">
            {t('trends.load.ratio', { value: ratio[lastRatio]!.toFixed(2), date: formatDay(dates[lastRatio], i18n.language) })}
          </p>
        )}
        <p className="muted small">{t('trends.load.note')}</p>
      </div>
    </section>
  );
}
