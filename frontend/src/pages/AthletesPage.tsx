import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useAthletes,
  useCreateAthleteLogin,
  useDeleteAthlete,
  useSaveAthlete,
  useUsers,
} from '../api/hooks';
import type { Athlete, AthleteInput, Zone } from '../api/types';
import { useAthleteSelection } from '../athleteContext';
import { ToleranceCard } from '../components/ToleranceCard';
import { formatPace, parsePace } from '../pace';

const EMPTY: AthleteInput = { name: '', timezone: 'Europe/Bucharest' };

const num = (v: string) => (v.trim() === '' ? undefined : Number(v));

export function AthletesPage() {
  const { t } = useTranslation();
  const { data: athletes, isLoading } = useAthletes();
  const { athleteId, setAthleteId } = useAthleteSelection();
  const selected = athletes?.find((a) => a.id === athleteId);
  const [editing, setEditing] = useState<Athlete | 'new' | null>(null);

  if (isLoading) return <p className="muted">{t('common.loading')}</p>;

  return (
    <section className="stack">
      <div className="row">
        <h1>{t('athletes.title')}</h1>
        <span className="spacer" />
        <button onClick={() => setEditing('new')}>{t('athletes.new')}</button>
      </div>
      {editing && (
        <AthleteForm
          key={editing === 'new' ? 'new' : editing.id}
          athlete={editing === 'new' ? undefined : editing}
          onDone={(saved) => {
            setEditing(null);
            if (saved) setAthleteId(saved.id);
          }}
        />
      )}
      {athletes?.length === 0 && <p className="muted">{t('athletes.empty')}</p>}
      <ul className="list">
        {athletes?.map((a) => (
          <li key={a.id} className="card row">
            <div>
              <strong>{a.name}</strong>
              <div className="muted small">
                {[a.hrMax && `HRmax ${a.hrMax}`, a.lthr && `LTHR ${a.lthr}`, a.thresholdPaceSPerKm && `T ${formatPace(a.thresholdPaceSPerKm)}/km`, a.timezone]
                  .filter(Boolean)
                  .join(' · ')}
              </div>
            </div>
            <span className="spacer" />
            <button className="ghost" onClick={() => setEditing(a)}>
              {t('common.edit')}
            </button>
          </li>
        ))}
      </ul>
      {selected && <ToleranceCard key={selected.id} athleteId={selected.id} athleteName={selected.name} />}
    </section>
  );
}

function AthleteForm({ athlete, onDone }: { athlete?: Athlete; onDone: (saved?: Athlete) => void }) {
  const { t } = useTranslation();
  const save = useSaveAthlete();
  const del = useDeleteAthlete();
  const [form, setForm] = useState<AthleteInput>(athlete ?? EMPTY);
  const [pace, setPace] = useState(formatPace(athlete?.thresholdPaceSPerKm));
  const set = <K extends keyof AthleteInput>(k: K, v: AthleteInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const zones: Zone[] = form.hrZones ?? [];

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate(
      { id: athlete?.id, input: { ...form, thresholdPaceSPerKm: parsePace(pace) } },
      { onSuccess: (saved) => onDone(saved) },
    );
  };

  const remove = () => {
    if (athlete && window.confirm(t('athletes.confirmDelete', { name: athlete.name }))) {
      del.mutate(athlete.id, { onSuccess: () => onDone() });
    }
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <div className="grid">
        <label>
          {t('athletes.name')}
          <input value={form.name} onChange={(e) => set('name', e.target.value)} required maxLength={120} />
        </label>
        <label>
          {t('athletes.sex')}
          <select value={form.sex ?? ''} onChange={(e) => set('sex', (e.target.value || undefined) as AthleteInput['sex'])}>
            <option value="">{t('common.none')}</option>
            <option value="M">{t('athletes.sexM')}</option>
            <option value="F">{t('athletes.sexF')}</option>
          </select>
        </label>
        <label>
          {t('athletes.birthYear')}
          <input type="number" value={form.birthYear ?? ''} onChange={(e) => set('birthYear', num(e.target.value))} />
        </label>
        <label>
          {t('athletes.hrMax')}
          <input type="number" min={100} max={250} value={form.hrMax ?? ''} onChange={(e) => set('hrMax', num(e.target.value))} />
        </label>
        <label>
          {t('athletes.restingHr')}
          <input type="number" min={25} max={120} value={form.restingHr ?? ''} onChange={(e) => set('restingHr', num(e.target.value))} />
        </label>
        <label>
          {t('athletes.lthr')}
          <input type="number" min={80} max={230} value={form.lthr ?? ''} onChange={(e) => set('lthr', num(e.target.value))} />
        </label>
        <label>
          {t('athletes.thresholdPace')}
          <input value={pace} placeholder="4:10" pattern="\d{1,2}:[0-5]\d" onChange={(e) => setPace(e.target.value)} />
        </label>
        <label>
          {t('athletes.timezone')}
          <input value={form.timezone} onChange={(e) => set('timezone', e.target.value)} />
        </label>
      </div>
      <label>
        {t('athletes.injuryNotes')}
        <textarea rows={2} value={form.injuryNotes ?? ''} onChange={(e) => set('injuryNotes', e.target.value || undefined)} />
      </label>

      <fieldset className="stack">
        <legend>{t('athletes.hrZones')}</legend>
        {zones.map((z, i) => (
          <div className="row" key={i}>
            <span className="muted">
              {t('athletes.zone')} {z.zone}
            </span>
            <input
              type="number"
              aria-label={t('athletes.zoneMin')}
              value={z.min}
              onChange={(e) => set('hrZones', zones.map((x, j) => (j === i ? { ...x, min: Number(e.target.value) } : x)))}
            />
            <input
              type="number"
              aria-label={t('athletes.zoneMax')}
              value={z.max}
              onChange={(e) => set('hrZones', zones.map((x, j) => (j === i ? { ...x, max: Number(e.target.value) } : x)))}
            />
            <button type="button" className="ghost" onClick={() => set('hrZones', zones.filter((_, j) => j !== i))}>
              ×
            </button>
          </div>
        ))}
        <button
          type="button"
          className="ghost"
          onClick={() => {
            const last = zones[zones.length - 1];
            set('hrZones', [...zones, { zone: zones.length + 1, min: last ? last.max + 1 : 100, max: last ? last.max + 15 : 130 }]);
          }}
        >
          {t('athletes.addZone')}
        </button>
      </fieldset>

      {save.isError && <p className="error">{t('common.error', { message: save.error.message })}</p>}
      <div className="row">
        <button type="submit" disabled={save.isPending}>
          {t('common.save')}
        </button>
        <button type="button" className="ghost" onClick={() => onDone()}>
          {t('common.cancel')}
        </button>
        <span className="spacer" />
        {athlete && (
          <button type="button" className="danger" onClick={remove}>
            {t('common.delete')}
          </button>
        )}
      </div>
      {athlete && <AthleteLogin athleteId={athlete.id} />}
    </form>
  );
}

function AthleteLogin({ athleteId }: { athleteId: number }) {
  const { t } = useTranslation();
  const { data: users } = useUsers(true);
  const create = useCreateAthleteLogin();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const existing = users?.find((u) => u.role === 'ATHLETE' && u.athleteId === athleteId);

  return (
    <fieldset className="stack">
      <legend>{t('athletes.login')}</legend>
      {existing ? (
        <p>{t('athletes.loginExists', { username: existing.username })}</p>
      ) : (
        <>
          <p className="muted small">{t('athletes.loginHint')}</p>
          <div className="row wrap">
            <input placeholder={t('login.username')} value={username} onChange={(e) => setUsername(e.target.value)} />
            <input
              type="password"
              placeholder={t('login.password')}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button
              type="button"
              disabled={create.isPending || username.length < 3 || password.length < 10}
              onClick={() => create.mutate({ username, password, athleteId })}
            >
              {t('athletes.createLogin')}
            </button>
          </div>
          {create.isError && <p className="error">{t('common.error', { message: create.error.message })}</p>}
        </>
      )}
    </fieldset>
  );
}
