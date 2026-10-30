import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { alertRuleApi } from '../../api/alert-rule.api.ts'
import { malformed, normalizeApiError } from '../../lib/apiError.ts'
import { sessionScope } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import { alertRuleKeys } from './alertRule.keys.ts'
import type { AlertRulePage } from './alertRule.types.ts'
export function alertRuleOptions(session: Scope, cursor: string | null, api = alertRuleApi, store = authStore) {
  return { queryKey: alertRuleKeys.page(session, { cursor }), retry: false as const,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      guardEngineerAccess(session, signal, store)
      const page = await api.list({ cursor, limit: 50 }, signal)
      guardEngineerAccess(session, signal, store)
      return page
    } }
}
export function alertRuleRows(pages: AlertRulePage[], cursors: (string | null)[], environmentIds: string[], selectedEnvironment: string | null) {
  const next = pages.length === cursors.length ? pages.at(-1)?.next_cursor ?? null : null
  if (next !== null && cursors.includes(next)) throw malformed()
  const items = [...new Map(pages.flatMap(page => page.items).map(rule => [rule.id, rule])).values()]
    .filter(rule => environmentIds.includes(rule.environment_id) && (selectedEnvironment === null || selectedEnvironment === rule.environment_id))
  return { items, next }
}
export function useAlertRules(environmentIds: string[], selectedEnvironment: string | null) {
  const state = useStore(authStore, value => value), session = sessionScope(state)
  const identity = `${state.generation}:${state.user?.id}`
  const [chain, setChain] = useState({ identity, cursors: [null] as (string | null)[] })
  const cursors = chain.identity === identity ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({ ...alertRuleOptions(session ?? { generation: -1, userId: '', role: 'ENGINEER' }, cursor), enabled: session?.role === 'ADMIN' })) })
  let error = queries.find(query => query.error)?.error
  let result: ReturnType<typeof alertRuleRows> = { items: [], next: null }
  try { result = alertRuleRows(queries.flatMap(query => query.data ? [query.data] : []), cursors, environmentIds, selectedEnvironment) } catch (failure) { error = failure as Error }
  const loading = queries.some(query => query.isFetching), initialLoading = queries[0]?.isPending ?? true
  return { items: error ? [] : result.items, loading, initialLoading, error: error ? normalizeApiError(error) : null,
    complete: !error && !initialLoading && queries.every(query => !!query.data) && result.next === null,
    cursor: error ? null : result.next, more: () => { if (result.next && !error && !loading) setChain({ identity, cursors: [...cursors, result.next] }) },
    retry: () => { setChain({ identity, cursors: [null] }); void queries[0]?.refetch() } }
}
