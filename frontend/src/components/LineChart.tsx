import { useId, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { Band } from '../api/types';
import { formatDay } from '../today';
import { segments } from '../trends';

// A small SVG line chart (stage 1b-2b). No charting library: the app has none, and this needs only
// lines, dots and gaps. A missing day is never drawn: the line breaks there (see `segments`).

export interface ChartSeries {
  id: string;
  label: string;
  values: (number | null)[];
  /** A CSS colour, normally a theme variable such as `var(--accent)`. */
  color: string;
  dashed?: boolean;
  /** Draw the connecting line (default true). */
  line?: boolean;
  /** Per-point band colour (recovery). */
  bands?: (Band | null | undefined)[];
  /** Per-point hollow marker (lower confidence). */
  hollow?: boolean[];
  /** Unit text in the readout, e.g. "ms". */
  unit?: string;
  /** Decimals in the readout. */
  decimals?: number;
}

const W = 360;
const H = 170;
const PAD = { left: 36, right: 10, top: 10, bottom: 22 };
const BAND_COLOR: Record<Band, string> = { GREEN: 'var(--band-green)', YELLOW: 'var(--band-yellow)', RED: 'var(--band-red)' };

export function LineChart(props: {
  title: string;
  dates: string[];
  series: ChartSeries[];
  /** Unknown days in a row a line may bridge; 0 = every missing day is a gap. */
  maxUnknownRun: number;
  domain?: [number, number];
  /** Index of a day to mark with a dashed vertical line (e.g. where overnight HRV starts). */
  markerIndex?: number;
  markerLabel?: string;
}) {
  const { title, dates, series, maxUnknownRun, domain, markerIndex, markerLabel } = props;
  const { t, i18n } = useTranslation();
  const uid = useId();
  const n = dates.length;
  const [active, setActive] = useState<number | null>(null);
  const shown = active ?? n - 1;

  const all = series.flatMap((s) => s.values).filter((v): v is number => v != null);
  const [lo, hi] = domain ?? autoDomain(all);
  const x = (i: number) => PAD.left + (n <= 1 ? 0 : (i / (n - 1)) * (W - PAD.left - PAD.right));
  const y = (v: number) => PAD.top + (1 - (v - lo) / (hi - lo || 1)) * (H - PAD.top - PAD.bottom);
  const r = n > 200 ? 1.6 : n > 100 ? 2.2 : 3;
  const ticks = [lo, (lo + hi) / 2, hi];
  const fmtDate = (iso: string) => formatDay(iso, i18n.language);

  const indexAt = (clientX: number, rect: DOMRect) => {
    const frac = (clientX - rect.left) / (rect.width || 1);
    const px = frac * W;
    const i = Math.round(((px - PAD.left) / (W - PAD.left - PAD.right)) * (n - 1));
    return Math.min(n - 1, Math.max(0, i));
  };
  const onPointer = (e: PointerEvent<SVGSVGElement>) => setActive(indexAt(e.clientX, e.currentTarget.getBoundingClientRect()));
  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1 };
    if (e.key in moves) setActive(Math.min(n - 1, Math.max(0, shown + moves[e.key])));
    else if (e.key === 'Home') setActive(0);
    else if (e.key === 'End') setActive(n - 1);
    else return;
    e.preventDefault();
  };

  const readout = series.map((s) => {
    const v = s.values[shown];
    if (v == null) return { id: s.id, label: s.label, text: t('trends.noData') };
    const band = s.bands?.[shown];
    const parts = [`${v.toFixed(s.decimals ?? 0)}${s.unit ? ` ${s.unit}` : ''}`];
    if (band) parts.push(t(`trends.band.${band}`));
    if (s.hollow?.[shown]) parts.push(t('trends.lowerConfidence'));
    return { id: s.id, label: s.label, text: parts.join(', ') };
  });

  return (
    <figure className="chart" data-testid={`chart-${series[0]?.id ?? 'x'}`}>
      <figcaption className="chart-title">{title}</figcaption>
      <p className="chart-readout" aria-live="polite" data-testid="readout">
        <time dateTime={dates[shown]}>{dates[shown] ? fmtDate(dates[shown]) : ''}</time>
        {readout.map((r) => (
          <span key={r.id}>
            {series.length > 1 && <span className="muted">{r.label}: </span>}
            {r.text}
          </span>
        ))}
      </p>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="chart-svg"
        role="img"
        aria-label={`${title}. ${t('trends.chartHelp')}`}
        tabIndex={0}
        data-days={n}
        onPointerDown={onPointer}
        onPointerMove={(e) => (e.buttons > 0 || e.pointerType === 'mouse' ? onPointer(e) : undefined)}
        onKeyDown={onKey}
      >
        {ticks.map((tk, i) => (
          <g key={i}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(tk)} y2={y(tk)} className="chart-grid" />
            <text x={PAD.left - 5} y={y(tk) + 3} textAnchor="end" className="chart-tick">
              {fmtTick(tk)}
            </text>
          </g>
        ))}
        {n > 0 && (
          <>
            <text x={PAD.left} y={H - 5} textAnchor="start" className="chart-tick">{shortDate(dates[0], i18n.language)}</text>
            <text x={W - PAD.right} y={H - 5} textAnchor="end" className="chart-tick">{shortDate(dates[n - 1], i18n.language)}</text>
          </>
        )}
        {markerIndex != null && markerIndex > 0 && markerIndex < n && (
          <g data-testid="marker">
            <line x1={x(markerIndex)} x2={x(markerIndex)} y1={PAD.top} y2={H - PAD.bottom} className="chart-marker" />
            {markerLabel && <text x={x(markerIndex) + 3} y={PAD.top + 8} className="chart-tick">{markerLabel}</text>}
          </g>
        )}
        {series.map((s) => {
          const runs = segments(s.values, maxUnknownRun);
          const known = runs.reduce((a, run) => a + run.length, 0);
          const dotAll = n <= 120 || s.line === false || s.bands != null;
          return (
            <g key={s.id} data-series={s.id} data-runs={runs.length} data-known={known}>
              {s.line !== false &&
                runs
                  .filter((run) => run.length > 1)
                  .map((run, k) => (
                    <polyline
                      key={`${uid}-${k}`}
                      className="chart-line"
                      points={run.map((p) => `${x(p.index).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')}
                      style={{ stroke: s.color }}
                      strokeDasharray={s.dashed ? '5 4' : undefined}
                    />
                  ))}
              {runs.flat().map((p) => {
                const isolated = runs.some((run) => run.length === 1 && run[0].index === p.index);
                if (!dotAll && !isolated) return null;
                const color = s.bands?.[p.index] ? BAND_COLOR[s.bands[p.index] as Band] : s.color;
                const hollow = s.hollow?.[p.index];
                return (
                  <circle
                    key={p.index}
                    cx={x(p.index)}
                    cy={y(p.value)}
                    r={r}
                    className="chart-dot"
                    style={{ fill: hollow ? 'var(--surface)' : color, stroke: color }}
                    data-hollow={hollow ? 'true' : undefined}
                  />
                );
              })}
            </g>
          );
        })}
        {n > 0 && <line x1={x(shown)} x2={x(shown)} y1={PAD.top} y2={H - PAD.bottom} className="chart-cursor" />}
      </svg>
    </figure>
  );
}

function autoDomain(values: number[]): [number, number] {
  if (values.length === 0) return [0, 1];
  let lo = Math.min(...values);
  let hi = Math.max(...values);
  if (lo === hi) {
    lo -= 1;
    hi += 1;
  }
  const pad = (hi - lo) * 0.1;
  return [lo - pad, hi + pad];
}

function fmtTick(v: number): string {
  return Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(Math.abs(v) < 10 ? 1 : 0);
}

function shortDate(iso: string, lang: string): string {
  const [yy, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(yy, m - 1, d)).toLocaleDateString(lang, { day: 'numeric', month: 'short', timeZone: 'UTC' });
}
