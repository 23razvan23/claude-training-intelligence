import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import type {
  Athlete,
  AthleteInput,
  CoverageReport,
  FeatureSummary,
  HoldoutFeed,
  HoldoutReport,
  HoldoutVersion,
  ImportBatch,
  Me,
  ModelVersion,
  RecomputeResult,
  Today,
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
