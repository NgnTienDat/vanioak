import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { userApi } from '../../api/user.api.ts'
import { malformed, normalizeApiError } from '../../lib/apiError.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import type { Status } from '../../types/api.types.ts'
import type { Engineer, UserPage } from './user.types.ts'
import { userKeys } from './user.keys.ts'

export function userOptions(scope: Scope, status: Status | undefined, cursor: string | null, api = userApi, store = authStore) {
  return { queryKey: userKeys.page(scope, { status, cursor }), retry: false as const,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      guardEngineerAccess(scope, signal, store)
      const page = await api.list({ status, cursor, limit: 50 }, signal)
      guardEngineerAccess(scope, signal, store)
      return page
    } }
}
export function userRows(pages: UserPage[], cursors: (string | null)[]) {
  const next = pages.length === cursors.length ? pages.at(-1)?.next_cursor ?? null : null
  if (next !== null && cursors.includes(next)) throw malformed()
  return { items: [...new Map(pages.flatMap(page => page.items).map(user => [user.id, user])).values()], next }
}
export function searchUsers(items: Engineer[], search: string) {
  return items.filter(user => user.username.toLowerCase().includes(search.toLowerCase()))
}
export function userDetailOptions(scope: Scope, id: string, api = userApi, store = authStore) {
  return { queryKey: userKeys.detail(scope, id), retry: false as const, gcTime: 0, staleTime: 0, refetchOnMount: 'always' as const,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      guardEngineerAccess(scope, signal, store)
      const user = await api.get(id, signal)
      guardEngineerAccess(scope, signal, store)
      return user
    } }
}
export function useUser(id: string) {
  const state = useStore(authStore, value => value), scope = sessionScope(state)
  return useQuery({ ...userDetailOptions(scope ?? { generation: -1, userId: '', role: 'ENGINEER' }, id), enabled: scope?.role === 'ADMIN' })
}
export function useUsers(status?: Status) {
  const state = useStore(authStore, value => value), scope = sessionScope(state)
  const identity = `${state.generation}:${state.user?.id}:${status}`
  const [chain, setChain] = useState({ identity, cursors: [null] as (string | null)[] })
  const cursors = chain.identity === identity ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({
    ...userOptions(scope ?? { generation: -1, userId: '', role: 'ENGINEER' }, status, cursor), enabled: scope?.role === 'ADMIN',
  })) })
  let error = queries.find(query => query.error)?.error
  let result: ReturnType<typeof userRows> = { items: [], next: null }
  try { result = userRows(queries.flatMap(query => query.data ? [query.data] : []), cursors) } catch (failure) { error = failure as Error }
  const loading = queries.some(query => query.isFetching)
  return { items: error ? [] : result.items, loading, initialLoading: queries[0]?.isPending ?? true,
    error: error ? normalizeApiError(error) : null, cursor: error ? null : result.next,
    more: () => { if (result.next && !error && !loading) setChain({ identity, cursors: [...cursors, result.next] }) },
    retry: () => { setChain({ identity, cursors: [null] }); void queries[0]?.refetch() },
  }
}
