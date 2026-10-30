import { useEffect, useState } from 'react'
import { useQueries, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { incidentApi } from '../../api/incident.api.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { incidentKeys } from './incident.keys.ts'
import type { IncidentParams, IncidentPage } from './incident.types.ts'

export function incidentPageQuery(session: Scope, params: IncidentParams, revision = 0, api: Pick<typeof incidentApi, 'list'> = incidentApi) {
  return { queryKey: incidentKeys.page(session, params, revision), gcTime: 0,
    queryFn: ({ signal }: { signal: AbortSignal }) => api.list(params, signal) }
}
export function loadedIncidents(pages: IncidentPage[], cursors: (string | null)[]) {
  const items = [...new Map(pages.flatMap(page => page.items).map(row => [row.id, row])).values()]
  const next = pages.at(-1)?.next_cursor ?? null
  const repeated = next !== null && cursors.includes(next)
  return { items, next: repeated ? null : next, error: repeated ? malformed() : null }
}
export function useIncidents(params: IncidentParams, revision: number) {
  const generation = useStore(authStore, state => state.generation)
  const phase = useStore(authStore, state => state.phase)
  const session = sessionScope(authStore.getState()), client = useQueryClient()
  const identity = JSON.stringify(session ? [...incidentKeys.list(session, params), 'reconcile', revision] : ['incidents','inactive',generation])
  const [chain, setChain] = useState<{ identity: string; cursors: (string | null)[] }>({ identity, cursors: [null] })
  const cursors = chain.identity === identity ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({
    ...(session ? incidentPageQuery(session, { ...params, cursor }, revision) : { queryKey: ['incidents','inactive'], queryFn: () => Promise.reject(malformed()) }),
    enabled: phase === 'authenticated' && !!session,
  })) })
  useEffect(() => () => { void client.cancelQueries({ predicate: query => JSON.stringify(query.queryKey.slice(0,-2)) === identity }) }, [client, identity])
  const loaded = loadedIncidents(queries.map(query => query.data).filter((page): page is IncidentPage => !!page), cursors)
  const error = queries.find(query => query.error)?.error ?? loaded.error
  const denied = error instanceof ApiError && (error.status === 403 || error.status === 401)
  return { items: denied ? [] : loaded.items, initialLoading: queries[0]?.isPending ?? true, loading: queries.some(query => query.isFetching), error: error ? normalizeApiError(error) : null,
    next: error ? null : loaded.next,
    more: () => { if (loaded.next && !error && !queries.some(query => query.isFetching)) setChain({ identity, cursors: [...cursors, loaded.next] }) },
    retry: () => { void (queries.find(query => query.error) ?? queries.at(-1))?.refetch() },
  }
}
