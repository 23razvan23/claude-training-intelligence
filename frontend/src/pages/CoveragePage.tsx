import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useCoverage } from '../api/hooks';
import type { CoverageDay } from '../api/types';
import { useAthleteSelection } from '../athleteContext';

function cellClass(d: CoverageDay) {
  if (d.paired) return 'paired';
  if (d.garminComplete) return 'garmin';
  if (d.whoopComplete) return 'whoop';
  return 'none';
}

/** Groups days into Monday-first weeks for the calendar grid. */
function weeks(days: CoverageDay[]): (CoverageDay | null)[][] {
  if (days.length === 0) return [];
  const out: (CoverageDay | null)[][] = [];
  const firstDow = (new Date(days[0].date + 'T00:00:00Z').getUTCDay() + 6) % 7;
  let week: (CoverageDay | null)[] = Array(firstDow).fill(null);
  for (const d of days) {
    week.push(d);
    if (week.length === 7) {
      out.push(week);
      week = [];
    }
  }
  if (week.length) out.push([...week, ...Array(7 - week.length).fill(null)]);
  return out;
}

export function CoveragePage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const { data: r, isLoading, error } = useCoverage(athleteId);
  const grid = useMemo(() => weeks(r?.days ?? []), [r]);

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  if (isLoading) return <p className="muted">{t('common.loading')}</p>;
  if (error) return <p className="error">{t('common.error', { message: error.message })}</p>;
  if (!r || r.totalDays === 0) {
    return (
      <section className="stack">
        <h1>{t('coverage.title')}</h1>
        <p className="muted">{t('coverage.empty')}</p>
      </section>
    );
  }

  const pct = (n: number) => `${Math.round((100 * n) / r.totalDays)}%`;

  return (
    <section className="stack">
      <h1>{t('coverage.title')}</h1>
      <p className="muted">{t('coverage.intro')}</p>
      <p className="small">{t('coverage.range', { from: r.from, to: r.to, days: r.totalDays })}</p>

      <div className="tiles">
        <div className="tile accent">
          <div className="tile-value">{r.pairedDays}</div>
          <div className="tile-label">
            {t('coverage.paired')} · {pct(r.pairedDays)}
          </div>
        </div>
        <div className="tile">
          <div className="tile-value">{r.garminCompleteDays}</div>
          <div className="tile-label">
            {t('coverage.garmin')} · {pct(r.garminCompleteDays)}
          </div>
        </div>
        <div className="tile">
          <div className="tile-value">{r.whoopCompleteDays}</div>
          <div className="tile-label">
            {t('coverage.whoop')} · {pct(r.whoopCompleteDays)}
          </div>
        </div>
      </div>
      <p className="muted small">{t('coverage.definition')}</p>

      <h2>{t('coverage.calendar')}</h2>
      <div className="legend small">
        <span><i className="cell paired" /> {t('coverage.legendPaired')}</span>
        <span><i className="cell garmin" /> {t('coverage.legendGarmin')}</span>
        <span><i className="cell whoop" /> {t('coverage.legendWhoop')}</span>
        <span><i className="cell none" /> {t('coverage.legendNone')}</span>
      </div>
      <div className="calendar" role="img" aria-label={t('coverage.calendar')}>
        {grid.map((w, i) => (
          <div className="week" key={i}>
            {w.map((d, j) =>
              d ? <i key={j} className={`cell ${cellClass(d)}`} title={`${d.date}: ${t(`coverage.legend${cellClass(d)[0].toUpperCase()}${cellClass(d).slice(1)}`)}`} /> : <i key={j} className="cell blank" />,
            )}
          </div>
        ))}
      </div>

      <h2>{t('coverage.fields')}</h2>
      <table className="fields">
        <tbody>
          {Object.entries(r.fieldDays).map(([k, v]) => (
            <tr key={k}>
              <td>{t(`coverage.field.${k}`)}</td>
              <td className="num">{v}</td>
              <td className="bar-cell">
                <span className="bar" style={{ width: pct(v) }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>{t('coverage.gaps')}</h2>
      {r.pairedGaps.length === 0 ? (
        <p className="muted">{t('coverage.noGaps')}</p>
      ) : (
        <ul className="small">
          {r.pairedGaps.map((g) => (
            <li key={g.from}>{t('coverage.gapRow', g)}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
