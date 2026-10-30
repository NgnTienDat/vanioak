import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { applicationAccessApi } from '../../api/application.api.ts'
import { ApiError, ended, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import type { AssignedEngineerPage } from '../applications/application.types.ts'
import { engineerKeys } from './engineer.keys.ts'

export function guardEngineerAccess(scope: Scope, signal: AbortSignal, store: AuthStore = authStore) {
  const state = store.getState()
  if (signal.aborted || state.phase !== 'authenticated' || state.generation !== scope.generation || state.user?.id !== scope.userId) throw ended()
  if (scope.role !== 'ADMIN' || state.user.role !== 'ADMIN') throw new ApiError(403, 'Administrator access is required.')
}
export function assignedEngineerOptions(scope: Scope, id: string, cursor: string | null, api = applicationAccessApi, store = authStore) {
  return { queryKey: engineerKeys.page(scope, id, { cursor }), retry: false as const,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      guardEngineerAccess(scope, signal, store)
      const page = await api.list(id, { cursor, limit: 50 }, signal)
      guardEngineerAccess(scope, signal, store)
      return page
    } }
}
export function assignedEngineerRows(pages: AssignedEngineerPage[], cursors: (string | null)[]) {
  const items = [...new Map(pages.flatMap(page => page.items).map(user => [user.id, user])).values()]
  const next = pages.length === cursors.length ? pages.at(-1)?.next_cursor ?? null : null
  if (next !== null && cursors.includes(next)) throw malformed()
  return { items, next }
}
export function useAssignedEngineers(id: string) {
  const state = useStore(authStore, value => value)
  const scope = sessionScope(state)
  const identity = `${state.generation}:${state.user?.id}:${id}`
  const [chain, setChain] = useState({ identity, cursors: [null] as (string | null)[] })
  const cursors = chain.identity === identity ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({
    ...assignedEngineerOptions(scope ?? { generation: -1, userId: '', role: 'ENGINEER' }, id, cursor),
    enabled: !!scope && scope.role === 'ADMIN',
  })) })
  let error = queries.find(query => query.error)?.error
  let result: ReturnType<typeof assignedEngineerRows> = { items: [], next: null }
  try { result = assignedEngineerRows(queries.flatMap(query => query.data ? [query.data] : []), cursors) } catch (failure) { error = failure as Error }
  const loading = queries.some(query => query.isFetching), initialLoading = queries[0]?.isPending ?? true
  return { items: error instanceof ApiError && error.status === 403 ? [] : result.items,
    initialLoading, loading, error: error ? normalizeApiError(error) : null,
    complete: !error && !initialLoading && queries.every(query => !!query.data) && result.next === null,
    cursor: error ? null : result.next,
    more: () => { if (result.next && !error && !loading) setChain({ identity, cursors: [...cursors, result.next] }) },
    retry: () => { setChain({ identity, cursors: [null] }); void queries[0]?.refetch() },
  }
}
