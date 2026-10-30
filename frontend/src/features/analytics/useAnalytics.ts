import { useQuery } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { analyticsApi } from '../../api/analytics.api.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { analyticsKeys } from './analytics.keys.ts'
import { analyticsMetrics } from './analyticsMetrics.ts'
import type { AnalyticsParams, AnalyticsMetrics } from './analytics.types.ts'
import { malformed, normalizeApiError } from '../../lib/apiError.ts'

export function analyticsQuery(session: Scope, params: AnalyticsParams, revision = 0, api = analyticsApi) {
  return { queryKey: analyticsKeys.health(session, params, revision), staleTime: 10000, gcTime: 30000,
    queryFn: async ({ signal }: { signal: AbortSignal }) => analyticsMetrics(await api.health(params, signal), params) }
}
export function useAnalytics(params: AnalyticsParams, revision = 0) {
  const phase = useStore(authStore, state => state.phase)
  useStore(authStore, state => state.generation)
  const session = sessionScope(authStore.getState())
  const query = useQuery<AnalyticsMetrics>({ ...(session ? analyticsQuery(session, params, revision) : { queryKey: ['analytics','inactive'], queryFn: () => Promise.reject(malformed()) }), enabled: phase === 'authenticated' && !!session })
  return { ...query, data: query.error ? undefined : query.data, error: query.error ? normalizeApiError(query.error) : null }
}
