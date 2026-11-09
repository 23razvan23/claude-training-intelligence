export type Role = 'COACH' | 'ATHLETE';

export interface Me {
  id: number;
  username: string;
  role: Role;
  athleteId?: number;
}

export interface Zone {
  zone: number;
  min: number;
  max: number;
}

export interface Athlete {
  id: number;
  name: string;
  sex?: 'M' | 'F';
  birthYear?: number;
  hrMax?: number;
  restingHr?: number;
  lthr?: number;
  thresholdPaceSPerKm?: number;
  hrZones?: Zone[];
  paceZones?: unknown;
  timezone: string;
  injuryNotes?: string;
}

export type AthleteInput = Omit<Athlete, 'id'>;

export interface UserView {
  id: number;
  username: string;
  role: Role;
  athleteId?: number;
}

export type ImportKind = 'WHOOP_EXPORT' | 'GARMIN_EXPORT' | 'GARMIN_SYNC' | 'FIT_UPLOAD';
export type ImportStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface ImportBatch {
  id: number;
  athleteId: number;
  kind: ImportKind;
  filename?: string;
  status: ImportStatus;
  stats: Record<string, { inserted: number; duplicates: number; skipped: number }>;
  warnings: string[];
  error?: string;
  createdAt: string;
  finishedAt?: string;
}

export interface CoverageDay {
  date: string;
  garminRestingHr: boolean;
  garminHrv: boolean;
  garminSleep: boolean;
  garminActivities: number;
  whoopRecovery: boolean;
  whoopStrain: boolean;
  whoopSleep: boolean;
  garminComplete: boolean;
  whoopComplete: boolean;
  paired: boolean;
}

export interface CoverageReport {
  from?: string;
  to?: string;
  totalDays: number;
  garminCompleteDays: number;
  whoopCompleteDays: number;
  pairedDays: number;
  fieldDays: Record<string, number>;
  pairedGaps: { from: string; to: string; days: number }[];
  days: CoverageDay[];
}
