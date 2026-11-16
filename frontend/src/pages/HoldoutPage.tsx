import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useHoldout, useHoldoutFeed, useHoldoutVersions } from '../api/hooks';
import type { HoldoutFigures, HoldoutMark, HoldoutReport, HoldoutScore, HoldoutSide, Rate, Stat } from '../api/types';
import { useAthleteSelection } from '../athleteContext';

// Live comparison with Whoop (ADR 0009). Read-only by design: nothing here can edit a pass mark or leave a day out.

const pct = (v?: number) => (v == null ? '–' : `${(v * 100).toFixed(1)}%`);
const fix = (s: Stat, digits: number) => (s.value == null ? '–' : s.value.toFixed(digits));
const interval = (r?: Rate) => (r == null || r.n === 0 || r.low == null || r.high == null ? '–' : `${pct(r.low)}–${pct(r.high)}`);
const SCORES: HoldoutScore[] = ['recovery', 'strain', 'sleepPerformance', 'sleepNeed'];
const BANDS = ['GREEN', 'YELLOW', 'RED'];

function RateCell({ r }: { r: Rate }) {
  if (r.n === 0) return <td className="num">–</td>;
  return (
    <td className="num">
      {pct(r.value)}{' '}
      <span className="muted">
        ({r.k}/{r.n}, {interval(r)})
      </span>
    </td>
  );
}

function useGroupLabel() {
  const { t } = useTranslation();
  return (code: string) => {
    const [reason, detail] = code.split('/');
    const label = t(`holdout.group.${reason}`, { defaultValue: reason });
    return detail ? `${label} (${t(`holdout.group.${detail}`, { defaultValue: detail }).toLowerCase()})` : label;
  };
}

export function HoldoutPage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const [version, setVersion] = useState<string>();
  const { data: versions } = useHoldoutVersions(athleteId);
  const { data: r, error, isLoading } = useHoldout(athleteId, version);

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  const reportHref = `/api/athletes/${athleteId}/holdout/report${version ? `?version=${encodeURIComponent(version)}` : ''}`;

  return (
    <section className="stack">
      <h1>{t('holdout.title')}</h1>
      <p className="muted">{t('holdout.intro')}</p>
      <div className="row wrap">
        {versions && versions.length > 1 && (
          <select aria-label={t('holdout.version')} value={version ?? ''} onChange={(e) => setVersion(e.target.value || undefined)}>
            <option value="">{t('holdout.activeVersion')}</option>
            {versions.map((v) => (
              <option key={v.version} value={v.version}>
                {v.version} · {v.status}
              </option>
            ))}
          </select>
        )}
        <span className="spacer" />
        {r && (
          <a className="button" href={reportHref} download>
            {t('holdout.download')}
          </a>
        )}
      </div>
      <Feed athleteId={athleteId} />
      {isLoading && <p className="muted">{t('common.loading')}</p>}
      {error && <p className="warn">{error.message}</p>}
      {r && <Holdout r={r} />}
    </section>
  );
}

function Holdout({ r }: { r: HoldoutReport }) {
  const { t } = useTranslation();
  const v = r.verdict;
  const noScoreYet = r.cumulative.groups.recovery?.NO_SCORE_YET ?? 0;
  const badge =
    v.state === 'INTERIM' || v.state === 'FINAL' ? (v.overall === 'PASS' ? 'succeeded' : 'failed') : 'pending';
  return (
    <>
      <div className="card stack">
        <div className="row wrap">
          <strong>{r.modelVersion}</strong>
          <span className="muted small">{r.status}</span>
          <span className="spacer" />
          <span className={`badge ${badge}`}>{t(`holdout.state.${v.state}`)}</span>
          {v.overall && <span className={`badge ${badge}`}>{t(`holdout.overall.${v.overall}`)}</span>}
        </div>
        <p>{t(`holdout.verdict.${v.state}`, { n: v.recoveryDays, min: v.minRecoveryDays })}</p>
        <p className="small">
          {r.windowTo
            ? t('holdout.window', { from: r.windowFrom, to: r.windowTo, days: r.cumulative.days, end: r.whoopEnd })
            : t('holdout.windowEmpty', { from: r.windowFrom, end: r.whoopEnd })}
        </p>
        <p className="muted small">
          {t('holdout.frozen', {
            when: r.activatedAt ? new Date(r.activatedAt).toLocaleString() : '–',
            source: r.source,
            protocol: r.protocol,
          })}
        </p>
        {r.phase === 'RETIRED' && (
          <p className="warn small">{t('holdout.retired', { when: r.retiredAt ? new Date(r.retiredAt).toLocaleString() : '–' })}</p>
        )}
        {noScoreYet > 0 && <p className="warn small">{t('holdout.noScoreYet', { count: noScoreYet, last: r.lastScoredDate ?? '–' })}</p>}
        {r.markers.staleFeatureDays > 0 && <p className="warn small">{t('holdout.stale', { count: r.markers.staleFeatureDays })}</p>}
      </div>

      <div className="card stack">
        <h2>{t('holdout.marks')}</h2>
        <Marks marks={v.marks} />
        <p className="muted small">{t('holdout.marksHint')}</p>
      </div>

      <div className="card stack">
        <h2>{t('holdout.evidence')}</h2>
        <p className="muted small">{t('holdout.evidenceHint')}</p>
        <FiguresTable f={r.cumulative} />
        <Confusion f={r.cumulative} />
      </div>

      <div className="card stack">
        <h2>{t('holdout.naive')}</h2>
        <p className="muted small">{t('holdout.naiveHint', { n: r.naive.n, skipped: r.naive.skippedNoWhoopYesterday })}</p>
        <div className="table-scroll">
          <table className="small data">
            <thead>
              <tr>
                <th />
                <th className="num">{t('holdout.col.band')}</th>
                <th className="num">{t('holdout.col.greenRed')}</th>
                <th className="num">{t('holdout.col.recoveryMae')}</th>
              </tr>
            </thead>
            <tbody>
              <SideRow label={t('holdout.ours')} s={r.naive.ours} />
              <SideRow label={t('holdout.yesterday')} s={r.naive.naive} />
            </tbody>
          </table>
        </div>
      </div>

      <div className="card stack">
        <h2>{t('holdout.excluded')}</h2>
        <Groups f={r.cumulative} />
      </div>

      <div className="card stack">
        <h2>{t('holdout.drift')}</h2>
        <p className="muted small">{t('holdout.driftHint')}</p>
        {r.rolling && (
          <>
            <h3 className="small">{t('holdout.rolling', { from: r.rolling.from, to: r.rolling.to })}</h3>
            <FiguresTable f={r.rolling} />
          </>
        )}
        <Weeks weeks={r.weeks} />
      </div>

      <div className="card stack">
        <h2>{t('holdout.markers')}</h2>
        <ul className="small">
          <li>
            {t('holdout.recomputeRuns', { count: r.markers.recomputeRuns.length })}
            {r.markers.recomputeRuns.length > 0 && `: ${r.markers.recomputeRuns.map((x) => new Date(x).toLocaleString()).join(', ')}`}
          </li>
          <li>{t('holdout.profileChanged', { count: r.markers.profileChangedDays })}</li>
        </ul>
      </div>

      <details className="card">
        <summary>{t('holdout.days', { count: r.days.length })}</summary>
        <Days r={r} />
      </details>
    </>
  );
}

function Marks({ marks }: { marks: HoldoutMark[] }) {
  const { t } = useTranslation();
  const show = (m: HoldoutMark, v?: number) => (v == null ? '–' : m.key.startsWith('recovery_') ? pct(v) : v.toFixed(2));
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.col.mark')}</th>
            <th className="num">{t('holdout.col.target')}</th>
            <th className="num">{t('holdout.col.measured')}</th>
            <th className="num">n</th>
            <th className="num">{t('holdout.col.interval')}</th>
            <th>{t('holdout.col.result')}</th>
          </tr>
        </thead>
        <tbody>
          {marks.map((m) => (
            <tr key={m.key}>
              <td>{t(`holdout.mark.${m.key}`)}</td>
              <td className="num">
                {m.comparison === '>=' ? '≥' : '≤'} {show(m, m.target)}
              </td>
              <td className="num">{show(m, m.value)}</td>
              <td className="num">{m.n}</td>
              <td className="num">{interval(m.rate)}</td>
              <td>
                {m.met == null ? (
                  <span className="muted">{t('holdout.notJudged')}</span>
                ) : (
                  <span className={`badge ${m.met ? 'succeeded' : 'failed'}`}>{m.met ? t('holdout.met') : t('holdout.notMet')}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FiguresTable({ f }: { f: HoldoutFigures }) {
  const { t } = useTranslation();
  const rec = f.recovery;
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.col.score')}</th>
            <th className="num">n</th>
            <th className="num">{t('holdout.col.band')}</th>
            <th className="num">{t('holdout.col.greenRed')}</th>
            <th className="num">MAE</th>
            <th className="num">Spearman</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              {t('holdout.score.recovery')}
              {rec.lowerConfidence > 0 && <div className="muted">{t('holdout.lowerConfidence', { count: rec.lowerConfidence })}</div>}
            </td>
            <td className="num">{rec.n}</td>
            <RateCell r={rec.bandAgreement} />
            <RateCell r={rec.greenRed} />
            <td className="num">{fix(rec.mae, 1)}</td>
            <td className="num">{fix(rec.spearman, 2)}</td>
          </tr>
          <tr>
            <td>{t('holdout.score.strainFull')}</td>
            <td className="num">{f.strain.n}</td>
            <td />
            <td />
            <td className="num">{fix(f.strain.mae, 2)}</td>
            <td className="num">{fix(f.strain.spearman, 2)}</td>
          </tr>
          <tr className="muted">
            <td>{t('holdout.score.strainLow')}</td>
            <td className="num">{f.strainLowCoverage.n}</td>
            <td />
            <td />
            <td className="num">{fix(f.strainLowCoverage.mae, 2)}</td>
            <td className="num">{fix(f.strainLowCoverage.spearman, 2)}</td>
          </tr>
          <tr>
            <td>{t('holdout.score.sleepPerformance')}</td>
            <td className="num">{f.sleepPerformanceMae.n}</td>
            <td />
            <td />
            <td className="num">{fix(f.sleepPerformanceMae, 1)}</td>
            <td />
          </tr>
          <tr>
            <td>{t('holdout.score.sleepNeed')}</td>
            <td className="num">{f.sleepNeedMae.n}</td>
            <td />
            <td />
            <td className="num">{fix(f.sleepNeedMae, 0)}</td>
            <td />
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function Confusion({ f }: { f: HoldoutFigures }) {
  const { t } = useTranslation();
  if (f.recovery.n === 0) return null;
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.confusion')}</th>
            {BANDS.map((b) => (
              <th key={b} className="num">
                {t(`holdout.band.${b}`)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {BANDS.map((w) => (
            <tr key={w}>
              <td>{t(`holdout.band.${w}`)}</td>
              {BANDS.map((o) => (
                <td key={o} className="num">
                  {f.recovery.confusion[w]?.[o] ?? 0}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SideRow({ label, s }: { label: string; s: HoldoutSide }) {
  return (
    <tr>
      <td>{label}</td>
      <RateCell r={s.bandAgreement} />
      <RateCell r={s.greenRed} />
      <td className="num">{fix(s.recoveryMae, 1)}</td>
    </tr>
  );
}

function Groups({ f }: { f: HoldoutFigures }) {
  const { t } = useTranslation();
  const label = useGroupLabel();
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.col.score')}</th>
            <th>{t('holdout.col.group')}</th>
            <th className="num">{t('holdout.col.days')}</th>
          </tr>
        </thead>
        <tbody>
          {SCORES.flatMap((s) =>
            Object.entries(f.groups[s] ?? {})
              .sort((a, b) => b[1] - a[1])
              .map(([code, n]) => (
                <tr key={`${s}-${code}`}>
                  <td>{t(`holdout.score.${s}`)}</td>
                  <td>{label(code)}</td>
                  <td className="num">{n}</td>
                </tr>
              )),
          )}
        </tbody>
      </table>
    </div>
  );
}

function Weeks({ weeks }: { weeks: HoldoutFigures[] }) {
  const { t } = useTranslation();
  if (weeks.length === 0) return null;
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.col.week')}</th>
            <th className="num">{t('holdout.col.recoveryN')}</th>
            <th className="num">{t('holdout.col.band')}</th>
            <th className="num">{t('holdout.col.greenRed')}</th>
            <th className="num">{t('holdout.col.strainN')}</th>
            <th className="num">{t('holdout.col.strainMae')}</th>
          </tr>
        </thead>
        <tbody>
          {weeks.map((w) => (
            <tr key={w.from}>
              <td className="nowrap">
                {w.from} – {w.to}
              </td>
              <td className="num">{w.recovery.n}</td>
              <RateCell r={w.recovery.bandAgreement} />
              <RateCell r={w.recovery.greenRed} />
              <td className="num">{w.strain.n}</td>
              <td className="num">{fix(w.strain.mae, 2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Days({ r }: { r: HoldoutReport }) {
  const { t } = useTranslation();
  const label = useGroupLabel();
  const band = (b?: string) => (b ? t(`holdout.band.${b}`) : '');
  return (
    <div className="table-scroll">
      <table className="small data">
        <thead>
          <tr>
            <th>{t('holdout.col.day')}</th>
            <th className="num">{t('holdout.col.ourRecovery')}</th>
            <th className="num">{t('holdout.col.whoop')}</th>
            <th className="num">{t('holdout.col.yesterday')}</th>
            <th className="num">{t('holdout.col.ourStrain')}</th>
            <th className="num">{t('holdout.col.whoop')}</th>
            <th className="num">{t('holdout.col.ourSleep')}</th>
            <th className="num">{t('holdout.col.whoop')}</th>
            <th>{t('holdout.col.note')}</th>
          </tr>
        </thead>
        <tbody>
          {r.days.map((d) => {
            const notes = [
              d.recoveryGroup !== 'COMPARED' ? label(d.recoveryGroup) : d.lowerConfidence ? t('holdout.lowerConfidenceDay') : '',
              d.lowCoverage ? t('holdout.lowCoverageDay') : '',
            ].filter(Boolean);
            return (
              <tr key={d.date}>
                <td className="nowrap">{d.date}</td>
                <td className="num">{d.ourRecovery == null ? '–' : `${d.ourRecovery}% ${band(d.ourBand)}`}</td>
                <td className="num">{d.whoopRecovery == null ? '–' : `${d.whoopRecovery}% ${band(d.whoopBand)}`}</td>
                <td className="num muted">{d.naiveRecovery == null ? '–' : `${d.naiveRecovery}%`}</td>
                <td className="num">{d.ourStrain == null ? '–' : d.ourStrain.toFixed(1)}</td>
                <td className="num">{d.whoopStrain == null ? '–' : d.whoopStrain.toFixed(1)}</td>
                <td className="num">{d.ourSleepPerf == null ? '–' : `${d.ourSleepPerf}%`}</td>
                <td className="num">{d.whoopSleepPerf == null ? '–' : `${d.whoopSleepPerf}%`}</td>
                <td>{notes.join('; ')}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** What has arrived and what to do next: the loop the coach repeats until Whoop ends. */
function Feed({ athleteId }: { athleteId: number }) {
  const { t } = useTranslation();
  const { data: f } = useHoldoutFeed(athleteId);
  if (!f) return null;
  const day = (d?: string) => d ?? '–';
  const garminBehind = f.lastWhoopDay != null && f.lastGarminDay != null && f.lastGarminDay < f.lastWhoopDay;
  return (
    <details className="card" open={f.recomputeNeeded || undefined}>
      <summary>
        <strong>{t('holdout.feed.title')}</strong>{' '}
        <span className="muted small">
          {t('holdout.feed.summary', { whoop: day(f.lastWhoopDay), garmin: day(f.lastGarminDay), scored: day(f.lastScoredDay) })}
        </span>
      </summary>
      <div className="stack small feed">
        {f.recomputeNeeded && <p className="warn">{t('holdout.feed.recompute')}</p>}
        {garminBehind && <p className="warn">{t('holdout.feed.garminBehind', { garmin: f.lastGarminDay, whoop: f.lastWhoopDay })}</p>}
        <ol>
          <li>{t('holdout.feed.step1')}</li>
          <li>{t('holdout.feed.step2')}</li>
          <li>{t('holdout.feed.step3')}</li>
          <li>{t('holdout.feed.step4')}</li>
        </ol>
        <p className="muted">{t('holdout.feed.last', { end: f.whoopEnd })}</p>
      </div>
    </details>
  );
}
