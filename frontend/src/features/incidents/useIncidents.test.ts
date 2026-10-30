import { test } from 'node:test'
import assert from 'node:assert/strict'
import { QueryObserver } from '@tanstack/react-query'
import { createQueryClient } from '../../lib/queryClient.ts'
import { incidentPageQuery, loadedIncidents } from './useIncidents.ts'
import type { Incident } from './incident.types.ts'
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }
const params = { application_id: 'app', environment_id: 'env', from: '2026-10-28T09:00:00Z', to: '2026-10-29T09:00:00Z' }
const item: Incident = { id: 'id', application: { id: 'app', name: 'App' }, environment: { id: 'env', name: 'DEV' }, status: 'OPEN', level: 'ERROR', error_count: 2, message: 'message', fingerprint: 'fp', first_seen_at: params.from, last_seen_at: params.from }
test('Incident pages repeat frozen bounds/cursor, deduplicate moving rows and refuse cursor loops', async () => {
  const client = createQueryClient(); const calls: unknown[] = []
  const api = { list: async (query: typeof params & { cursor?: string | null }) => {
    calls.push(query); return { items: [item], next_cursor: query.cursor ? null : 'next' }
  } }
  try {
    const first = await client.fetchQuery(incidentPageQuery(session, params, 0, api))
    const second = await client.fetchQuery(incidentPageQuery(session, { ...params, cursor: first.next_cursor }, 0, api))
    assert.deepEqual(calls, [params, { ...params, cursor: 'next' }])
    assert.equal(loadedIncidents([first, second], [null,'next']).items.length, 1)
    assert.ok(loadedIncidents([{ items: [], next_cursor: 'next' }], [null,'next']).error)
    assert.notDeepEqual(incidentPageQuery(session, params, 1, api).queryKey, incidentPageQuery(session, params, 0, api).queryKey)
  } finally { client.clear() }
})
test('obsolete Incident query cancellation prevents late refill after scope/filter reset', async () => {
  const client = createQueryClient(); let finish!: () => void, started!: () => void, aborted: AbortSignal | undefined
  const ready = new Promise<void>(resolve => { started = resolve })
  const query = incidentPageQuery(session, params, 0, { list: async (_params, signal) => { aborted = signal; started(); await new Promise<void>(resolve => { finish = resolve }); return { items: [item], next_cursor: null } } })
  const result = client.fetchQuery(query); const rejected = assert.rejects(result)
  await ready; await client.cancelQueries({ queryKey: query.queryKey }); finish(); await rejected
  assert.equal(aborted?.aborted, true); assert.equal(client.getQueryData(query.queryKey), undefined); client.clear()
})

test('Incident initial pending differs from successful empty, empty refetch and failed reads', async () => {
  const client = createQueryClient()
  let finish!: (page: { items: Incident[]; next_cursor: null }) => void
  let fail!: (error: Error) => void
  const api = { list: () => new Promise<{ items: Incident[]; next_cursor: null }>((resolve, reject) => { finish = resolve; fail = reject }) }
  const query = incidentPageQuery(session, params, 0, api)
  const observer = new QueryObserver(client, query)
  const unsubscribe = observer.subscribe(() => {})
  try {
    assert.equal(observer.getCurrentResult().isPending, true)
    const initial = client.fetchQuery(query)
    finish({ items: [], next_cursor: null }); await initial
    assert.equal(observer.getCurrentResult().isPending, false)
    assert.deepEqual(observer.getCurrentResult().data?.items, [])

    const refetch = observer.refetch()
    assert.equal(observer.getCurrentResult().isFetching, true)
    assert.equal(observer.getCurrentResult().isPending, false)
    finish({ items: [], next_cursor: null }); await refetch

    const failed = observer.refetch()
    fail(new Error('Unavailable')); await failed
    assert.equal(observer.getCurrentResult().isError, true)
    assert.equal(observer.getCurrentResult().isPending, false)

    observer.setOptions(incidentPageQuery(session, { ...params, status: 'OPEN' }, 0, api))
    assert.equal(observer.getCurrentResult().isPending, true)
    const failedInitial = observer.refetch()
    fail(new Error('Unavailable')); await failedInitial
    assert.equal(observer.getCurrentResult().isError, true)
    assert.equal(observer.getCurrentResult().data, undefined)
  } finally { unsubscribe(); client.clear() }
})
