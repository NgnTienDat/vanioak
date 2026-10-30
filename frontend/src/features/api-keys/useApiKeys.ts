import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { apiKeyApi } from '../../api/api-key.api.ts'
import { malformed, normalizeApiError } from '../../lib/apiError.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import { apiKeyKeys } from './apiKey.keys.ts'
import type { ApiKeyPage, ApiKeyScope } from './apiKey.types.ts'
export function apiKeyOptions(session: Scope, scope: ApiKeyScope, cursor: string | null, api = apiKeyApi, store = authStore) {
  return { queryKey: apiKeyKeys.page(session, scope, { cursor }), retry: false as const,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      guardEngineerAccess(session, signal, store)
      const page = await api.list(scope, { cursor, limit: 50 }, signal)
      guardEngineerAccess(session, signal, store)
      return page
    } }
}
export function apiKeyRows(pages: ApiKeyPage[], cursors: (string | null)[]) {
  const next = pages.length === cursors.length ? pages.at(-1)?.next_cursor ?? null : null
  if (next !== null && cursors.includes(next)) throw malformed()
  return { items: [...new Map(pages.flatMap(page => page.items).map(key => [key.id, key])).values()], next }
}
export function useApiKeys(scope: ApiKeyScope) {
  const state = useStore(authStore, value => value), session = sessionScope(state)
  const identity = `${state.generation}:${state.user?.id}:${scope.applicationId}:${scope.environmentId}`
  const [chain, setChain] = useState({ identity, cursors: [null] as (string | null)[] })
  const cursors = chain.identity === identity ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({ ...apiKeyOptions(session ?? { generation: -1, userId: '', role: 'ENGINEER' }, scope, cursor), enabled: session?.role === 'ADMIN' })) })
  let error = queries.find(query => query.error)?.error
  let result: ReturnType<typeof apiKeyRows> = { items: [], next: null }
  try { result = apiKeyRows(queries.flatMap(query => query.data ? [query.data] : []), cursors) } catch (failure) { error = failure as Error }
  const loading = queries.some(query => query.isFetching)
  return { items: error ? [] : result.items, loading, initialLoading: queries[0]?.isPending ?? true, error: error ? normalizeApiError(error) : null,
    cursor: error ? null : result.next, more: () => { if (result.next && !error && !loading) setChain({ identity, cursors: [...cursors, result.next] }) },
    retry: () => { setChain({ identity, cursors: [null] }); void queries[0]?.refetch() } }
}
