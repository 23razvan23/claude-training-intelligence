import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import type {
  Athlete,
  Checkin,
  CheckinInput,
  CheckinOptions,
  AthleteInput,
  CoverageReport,
  FeatureSummary,
  GarminCheckReport,
  GarminConnectResult,
  GarminSyncRun,
  GarminSyncStatus,
  GarminStatus,
  HoldoutFeed,
  HoldoutReport,
  HoldoutVersion,
  ImportBatch,
  Me,
  ModelVersion,
  Plan,
  PlanImported,
  PlanImportPreview,
  PlanInput,
  PlannedSession,
  SessionInput,
  SessionRevision,
  SessionTypeInfo,
  DecisionOutcome,
  EngineRun,
  SessionChange,
  Suggestion,
  SuggestionsToday,
  RecomputeResult,
  Today,
  Trends,
  StoredSignals,
  ToleranceView,
  UserView,
} from './types';

export function useMe() {
  return useQuery<Me | null>({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        return await api<Me>('/api/auth/me');
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: Infinity,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; password: string }) =>
      api<Me>('/api/auth/login', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: (me) => qc.setQueryData(['me'], me),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>('/api/auth/logout', { method: 'POST' }),
    onSettled: () => {
      qc.clear();
      qc.setQueryData(['me'], null);
    },
  });
}

export function useAthletes() {
  return useQuery({ queryKey: ['athletes'], queryFn: () => api<Athlete[]>('/api/athletes') });
}

export function useSaveAthlete() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: AthleteInput }) =>
      id
        ? api<Athlete>(`/api/athletes/${id}`, { method: 'PUT', body: JSON.stringify(input) })
        : api<Athlete>('/api/athletes', { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['athletes'] }),
  });
}

export function useDeleteAthlete() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/athletes/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['athletes'] }),
  });
}

export function useUsers(enabled: boolean) {
  return useQuery({ queryKey: ['users'], queryFn: () => api<UserView[]>('/api/users'), enabled });
}

export function useCreateAthleteLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; password: string; athleteId: number }) =>
      api<UserView>('/api/users', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  });
}

export function useImports(athleteId?: number) {
  return useQuery({
    queryKey: ['imports', athleteId],
    queryFn: () => api<ImportBatch[]>(`/api/athletes/${athleteId}/imports`),
    enabled: athleteId != null,
    // Poll while anything is still in flight.
    refetchInterval: (q) =>
      q.state.data?.some((b) => b.status === 'PENDING' || b.status === 'RUNNING') ? 1500 : false,
  });
}

export function useUpload(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, files }: { kind: 'whoop' | 'garmin' | 'fit'; files: File[] }) => {
      const form = new FormData();
      const field = kind === 'garmin' ? 'file' : 'files';
      files.forEach((f) => form.append(field, f));
      return api<ImportBatch>(`/api/athletes/${athleteId}/imports/${kind}`, { method: 'POST', body: form });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['imports', athleteId] });
      qc.invalidateQueries({ queryKey: ['coverage', athleteId] });
    },
  });
}

export function useCoverage(athleteId?: number) {
  return useQuery({
    queryKey: ['coverage', athleteId],
    queryFn: () => api<CoverageReport>(`/api/athletes/${athleteId}/coverage`),
    enabled: athleteId != null,
  });
}

export function useFeatureSummary(athleteId?: number) {
  return useQuery({
    queryKey: ['features', athleteId],
    queryFn: () => api<FeatureSummary>(`/api/athletes/${athleteId}/features/summary`),
    enabled: athleteId != null,
  });
}

export function useRecomputeFeatures(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<RecomputeResult>(`/api/athletes/${athleteId}/features/recompute`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['features', athleteId] });
      qc.invalidateQueries({ queryKey: ['holdout', athleteId] });
      qc.invalidateQueries({ queryKey: ['today', athleteId] });
      qc.invalidateQueries({ queryKey: ['trends', athleteId] });
    },
  });
}

export function useModels(athleteId?: number) {
  return useQuery({
    queryKey: ['models', athleteId],
    queryFn: () => api<ModelVersion[]>(`/api/athletes/${athleteId}/models`),
    enabled: athleteId != null,
  });
}

export function useModelReport(modelId?: number) {
  return useQuery({
    queryKey: ['model-report', modelId],
    queryFn: async () => {
      const res = await fetch(`/api/models/${modelId}/report`, { credentials: 'same-origin' });
      if (!res.ok) throw new ApiError(res.status, res.statusText);
      return res.text();
    },
    enabled: modelId != null,
    staleTime: Infinity,
  });
}

export function useActivateModel(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (modelId: number) => api<ModelVersion>(`/api/models/${modelId}/activate`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models', athleteId] });
      qc.invalidateQueries({ queryKey: ['holdout', athleteId] });
      qc.invalidateQueries({ queryKey: ['holdout-versions', athleteId] });
    },
  });
}

/** The live comparison with Whoop for the ACTIVE version, or for {@code version} (coach only). */
export function useHoldout(athleteId?: number, version?: string) {
  const q = version ? `?version=${encodeURIComponent(version)}` : '';
  return useQuery({
    queryKey: ['holdout', athleteId, version ?? null],
    queryFn: () => api<HoldoutReport>(`/api/athletes/${athleteId}/holdout${q}`),
    enabled: athleteId != null,
    retry: false,
  });
}

export function useHoldoutVersions(athleteId?: number) {
  return useQuery({
    queryKey: ['holdout-versions', athleteId],
    queryFn: () => api<HoldoutVersion[]>(`/api/athletes/${athleteId}/holdout/versions`),
    enabled: athleteId != null,
  });
}

/** What has arrived (last Whoop and Garmin days) and whether Recompute features is needed. */
export function useHoldoutFeed(athleteId?: number) {
  return useQuery({
    queryKey: ['holdout', athleteId, 'feed'],
    queryFn: () => api<HoldoutFeed>(`/api/athletes/${athleteId}/holdout/feed`),
    enabled: athleteId != null,
  });
}

export function useToday(athleteId?: number) {
  return useQuery({
    queryKey: ['today', athleteId],
    queryFn: () => api<Today>(`/api/athletes/${athleteId}/today`),
    enabled: athleteId != null,
    // The morning check: refresh when the phone comes back to the app.
    refetchOnWindowFocus: true,
  });
}

export function useTrends(athleteId?: number) {
  return useQuery({
    queryKey: ['trends', athleteId],
    queryFn: () => api<Trends>(`/api/athletes/${athleteId}/trends`),
    enabled: athleteId != null,
  });
}

/** Stored engine signals between two days (stage 2-2, ADR 0021); at most 400 days per request. */
export function useSignals(athleteId: number | undefined, from: string | undefined, to: string | undefined) {
  return useQuery({
    queryKey: ['signals', athleteId, from, to],
    queryFn: () => api<StoredSignals[]>(`/api/athletes/${athleteId}/signals?from=${from}&to=${to}`),
    enabled: athleteId != null && !!from && !!to,
  });
}

/** Running tolerance with its derivation and the override history (ADR 0021 D11). */
export function useTolerance(athleteId?: number) {
  return useQuery({
    queryKey: ['tolerance', athleteId],
    queryFn: () => api<ToleranceView>(`/api/athletes/${athleteId}/tolerance`),
    enabled: athleteId != null,
  });
}

/** Coach: set the tolerance override (km per week), or clear it with null. */
export function useSetToleranceOverride(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { kmPerWeek: number | null; note?: string }) =>
      api<ToleranceView>(`/api/athletes/${athleteId}/tolerance/override`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: (view) => {
      qc.setQueryData(['tolerance', athleteId], view);
      qc.invalidateQueries({ queryKey: ['signals', athleteId] });
    },
  });
}

/** Garmin connection status (coach only, ADR 0011). */
export function useGarminStatus(athleteId?: number) {
  return useQuery({
    queryKey: ['garmin', athleteId],
    queryFn: () => api<GarminStatus>(`/api/athletes/${athleteId}/garmin`),
    enabled: athleteId != null,
  });
}

/** Password and code go only in the request body (ADR 0011 D9). */
export function useGarminConnect(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; password: string } | { mfaId: string; code: string }) =>
      api<GarminConnectResult>(`/api/athletes/${athleteId}/garmin/${'mfaId' in body ? 'mfa' : 'connect'}`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (r) => {
      if (r.status === 'CONNECTED') qc.invalidateQueries({ queryKey: ['garmin', athleteId] });
    },
  });
}

export function useGarminDisconnect(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>(`/api/athletes/${athleteId}/garmin`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['garmin', athleteId] }),
  });
}

/** The latest Garmin sync runs; polls while one is running. */
export function useGarminRuns(athleteId?: number, enabled = true) {
  const qc = useQueryClient();
  return useQuery({
    queryKey: ['garmin-runs', athleteId],
    queryFn: async () => {
      const runs = await api<GarminSyncRun[]>(`/api/athletes/${athleteId}/garmin/sync/runs`);
      // A run that just finished may have changed scores, imports and the connection state.
      if (runs[0]?.status === 'SUCCEEDED' || runs[0]?.status === 'FAILED') {
        const prev = qc.getQueryData<GarminSyncRun[]>(['garmin-runs', athleteId]);
        if (prev?.[0]?.id === runs[0].id && prev[0].status === 'RUNNING') {
          for (const key of ['today', 'imports', 'holdout', 'features', 'garmin', 'coverage', 'garmin-sync-status']) {
            qc.invalidateQueries({ queryKey: [key, athleteId] });
          }
        }
      }
      return runs;
    },
    enabled: athleteId != null && enabled,
    refetchInterval: (q) => (q.state.data?.[0]?.status === 'RUNNING' ? 2000 : false),
  });
}

export function useGarminSyncNow(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<GarminSyncRun>(`/api/athletes/${athleteId}/garmin/sync`, { method: 'POST' }),
    onSuccess: (run) => {
      qc.setQueryData<GarminSyncRun[]>(['garmin-runs', athleteId], (old) => [run, ...(old ?? []).filter((r) => r.id !== run.id)]);
      qc.invalidateQueries({ queryKey: ['imports', athleteId] });
      qc.invalidateQueries({ queryKey: ['garmin-sync-status', athleteId] });
    },
  });
}

/** The "Last sync" line (Today and Imports); readable by an athlete for their own data. */
export function useGarminSyncStatus(athleteId?: number) {
  return useQuery({
    queryKey: ['garmin-sync-status', athleteId],
    queryFn: () => api<GarminSyncStatus>(`/api/athletes/${athleteId}/garmin/sync/status`),
    enabled: athleteId != null,
    refetchOnWindowFocus: true,
    retry: false,
  });
}

export function useGarminCheck(athleteId?: number) {
  return useMutation({
    mutationFn: (range: { from: string; to: string }) =>
      api<GarminCheckReport>(`/api/athletes/${athleteId}/garmin/check`, { method: 'POST', body: JSON.stringify(range) }),
  });
}

// ---- Plan (stage 2-1a, ADR 0019) ----

export function useSessionTypes() {
  return useQuery({ queryKey: ['sessionTypes'], queryFn: () => api<SessionTypeInfo[]>('/api/plan/session-types'), staleTime: Infinity });
}

export function usePlans(athleteId?: number) {
  return useQuery({
    queryKey: ['plans', athleteId],
    queryFn: () => api<Plan[]>(`/api/athletes/${athleteId}/plans`),
    enabled: athleteId != null,
  });
}

export function useSessions(athleteId: number | undefined, from: string, to: string) {
  return useQuery({
    queryKey: ['sessions', athleteId, from, to],
    queryFn: () => api<PlannedSession[]>(`/api/athletes/${athleteId}/sessions?from=${from}&to=${to}`),
    enabled: athleteId != null,
  });
}

export function useSessionRevisions(sessionId?: number) {
  return useQuery({
    queryKey: ['sessionRevisions', sessionId],
    queryFn: () => api<SessionRevision[]>(`/api/sessions/${sessionId}/revisions`),
    enabled: sessionId != null,
  });
}

function invalidatePlan(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: ['plans'] });
  void qc.invalidateQueries({ queryKey: ['sessions'] });
  void qc.invalidateQueries({ queryKey: ['sessionRevisions'] });
  void qc.invalidateQueries({ queryKey: ['suggestions'] });
}

/** Today's pending suggestions and health messages (stage 2-3, ADR 0022). */
export function useSuggestionsToday(athleteId?: number) {
  return useQuery({
    queryKey: ['suggestions', 'today', athleteId],
    queryFn: () => api<SuggestionsToday>(`/api/athletes/${athleteId}/suggestions/today`),
    enabled: athleteId != null,
    refetchOnWindowFocus: true,
  });
}

/** Pending suggestions about the days from {@code from} (default today) for two weeks. */
export function usePendingSuggestions(athleteId: number | undefined, from: string, to: string) {
  return useQuery({
    queryKey: ['suggestions', 'pending', athleteId, from, to],
    queryFn: () => api<Suggestion[]>(`/api/athletes/${athleteId}/suggestions?status=PENDING&from=${from}&to=${to}`),
    enabled: athleteId != null,
  });
}

export function useSessionSuggestions(sessionId?: number) {
  return useQuery({
    queryKey: ['suggestions', 'session', sessionId],
    queryFn: () => api<Suggestion[]>(`/api/sessions/${sessionId}/suggestions`),
    enabled: sessionId != null,
  });
}

/** The coach's "Check the plan now": fresh signals, then the engine. */
export function useRunSuggestions(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<EngineRun>(`/api/athletes/${athleteId}/suggestions/run`, { method: 'POST' }),
    onSuccess: () => invalidatePlan(qc),
  });
}

export type DecisionInput =
  | { id: number; kind: 'accept'; note?: string; after?: { session: SessionChange; counterpart?: SessionChange } }
  | { id: number; kind: 'reject'; note?: string };

export function useDecideSuggestion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: DecisionInput) =>
      api<DecisionOutcome>(`/api/suggestions/${d.id}/${d.kind}`, {
        method: 'POST',
        body: JSON.stringify(d.kind === 'accept' ? { note: d.note, after: d.after } : { note: d.note }),
      }),
    onSuccess: () => invalidatePlan(qc),
  });
}

export function useSavePlan(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: PlanInput }) =>
      id
        ? api<Plan>(`/api/plans/${id}`, { method: 'PUT', body: JSON.stringify(input) })
        : api<Plan>(`/api/athletes/${athleteId}/plans`, { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => invalidatePlan(qc),
  });
}

export function useSaveSession(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: SessionInput }) =>
      id
        ? api<PlannedSession>(`/api/sessions/${id}`, { method: 'PUT', body: JSON.stringify(input) })
        : api<PlannedSession>(`/api/athletes/${athleteId}/sessions`, { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => invalidatePlan(qc),
  });
}

function fileForm(file: File) {
  const form = new FormData();
  form.append('file', file);
  return form;
}

/** Step 1 of a plan import: read the file and say what would happen. Saves nothing. */
export function usePlanImportPreview(athleteId?: number) {
  return useMutation({
    mutationFn: (file: File) =>
      api<PlanImportPreview>(`/api/athletes/${athleteId}/plan-import/preview`, { method: 'POST', body: fileForm(file) }),
  });
}

/** Step 2: the same file again; saves every row or none. */
export function usePlanImportCommit(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (file: File) =>
      api<PlanImported>(`/api/athletes/${athleteId}/plan-import/commit`, { method: 'POST', body: fileForm(file) }),
    onSuccess: () => invalidatePlan(qc),
  });
}

export function useDeleteSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api<void>(`/api/sessions/${id}`, { method: 'DELETE' }),
    onSuccess: () => invalidatePlan(qc),
  });
}

export function useCheckinOptions(athleteId?: number, date?: string) {
  return useQuery({
    queryKey: ['checkin-options', athleteId, date],
    queryFn: () => api<CheckinOptions>(`/api/athletes/${athleteId}/checkins/options${date ? `?date=${date}` : ''}`),
    enabled: athleteId != null,
  });
}

export function useRecentCheckins(athleteId?: number) {
  return useQuery({
    queryKey: ['checkins', athleteId],
    queryFn: () => api<Checkin[]>(`/api/athletes/${athleteId}/checkins/recent`),
    enabled: athleteId != null,
  });
}

/** The coach's overview: every athlete's current check-ins. */
export function useAllRecentCheckins(enabled: boolean) {
  return useQuery({ queryKey: ['checkins-all'], queryFn: () => api<Checkin[]>('/api/checkins/recent'), enabled });
}

export function useCreateCheckin(athleteId?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: CheckinInput) =>
      api<Checkin>(`/api/athletes/${athleteId}/checkins`, { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['checkin-options'] });
      qc.invalidateQueries({ queryKey: ['checkins'] });
      qc.invalidateQueries({ queryKey: ['checkins-all'] });
    },
  });
}
