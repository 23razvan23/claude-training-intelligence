import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useFeatureSummary, useModelReport, useModels, useRecomputeFeatures } from '../api/hooks';
import type { ModelVersion } from '../api/types';
import { useAthleteSelection } from '../athleteContext';

const pct = (v?: number) => (v == null ? '–' : `${Math.round(v * 100)}%`);
const num = (v?: number) => (v == null ? '–' : v.toFixed(2));

export function CalibrationPage() {
  const { t } = useTranslation();
  const { athleteId } = useAthleteSelection();
  const { data: summary } = useFeatureSummary(athleteId);
  const { data: models } = useModels(athleteId);
  const recompute = useRecomputeFeatures(athleteId);
  const [open, setOpen] = useState<number>();

  if (athleteId == null) return <p className="muted">{t('common.selectAthlete')}</p>;
  const r = recompute.data;
  const noLoad = (summary?.activityLoadMethods?.none ?? 0) > 0 || (r?.load.withoutLoad ?? 0) > 0;

  return (
    <section className="stack">
      <h1>{t('calibration.title')}</h1>
      <p className="muted">{t('calibration.intro')}</p>

      <div className="card stack">
        <div className="row wrap">
          <h2>{t('calibration.features')}</h2>
          <span className="spacer" />
          <button disabled={recompute.isPending} onClick={() => recompute.mutate()}>
            {recompute.isPending ? t('calibration.recomputing') : t('calibration.recompute')}
          </button>
        </div>
        <p className="muted small">{t('calibration.featuresHint')}</p>
        {recompute.isError && <p className="error">{t('common.error', { message: recompute.error.message })}</p>}
        {r && <p className="small">{t('calibration.recomputed', { ...r.features, ...r.load })}</p>}
        {r?.load.sexAssumed && <p className="warn small">{t('calibration.sexAssumed')}</p>}
        {noLoad && <p className="warn small">{t('calibration.noHrMax')}</p>}
        {summary && summary.days === 0 && <p className="muted">{t('calibration.empty')}</p>}
        {summary && summary.days > 0 && (
          <>
            <div className="tiles">
              <div className="tile accent">
                <div className="tile-value">{summary.paired}</div>
                <div className="tile-label">{t('calibration.stat.paired')}</div>
              </div>
              <div className="tile">
                <div className="tile-value">{summary.withGarminCore}</div>
                <div className="tile-label">{t('calibration.stat.garmin')}</div>
              </div>
              <div className="tile">
                <div className="tile-value">{summary.withWhoop}</div>
                <div className="tile-label">{t('calibration.stat.whoop')}</div>
              </div>
            </div>
            {summary.pairedFrom && (
              <p className="small">{t('calibration.pairedRange', { from: summary.pairedFrom, to: summary.pairedTo })}</p>
            )}
            <table className="small">
              <tbody>
                <tr>
                  <td>{t('calibration.stat.days')}</td>
                  <td className="num">{summary.days}</td>
                </tr>
                <tr>
                  <td>{t('calibration.stat.noBaseline')}</td>
                  <td className="num">{summary.withoutBaseline}</td>
                </tr>
                <tr>
                  <td>{t('calibration.stat.noLoad')}</td>
                  <td className="num">{summary.withoutLoad}</td>
                </tr>
              </tbody>
            </table>
          </>
        )}
      </div>

      <div className="card stack">
        <h2>{t('calibration.run')}</h2>
        <p className="muted small">{t('calibration.runHint')}</p>
        <pre className="code">docker compose run --rm calibration --athlete {athleteId}</pre>
      </div>

      <h2>{t('calibration.models')}</h2>
      {models?.length === 0 && <p className="muted">{t('calibration.noModels')}</p>}
      <ul className="list">
        {models?.map((m) => (
          <ModelRow key={m.id} model={m} open={open === m.id} onToggle={() => setOpen(open === m.id ? undefined : m.id)} />
        ))}
      </ul>
    </section>
  );
}

function ModelRow({ model, open, onToggle }: { model: ModelVersion; open: boolean; onToggle: () => void }) {
  const { t } = useTranslation();
  const report = useModelReport(open ? model.id : undefined);
  const c = model.metrics.checks;
  const chip = (ok: boolean | undefined, text: string) => <span className={`badge ${ok ? 'succeeded' : 'failed'}`}>{text}</span>;
  return (
    <li className="card stack">
      <div className="row wrap">
        <span className={`badge ${model.passed ? 'succeeded' : 'failed'}`}>{model.passed ? t('calibration.pass') : t('calibration.fail')}</span>
        <strong>{model.version}</strong>
        <span className="muted small">{model.status}</span>
        <span className="spacer" />
        <span className="muted small">{new Date(model.createdAt).toLocaleString()}</span>
      </div>
      <div className="row wrap small">
        {chip(c?.strain_mae, t('calibration.strainMae', { v: num(model.metrics.strain?.mae) }))}
        {chip(c?.recovery_band_agreement, t('calibration.band', { v: pct(model.metrics.recovery?.agreement) }))}
        {chip(c?.recovery_green_red, t('calibration.greenRed', { v: pct(model.metrics.recovery?.green_red_rate) }))}
      </div>
      <div className="muted small">{t('calibration.window', { trainFrom: model.trainFrom, trainTo: model.trainTo, testFrom: model.testFrom, testTo: model.testTo })}</div>
      <div>
        <button className="ghost" onClick={onToggle}>
          {open ? t('calibration.hide') : t('calibration.view')}
        </button>
      </div>
      {open && report.data && (
        // No allow-scripts: the report is static HTML; the server also sends a no-script CSP.
        <iframe className="report" title={model.version} sandbox="" srcDoc={report.data} />
      )}
    </li>
  );
}
