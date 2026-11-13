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
  startedAt?: string;
  finishedAt?: string;
  progress?: { phase: 'wellness' | 'activities' | 'fit' | 'whoop'; done: number; total?: number };
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

export interface FeatureSummary {
  featureVersion: string;
  days: number;
  withGarminCore: number;
  withWhoop: number;
  paired: number;
  from?: string;
  to?: string;
  pairedFrom?: string;
  pairedTo?: string;
  withoutBaseline: number;
  withoutLoad: number;
  activityLoadMethods: Record<string, number>;
}

export interface RecomputeResult {
  load: { computed: number; fromStream: number; fromAverage: number; withoutLoad: number; hrRest?: number; sexAssumed: boolean };
  features: { days: number; withGarminCore: number; withWhoop: number; paired: number; from?: string; to?: string };
}

interface ScoreMetrics {
  n: number;
  mae?: number;
  spearman?: number;
  bias?: number;
}

export interface ModelVersion {
  id: number;
  athleteId: number;
  version: string;
  status: 'CANDIDATE' | 'ACTIVE' | 'FROZEN' | 'RETIRED';
  featureVersion: string;
  trainFrom?: string;
  trainTo?: string;
  testFrom?: string;
  testTo?: string;
  passed: boolean;
  createdAt: string;
  metrics: {
    strain?: ScoreMetrics;
    recovery?: ScoreMetrics & { agreement?: number; green_red_rate?: number };
    targets?: { strain_mae_max: number; recovery_band_agreement_min: number; recovery_green_red_max: number };
    checks?: { strain_mae: boolean; strain_spearman?: boolean; recovery_band_agreement: boolean; recovery_green_red: boolean };
  };
}
