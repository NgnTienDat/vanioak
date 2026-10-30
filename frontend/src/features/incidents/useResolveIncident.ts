import { useEffect, useMemo } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { Query, QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { clients } from '../../api/client.ts'
import { incidentApi } from '../../api/incident.api.ts'
import { ApiError, ended, normalizeApiError } from '../../lib/apiError.ts'
import type { Scope } from '../applications/application.keys.ts'
import { incidentKeys } from './incident.keys.ts'
import type { Incident, IncidentDetail, IncidentResolution, IncidentScope } from './incident.types.ts'

export type ResolutionOutcome = { kind: 'resolved'; resolution: IncidentResolution }
  | { kind: 'already-resolved'; incident: IncidentDetail }
  | { kind: 'uncertain'; incident?: IncidentDetail; reconciled: boolean; notice: string }
export function canResolveIncident(incident: Incident, role: string | null, scope: IncidentScope) {
  return incident.status === 'OPEN' && (role === 'ADMIN' || role === 'ENGINEER') && incident.application.id === scope.applicationId
    && (scope.environmentId === null || incident.environment.id === scope.environmentId)
}
export function createIncidentResolution(options: {
  client: QueryClient; session: Scope; scope: IncidentScope; id: string
  store?: AuthStore; api?: Pick<typeof incidentApi, 'detail' | 'resolve'>; renew?: () => Promise<void>
}) {
  const store = options.store ?? authStore, api = options.api ?? incidentApi, renew = options.renew ?? clients.refresh
  const { client, session, scope, id } = options
  let pending: Promise<ResolutionOutcome> | null = null, controller: AbortController | null = null, needsRead = false
  const matches = (query: Query) => ['incidents','logs','analytics','overview'].includes(String(query.queryKey[0]))
    && query.queryKey[1] === session.generation && query.queryKey[2] === session.userId && query.queryKey[3] === session.role && query.queryKey[4] === scope.applicationId
  function guard(signal: AbortSignal) {
    const state = store.getState()
    if (signal.aborted || state.phase !== 'authenticated' || state.generation !== session.generation || state.user?.id !== session.userId) throw ended()
  }
  function denied() { void client.cancelQueries({ predicate: matches }); client.removeQueries({ predicate: matches }) }
  function invalidate() {
    void client.cancelQueries({ predicate: matches })
    void client.invalidateQueries({ predicate: query => matches(query) && (query.queryKey[0] === 'incidents' || query.queryKey[0] === 'overview') })
  }
  async function read(signal: AbortSignal) {
    guard(signal)
    const value = await api.detail(id, scope, signal)
    guard(signal)
    client.setQueryData(incidentKeys.detail(session, scope, id), value)
    return value
  }
  async function reconcile(signal: AbortSignal, notice: string): Promise<ResolutionOutcome> {
    needsRead = true
    try {
      const incident = await read(signal)
      needsRead = false; invalidate()
      return { kind: 'uncertain', incident, reconciled: true, notice: `${notice} Current server state: ${incident.status}.` }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { denied(); throw safe }
      return { kind: 'uncertain', reconciled: false, notice: `${notice} Status could not be verified. Check status before confirming again.` }
    }
  }
  async function run(mode: 'resolve' | 'reconcile', signal: AbortSignal): Promise<ResolutionOutcome> {
    guard(signal)
    if (mode === 'reconcile') return reconcile(signal, 'The previous request outcome remains unconfirmed.')
    if (needsRead) throw new ApiError(409, 'Check the current Incident status before confirming again.')
    let submitted = false
    try {
      await client.cancelQueries({ predicate: matches })
      const before = await read(signal)
      if (before.status === 'RESOLVED') { invalidate(); return { kind: 'already-resolved', incident: before } }
      if (!canResolveIncident(before, session.role, scope)) throw new ApiError(403, 'Resolution is not permitted in this scope.')
      guard(signal); submitted = true
      const resolution = await api.resolve(id, signal)
      guard(signal); needsRead = false; invalidate()
      return { kind: 'resolved', resolution }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { denied(); throw safe }
      if (safe.status === 401) {
        await renew(); guard(signal)
        return reconcile(signal, 'Session renewed. The resolution request was not replayed; review and confirm explicitly if still needed.')
      }
      if (!submitted || safe.status === 400 || safe.status === 404) throw safe
      return reconcile(signal, 'Resolution request outcome is unconfirmed; it may have committed.')
    }
  }
  return {
    execute(mode: 'resolve' | 'reconcile' = 'resolve') {
      if (pending) return pending
      const owned = new AbortController(); controller = owned
      const work = run(mode, owned.signal).finally(() => { if (pending === work) { pending = null; controller = null } })
      pending = work; return work
    },
    cancel() { controller?.abort() },
    get needsReconciliation() { return needsRead },
  }
}
export function useResolveIncident(scope: IncidentScope, id: string) {
  const client = useQueryClient()
  const generation = useStore(authStore, state => state.generation)
  const user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER'
  const { applicationId, environmentId } = scope
  const action = useMemo(() => createIncidentResolution({ client, session: { generation, userId, role }, scope: { applicationId, environmentId }, id }),
    [client, generation, userId, role, applicationId, environmentId, id])
  useEffect(() => () => action.cancel(), [action])
  const mutation = useMutation({ mutationFn: (mode: 'resolve' | 'reconcile') => action.execute(mode), retry: false, gcTime: 0 })
  return { ...mutation, error: mutation.error ? normalizeApiError(mutation.error) : null,
    resolve: () => mutation.mutateAsync('resolve'), reconcile: () => mutation.mutateAsync('reconcile'), needsReconciliation: action.needsReconciliation }
}
