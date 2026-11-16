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
  scores: ScoringOutcome;
}

/** What a daily-scoring run did (backend ScoringService.Outcome); `skipped` says why nothing was scored. */
export interface ScoringOutcome {
  modelVersion?: string;
  featureVersion?: string;
  days: number;
  withRecovery: number;
  withStrain: number;
  withSleepPerformance: number;
  lowerConfidence: number;
  withDrift: number;
  stale: number;
  profileChanged: boolean;
  from?: string;
  to?: string;
  lastDataDate?: string;
  skipped?: string;
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
  /** Absent if never activated, or if it was already ACTIVE before activation times were recorded. */
  activatedAt?: string;
  retiredAt?: string;
  /** Why the daily scoring could not use this version; absent when it can. */
  contractIssue?: 'FEATURE_VERSION' | 'SCHEMA_VERSION';
  /** The ACTIVE version was created after this one, so this one can no longer be activated. */
  olderThanActive: boolean;
  metrics: {
    strain?: ScoreMetrics;
    recovery?: ScoreMetrics & { agreement?: number; green_red_rate?: number };
    targets?: { strain_mae_max: number; recovery_band_agreement_min: number; recovery_green_red_max: number };
    checks?: { strain_mae: boolean; strain_spearman?: boolean; recovery_band_agreement: boolean; recovery_green_red: boolean };
  };
}

// Live comparison with Whoop (stage 1b-3, ADR 0009 / ADR 0013). Rates are 0–1.
export interface Rate {
  k: number;
  n: number;
  value?: number;
  low?: number;
  high?: number;
}

export interface Stat {
  n: number;
  value?: number;
}

export interface RecoveryFigures {
  n: number;
  bandAgreement: Rate;
  greenRed: Rate;
  mae: Stat;
  spearman: Stat;
  lowerConfidence: number;
  confusion: Record<string, Record<string, number>>;
}

export interface StrainFigures {
  n: number;
  mae: Stat;
  spearman: Stat;
}

export type HoldoutScore = 'recovery' | 'strain' | 'sleepPerformance' | 'sleepNeed';

export interface HoldoutFigures {
  from: string;
  to: string;
  days: number;
  recovery: RecoveryFigures;
  strain: StrainFigures;
  strainLowCoverage: StrainFigures;
  sleepPerformanceMae: Stat;
  sleepNeedMae: Stat;
  groups: Record<HoldoutScore, Record<string, number>>;
}

export interface HoldoutSide {
  bandAgreement: Rate;
  greenRed: Rate;
  recoveryMae: Stat;
}

export interface HoldoutMark {
  key: string;
  comparison: '>=' | '<=';
  target: number;
  value?: number;
  n: number;
  rate?: Rate;
  met?: boolean;
}

export type HoldoutState = 'NOT_ENOUGH_DAYS_YET' | 'INTERIM' | 'FINAL' | 'NOT_ENOUGH_DAYS_BY_RULE';

export interface HoldoutDay {
  date: string;
  recoveryGroup: string;
  ourRecovery?: number;
  ourBand?: string;
  whoopRecovery?: number;
  whoopBand?: string;
  lowerConfidence: boolean;
  strainGroup: string;
  ourStrain?: number;
  whoopStrain?: number;
  lowCoverage: boolean;
  sleepGroup: string;
  ourSleepPerf?: number;
  whoopSleepPerf?: number;
  naiveRecovery?: number;
  naiveBand?: string;
}

export interface HoldoutReport {
  modelVersion: string;
  status: string;
  activatedAt?: string;
  retiredAt?: string;
  protocol: string;
  source: string;
  marks: Record<string, unknown>;
  windowFrom: string;
  windowTo?: string;
  whoopEnd: string;
  lastScoredDate?: string;
  phase: 'RUNNING' | 'ENDED' | 'RETIRED';
  verdict: { state: HoldoutState; overall?: 'PASS' | 'NOT_MET'; recoveryDays: number; minRecoveryDays: number; marks: HoldoutMark[] };
  cumulative: HoldoutFigures;
  rolling?: HoldoutFigures;
  weeks: HoldoutFigures[];
  naive: { n: number; skippedNoWhoopYesterday: number; ours: HoldoutSide; naive: HoldoutSide };
  markers: { profileChangedDays: number; profile?: Record<string, number>; recomputeRuns: string[]; staleFeatureDays: number };
  days: HoldoutDay[];
}

export interface HoldoutVersion {
  version: string;
  status: string;
  activatedAt?: string;
  retiredAt?: string;
  windowFrom?: string;
}

export interface HoldoutFeed {
  lastWhoopDay?: string;
  lastGarminDay?: string;
  lastScoredDay?: string;
  lastWhoopImport?: string;
  lastGarminImport?: string;
  lastRecompute?: string;
  recomputeNeeded: boolean;
  whoopEnd: string;
}

// Today screen (stage 1b-2a, ADR 0014): GET /api/athletes/{id}/today
export type TodayState = 'FRESH' | 'MISSING' | 'STALE' | 'NO_DATA' | 'NO_MODEL';
export type Band = 'GREEN' | 'YELLOW' | 'RED';

export interface TodayReason {
  reason: string;
  detail?: string | null;
}

export interface TodayContribution {
  feature: string;
  value?: number | null;
  points?: number | null;
  measured?: number | null;
  baseline?: number | null;
  percentVsBaseline?: number | null;
  typical?: number | null;
}

export interface StrainTarget {
  low: number;
  high: number;
  band: Band;
  basisDays: number;
  windowDays: number;
  lowPercentile: number;
  highPercentile: number;
}

export interface TodayDay {
  date: string;
  recoveryPct?: number | null;
  band?: Band | null;
  recoveryLowerConfidence: boolean;
  recoveryReasons: TodayReason[];
  strain?: number | null;
  strainLowCoverage: boolean;
  strainReason?: string | null;
  strainTarget?: StrainTarget | null;
  sleepPerformancePct?: number | null;
  sleepNeedMin?: number | null;
  sleepReason?: string | null;
  contributions: TodayContribution[];
  needsRecompute: boolean;
  drift: boolean;
  profileChanged: boolean;
}

export interface Today {
  today: string;
  timezone: string;
  state: TodayState;
  modelVersion?: string | null;
  lastDataDate?: string | null;
  /** Only ever today's row (date === today). */
  day?: TodayDay | null;
  /** The newest scored day before today, only when stale; always shown with its date. */
  lastScored?: TodayDay | null;
}

// Trends screen (stage 1b-2b, ADR 0015): GET /api/athletes/{id}/trends. Every value is null when not recorded.
export interface TrendsDay {
  date: string;
  rmssd?: number | null;
  rhr?: number | null;
  sleepMinutes?: number | null;
  /** Edwards daily load: 0 is a recorded rest day, null is unknown. */
  dayLoadEdwards?: number | null;
  recoveryPct?: number | null;
  band?: Band | null;
  strain?: number | null;
  strainLowCoverage: boolean;
  sleepPerformancePct?: number | null;
  sleepNeedMin?: number | null;
}

export interface Trends {
  modelVersion?: string | null;
  featureVersion?: string | null;
  /** First day asked for (the load warm-up included) and the last (today in the athlete's timezone). */
  from: string;
  to: string;
  /** First day with overnight HRV anywhere in the athlete's data. */
  firstHrvDate?: string | null;
  historyDays: number;
  days: TrendsDay[];
}
