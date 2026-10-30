import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createAlertRuleApi } from './alert-rule.api.ts'
import { ApiError } from '../lib/apiError.ts'
const rule = { id: '51801983-8baa-4b0a-ba77-8df1f212668b', environment_id: '41801983-8baa-4b0a-ba77-8df1f212668b', level: 'ERROR' as const, threshold: 10, window_seconds: 60, cooldown_seconds: 1, resolve_after_seconds: 300, enabled: true, created_at: '2026-10-30T00:00:00Z', updated_at: '2026-10-30T00:00:00Z' }
const values = { environment_id: rule.environment_id, level: rule.level, threshold: 10, window_seconds: 60, cooldown_seconds: 1, resolve_after_seconds: 300, enabled: true }
test('global rule list uses cursor/limit only and retains real unmatched environments', async () => {
  const api = createAlertRuleApi(axios.create({ adapter: async config => {
    assert.equal(axios.getUri(config), '/alert-rules?limit=50&cursor=opaque%2B%3D')
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [rule], next_cursor: 'next' } } }
  } }))
  assert.deepEqual(await api.list({ cursor: 'opaque+=' }), { items: [rule], next_cursor: 'next' })
})
test('rule writes use threshold (not threshold_count), mutable PATCH only and DELETE disable semantics', async () => {
  const calls: { method?: string; url?: string; body: unknown }[] = []
  const api = createAlertRuleApi(axios.create({ adapter: async config => {
    const body = config.data ? JSON.parse(config.data) : undefined; calls.push({ method: config.method, url: config.url, body })
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { ...rule, ...(body ?? {}), ...(config.method === 'delete' ? { enabled: false } : {}) } } }
  } }))
  await api.create(values); assert.equal((await api.edit(rule, { threshold: 15 })).threshold, 15)
  assert.equal((await api.disable(rule)).enabled, false)
  assert.deepEqual(calls, [{ method: 'post', url: '/alert-rules', body: values }, { method: 'patch', url: `/alert-rules/${rule.id}`, body: { threshold: 15 } }, { method: 'delete', url: `/alert-rules/${rule.id}`, body: undefined }])
})
test('rule API rejects invalid ints, foreign edit reply, malformed pages and DELETE returning enabled', async () => {
  let value: unknown = rule, requests = 0
  const api = createAlertRuleApi(axios.create({ adapter: async config => { requests++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: value } } } }))
  await assert.rejects(api.create({ ...values, cooldown_seconds: 0 }), ApiError); assert.equal(requests, 0)
  value = { ...rule, environment_id: rule.id }; await assert.rejects(api.edit(rule, { enabled: false }), ApiError)
  value = rule; await assert.rejects(api.disable(rule), ApiError)
  for (value of [{ items: [{ ...rule, level: 'WARN' }], next_cursor: null }, { items: [{ ...rule, threshold: 2147483648 }], next_cursor: null }, { items: [rule], next_cursor: '' }, { items: [{ ...rule, enabled: 'true' }], next_cursor: null }]) await assert.rejects(api.list(), ApiError)
})
