import { useQuery } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { incidentApi } from '../../api/incident.api.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import type { IncidentDetail, IncidentScope } from './incident.types.ts'
import { incidentKeys } from './incident.keys.ts'
import { malformed, normalizeApiError } from '../../lib/apiError.ts'

export function incidentDetailQuery(session: Scope, scope: IncidentScope, id: string, api: Pick<typeof incidentApi, 'detail'> = incidentApi) {
  return { queryKey: incidentKeys.detail(session, scope, id),
    queryFn: ({ signal }: { signal: AbortSignal }) => api.detail(id, scope, signal) }
}
export function resolutionAudit(incident: IncidentDetail) {
  const history = incident.events.find(event => event.event_type === 'RESOLVED')?.metadata
  const source = history?.resolution_source === 'MANUAL' || history?.resolution_source === 'AUTOMATIC' ? history.resolution_source : null
  const actor = source === 'MANUAL' && typeof history?.resolved_by_user_id === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(history.resolved_by_user_id) ? history.resolved_by_user_id : null
  return { source, actor }
}
export function useIncident(scope: IncidentScope, id: string) {
  const phase = useStore(authStore, state => state.phase)
  useStore(authStore, state => state.generation)
  const session = sessionScope(authStore.getState())
  const query = useQuery<IncidentDetail>({ ...(session ? incidentDetailQuery(session, scope, id) : { queryKey: ['incidents','inactive'], queryFn: () => Promise.reject(malformed()) }),
    enabled: phase === 'authenticated' && !!session })
  return { ...query, data: query.error ? undefined : query.data, error: query.error ? normalizeApiError(query.error) : null }
}
