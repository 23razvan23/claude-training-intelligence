import { useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAthletes, useLogout, useMe } from '../api/hooks';
import { useAthleteSelection } from '../athleteContext';
import { applyTheme, getTheme, type Theme } from '../theme';

const NEXT_THEME: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' };

export function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { data: me } = useMe();
  const { data: athletes } = useAthletes();
  const { athleteId, setAthleteId } = useAthleteSelection();
  const logout = useLogout();
  const [theme, setTheme] = useState<Theme>(getTheme);

  const cycleTheme = () => {
    const next = NEXT_THEME[theme];
    applyTheme(next);
    setTheme(next);
  };

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">
          <span className="brand-full">{t('app.name')}</span>
          <span className="brand-short">{t('app.short')}</span>
        </span>
        {me?.role === 'COACH' && athletes && athletes.length > 0 && (
          <select
            className="athlete-switcher"
            aria-label={t('common.athlete')}
            value={athleteId ?? ''}
            onChange={(e) => setAthleteId(Number(e.target.value))}
          >
            {athletes.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        )}
        <span className="spacer" />
        <button className="ghost" onClick={cycleTheme} title={t('theme.toggle')}>
          {t(`theme.${theme}`)}
        </button>
        <button className="ghost" onClick={() => logout.mutate()}>
          {t('nav.logout')}
        </button>
      </header>
      <nav className="tabs">
        <NavLink to="/today">{t('nav.today')}</NavLink>
        <NavLink to="/coverage">{t('nav.coverage')}</NavLink>
        <NavLink to="/imports">{t('nav.imports')}</NavLink>
        {me?.role === 'COACH' && <NavLink to="/calibration">{t('nav.calibration')}</NavLink>}
        {me?.role === 'COACH' && <NavLink to="/holdout">{t('nav.holdout')}</NavLink>}
        {me?.role === 'COACH' && <NavLink to="/athletes">{t('nav.athletes')}</NavLink>}
      </nav>
      <main className="content">{children}</main>
    </div>
  );
}
