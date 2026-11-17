import { useTranslation } from 'react-i18next';
import { useToday } from '../api/hooks';
import type { Band, StrainTarget, Today, TodayDay } from '../api/types';
import { useAthleteSelection } from '../athleteContext';
import { describeContribution, describeReason, formatDay, formatMinutes, formatPoints } from '../today';

// The 30-second morning check (stage 1b-2a, ADR 0014). It only shows stored scores; it never computes one.
// Rule: the "today" card is only ever today's row. An older day is shown only under its own date.

const STRAIN_MAX = 21;

export function TodayPage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const { data, error, isLoading } = useToday(athleteId);

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  if (isLoading) return <p className="muted">{t('common.loading')}</p>;
  if (error) return <p className="warn">{t('common.error', { message: error.message })}</p>;
  if (!data) return null;
  return <TodayView data={data} />;
}

export function TodayView({ data }: { data: Today }) {
  const { t, i18n } = useTranslation();
  const day = (iso: string) => formatDay(iso, i18n.language);

  return (
    <section className="stack today" data-state={data.state}>
      <div className="row wrap">
        <h1>{t('today.title')}</h1>
        <span className="muted">
          <time dateTime={data.today}>{day(data.today)}</time>
        </span>
      </div>

      {data.state === 'NO_MODEL' && <p className="card muted">{t('today.noModel')}</p>}
      {data.state === 'NO_DATA' && <p className="card muted">{t('today.noData')}</p>}

      {data.state === 'STALE' && (
        <>
          <div className="banner" role="status" data-testid="stale-banner">
            <strong>{t('today.staleTitle')}</strong>
            <span>{t('today.staleBody', { date: data.lastDataDate ? day(data.lastDataDate) : '–' })}</span>
          </div>
          {data.lastScored && (
            <article className="stack past" data-testid="last-scored" aria-label={t('today.lastScored', { date: day(data.lastScored.date) })}>
              <h2>
                {t('today.lastScored', { date: '' })}
                <time dateTime={data.lastScored.date}>{day(data.lastScored.date)}</time>
              </h2>
              <p className="muted small">{t('today.notToday')}</p>
              <DayCard d={data.lastScored} isToday={false} />
            </article>
          )}
        </>
      )}

      {(data.state === 'FRESH' || data.state === 'MISSING') && data.day && data.day.date === data.today && (
        <article className="stack" data-testid="today-card">
          <DayCard d={data.day} isToday />
        </article>
      )}
    </section>
  );
}

function DayCard({ d, isToday }: { d: TodayDay; isToday: boolean }) {
  const { t, i18n } = useTranslation();
  const hasRecovery = d.recoveryPct != null && d.band != null;

  return (
    <>
      <div className="card recovery-card">
        {hasRecovery ? (
          <div className="recovery">
            <Dial value={d.recoveryPct!} band={d.band!} label={isToday ? t('today.recovery') : t('today.recoveryOn', { date: formatDay(d.date, i18n.language) })} testId={isToday ? 'today-recovery' : 'past-recovery'} />
            <div className="stack">
              <span className={`band-label band-${d.band!.toLowerCase()}`}>{t(`today.band.${d.band}`)}</span>
              {d.recoveryLowerConfidence && (
                <span className="lowconf" data-testid="recovery-lowconf">
                  {t('today.recoveryLowConf')}
                </span>
              )}
              {d.liveBreathing && (
                <span className="lowconf" data-testid="recovery-live-breathing">
                  {t('today.liveBreathing')}
                </span>
              )}
            </div>
          </div>
        ) : (
          <div className="stack" data-testid="recovery-missing">
            <h2>{isToday ? t('today.notAvailableToday') : t('today.notAvailable')}</h2>
            <ul className="reasons">
              {d.recoveryReasons.map((r) => (
                <li key={r.reason + (r.detail ?? '')}>{describeReason(r, t)}</li>
              ))}
            </ul>
          </div>
        )}
      </div>

      <StrainCard d={d} isToday={isToday} />

      <div className="tiles two">
        <div className="tile">
          <div className="tile-label">{t('today.sleepPerformance')}</div>
          <div className="tile-value">{d.sleepPerformancePct != null ? `${d.sleepPerformancePct}%` : '–'}</div>
          {d.sleepPerformancePct == null && <div className="muted small">{t('today.sleepMissing')}</div>}
        </div>
        <div className="tile">
          <div className="tile-label">{t('today.sleepNeed')}</div>
          <div className="tile-value">{d.sleepNeedMin != null ? formatMinutes(d.sleepNeedMin, t) : '–'}</div>
        </div>
      </div>

      {hasRecovery && d.contributions.length > 0 && (
        <div className="card">
          <h3>{t('today.contributions')}</h3>
          <p className="muted small">{t('today.contributionsHint')}</p>
          <ul className="contribs">
            {d.contributions.map((c) => (
              <li key={c.feature}>
                <span>{describeContribution(c, t)}</span>
                <span className={`points ${c.points != null && Math.round(c.points) > 0 ? 'up' : c.points != null && Math.round(c.points) < 0 ? 'down' : ''}`}>
                  {formatPoints(c.points)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {(d.needsRecompute || d.profileChanged || d.drift) && (
        <ul className="notes small">
          {d.needsRecompute && <li>{t('today.needsRecompute')}</li>}
          {d.profileChanged && <li>{t('today.profileChanged')}</li>}
          {d.drift && <li>{t('today.drift')}</li>}
        </ul>
      )}
    </>
  );
}

function Dial({ value, band, label, testId }: { value: number; band: Band; label: string; testId: string }) {
  const r = 52;
  const c = 2 * Math.PI * r;
  const shown = Math.max(0, Math.min(100, value));
  return (
    <figure className="dial" data-testid={testId} data-band={band} aria-label={`${label}: ${value}%`}>
      <svg viewBox="0 0 120 120" width="132" height="132" aria-hidden="true">
        <circle cx="60" cy="60" r={r} className="dial-track" />
        <circle
          cx="60"
          cy="60"
          r={r}
          className={`dial-arc band-${band.toLowerCase()}`}
          strokeDasharray={`${(shown / 100) * c} ${c}`}
          transform="rotate(-90 60 60)"
        />
      </svg>
      <figcaption>
        <span className="dial-value">{value}%</span>
        <span className="dial-label">{label}</span>
      </figcaption>
    </figure>
  );
}

function StrainCard({ d, isToday }: { d: TodayDay; isToday: boolean }) {
  const { t } = useTranslation();
  const target = d.strainTarget;
  return (
    <div className="card stack strain-card">
      <div className="row">
        <h3>{isToday ? t('today.strainSoFar') : t('today.strain')}</h3>
        <span className="spacer" />
        <span className="strain-value" data-testid="strain-value">
          {d.strain != null ? Number(d.strain).toFixed(1) : '–'}
        </span>
      </div>
      {d.strain == null && d.strainReason && (
        <p className="muted small">{t('today.strainMissing', { reason: t(`today.strainReason.${d.strainReason}`, { defaultValue: d.strainReason }) })}</p>
      )}
      <StrainBar strain={d.strain ?? undefined} target={target ?? undefined} />
      {target ? <TargetText target={target} /> : <p className="muted small">{d.band ? t('today.noTarget') : t('today.noTargetNoBand')}</p>}
      {d.strainLowCoverage && (
        <span className="lowconf" data-testid="strain-lowconf">
          {t('today.strainLowConf')}
        </span>
      )}
    </div>
  );
}

function TargetText({ target }: { target: StrainTarget }) {
  const { t } = useTranslation();
  const args = {
    n: target.basisDays,
    window: target.windowDays,
    lowP: target.lowPercentile,
    highP: target.highPercentile,
  };
  return (
    <>
      <p className="small" data-testid="strain-target">
        {t('today.target', {
          band: t(`today.band.${target.band}`).toLowerCase(),
          low: target.low.toFixed(1),
          high: target.high.toFixed(1),
        })}
      </p>
      <p className="muted small">{target.lowPercentile <= 0 ? t('today.targetBasisFromZero', args) : t('today.targetBasis', args)}</p>
    </>
  );
}

function StrainBar({ strain, target }: { strain?: number; target?: StrainTarget }) {
  const pct = (v: number) => `${(Math.max(0, Math.min(STRAIN_MAX, v)) / STRAIN_MAX) * 100}%`;
  return (
    <div className="strain-bar" aria-hidden="true">
      {target && <span className="strain-target" style={{ left: pct(target.low), width: `calc(${pct(target.high)} - ${pct(target.low)})` }} />}
      {strain != null && <span className="strain-marker" style={{ left: pct(Number(strain)) }} />}
    </div>
  );
}
