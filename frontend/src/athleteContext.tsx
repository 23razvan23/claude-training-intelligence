import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAthletes, useMe } from './api/hooks';

interface Ctx {
  athleteId?: number;
  setAthleteId: (id: number) => void;
}

const AthleteContext = createContext<Ctx>({ setAthleteId: () => {} });

function readStored(): number | undefined {
  try {
    const v = localStorage.getItem('athleteId');
    return v ? Number(v) : undefined;
  } catch {
    return undefined;
  }
}

/** The athlete the coach is looking at. Athletes are pinned to themselves. */
export function AthleteProvider({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const { data: athletes } = useAthletes();
  const [selected, setSelected] = useState<number | undefined>(readStored);

  useEffect(() => {
    if (!athletes || athletes.length === 0) return;
    if (selected == null || !athletes.some((a) => a.id === selected)) {
      setSelected(me?.athleteId ?? athletes[0].id);
    }
  }, [athletes, me, selected]);

  const setAthleteId = (id: number) => {
    setSelected(id);
    try {
      localStorage.setItem('athleteId', String(id));
    } catch {
      /* storage unavailable */
    }
  };

  const athleteId = me?.role === 'ATHLETE' ? me.athleteId : selected;
  return <AthleteContext.Provider value={{ athleteId, setAthleteId }}>{children}</AthleteContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export const useAthleteSelection = () => useContext(AthleteContext);
