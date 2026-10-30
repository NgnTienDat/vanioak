import { test } from 'node:test'
import assert from 'node:assert/strict'
import { analyticsQuery } from './useAnalytics.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { ApiError } from '../../lib/apiError.ts'
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }, params = { application_id: 'app', from: '2026-10-29T09:00:00Z', to: '2026-10-29T10:00:00Z' }
const data = { bucket: 'hour' as const, items: [{ application_id: 'app', environment_id: 'dev', timestamp: params.from, total_logs: 10, error_logs: 2, critical_logs: 1, log_error_rate: 0.3 }] }
test('same Analytics/Overview scoped snapshot reuses Query cache; new generation/filter/revision cannot borrow it', async () => {
  const client = createQueryClient(); let calls = 0
  const api = { health: async () => { calls++; return data } }
  try {
    const first = await client.fetchQuery(analyticsQuery(session, params, 0, api))
    const second = await client.fetchQuery(analyticsQuery(session, params, 0, api))
    assert.equal(calls, 1); assert.equal(second, first)
    assert.equal(client.getQueryData(analyticsQuery({ ...session, generation: 2 }, params).queryKey), undefined)
    assert.equal(client.getQueryData(analyticsQuery(session, { ...params, environment_id: 'test' }).queryKey), undefined)
    assert.equal(client.getQueryData(analyticsQuery(session, params, 1).queryKey), undefined)
  } finally { client.clear() }
})
test('403 redacts cached aggregate instead of showing a previous successful KPI; query does not retry', async () => {
  const client = createQueryClient(); let calls = 0
  const options = analyticsQuery(session, params, 0, { health: async () => { calls++; throw new ApiError(403, 'Access denied.') } })
  try {
    client.setQueryData(options.queryKey, { totals: { total: 10 } }); await client.invalidateQueries({ queryKey: options.queryKey, refetchType: 'none' })
    await assert.rejects(client.fetchQuery(options), ApiError)
    assert.equal(calls, 1); assert.equal(client.getQueryData(options.queryKey), undefined)
  } finally { client.clear() }
})
test('logout cancels uncooperative Analytics work and late data cannot refill a cleared/new-account cache', async () => {
  const client = createQueryClient(), store = createAuthStore(); let finish!: () => void, started!: () => void
  const ready = new Promise<void>(resolve => { started = resolve })
  const unbind = bindSessionCache(client, store)
  const options = analyticsQuery(session, params, 0, { health: async (_params, signal) => {
    assert.ok(signal); started(); await new Promise<void>(resolve => { finish = resolve }); return data
  } })
  try {
    const work = client.fetchQuery(options), rejected = assert.rejects(work)
    await ready; store.getState().clear(); finish(); await rejected
    assert.equal(client.getQueryCache().getAll().length, 0)
  } finally { unbind(); client.clear() }
})
