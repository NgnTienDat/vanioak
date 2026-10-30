import { useState } from 'react'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import { applicationApi } from '../../api/application.api.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { applicationKeys, sessionScope } from './application.keys.ts'
import type { Scope } from './application.keys.ts'
import type { ApplicationPage, ApplicationParams } from './application.types.ts'
import type { Status } from '../../types/api.types.ts'

export async function resolveApplication(client: QueryClient, scope: Scope, id: string, signal: AbortSignal, api = applicationApi) {
  const seen = new Set<string>()
  let cursor: string | null = null
  do {
    if (signal.aborted) throw new ApiError(499, 'Request cancelled.')
    const params: ApplicationParams = { cursor, limit: 50 }
    // Shared pages belong to Query; retiring one selection must not cancel another reader.
    const page = await client.fetchQuery({ queryKey: applicationKeys.page(scope, params),
      queryFn: ({ signal: pageSignal }) => api.list(params, pageSignal) })
    if (signal.aborted) throw new ApiError(499, 'Request cancelled.')
    const found = page.items.find(app => app.id === id)
    if (found) return found
    cursor = page.next_cursor
    if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
  } while (cursor !== null)
  throw new ApiError(403, 'This application is unavailable or is not assigned to your account.')
}

export function useApplications(status?: Status) {
  const phase = useStore(authStore, s => s.phase)
  const generation = useStore(authStore, s => s.generation)
  const scope = sessionScope(authStore.getState())
  const [chain, setChain] = useState<{ filter: Status | undefined; generation: number; cursors: (string | null)[] }>({ filter: status, generation, cursors: [null] })
  const cursors = chain.filter === status && chain.generation === generation ? chain.cursors : [null]
  const queries = useQueries({ queries: cursors.map(cursor => ({
    queryKey: scope ? applicationKeys.page(scope, { status, cursor }) : ['applications','inactive'],
    enabled: phase === 'authenticated' && !!scope,
    queryFn: ({ signal }: { signal: AbortSignal }) => applicationApi.list({ status, cursor }, signal),
  })) })
  const failure = queries.find(q => q.error)?.error
  const pages = queries.map(q => q.data).filter((p): p is ApplicationPage => !!p)
  const denied = failure instanceof ApiError && failure.status === 403
  const items = denied ? [] : [...new Map(pages.flatMap(p => p.items).map(app => [app.id, app])).values()]
  const last = queries.at(-1)
  const next = last?.data?.next_cursor ?? null
  const repeated = next !== null && cursors.includes(next)
  const error = repeated ? malformed() : failure
  return { items, loading: queries.some(q => q.isFetching), error: error ? normalizeApiError(error) : null,
    cursor: error ? null : next,
    more: () => { if (next && !repeated && !queries.some(q => q.isFetching)) setChain({ filter: status, generation, cursors: [...cursors, next] }) },
    retry: () => { setChain({ filter: status, generation, cursors: [null] }); void queries[0]?.refetch() },
  }
}
export function useApplication(id: string) {
  const user = useStore(authStore, s => s.user)
  const phase = useStore(authStore, s => s.phase)
  const generation = useStore(authStore, s => s.generation)
  const client = useQueryClient()
  const scope = user ? { generation, userId: user.id, role: user.role } : null
  const query = useQuery({ queryKey: scope ? applicationKeys.selection(scope,id) : ['applications','inactive'],
    enabled: phase === 'authenticated' && !!scope,
    queryFn: ({ signal }) => resolveApplication(client,scope!,id,signal),
  })
  return { ...query, error: query.error ? normalizeApiError(query.error) : null, data: query.error ? undefined : query.data }
}
