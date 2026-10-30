import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createAnalyticsApi } from './analytics.api.ts'
import { ApiError } from '../lib/apiError.ts'
const app = '31801983-8baa-4b0a-ba77-8df1f212668b', env = '41801983-8baa-4b0a-ba77-8df1f212668b'
const params = { application_id: app, environment_id: env, from: '2026-10-29T09:15:00Z', to: '2026-10-29T10:15:00Z' }
const row = { application_id: app, environment_id: env, timestamp: '2026-10-29T09:00:00Z', total_logs: 10, error_logs: 1, critical_logs: 1, log_error_rate: 0.2 }
const envelope = (items: unknown) => ({ success: true, message: 'Success', data: { bucket: 'hour', items } })
function apiFor(data: unknown) { return createAnalyticsApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data }) })) }
test('Analytics GET sends actual scoped parameters/hour bucket and accepts partial edge hour before from', async () => {
  const abort = new AbortController()
  const api = createAnalyticsApi(axios.create({ adapter: async config => {
    assert.equal(config.method, 'get'); assert.equal(config.url, '/analytics/health'); assert.equal(config.signal, abort.signal)
    assert.deepEqual(config.params, { ...params, bucket: 'hour' })
    return { config, status: 200, statusText: 'OK', headers: {}, data: envelope([row]) }
  } }))
  assert.deepEqual(await api.health(params, abort.signal), { bucket: 'hour', items: [row] })
})
test('ALL omits environment and empty data remains a successful empty aggregate, not service health', async () => {
  const api = createAnalyticsApi(axios.create({ adapter: async config => {
    assert.equal(config.params.environment_id, undefined); assert.ok(!axios.getUri(config).includes('environment_id='))
    return { config, status: 200, statusText: 'OK', headers: {}, data: envelope([]) }
  } }))
  assert.deepEqual(await api.health({ ...params, environment_id: undefined }), { bucket: 'hour', items: [] })
})
test('malformed/partial DTO, foreign scope, invalid hour, duplicate bucket and inconsistent ratio fail closed', async () => {
  for (const change of [{ application_id: env }, { environment_id: app }, { timestamp: '2026-10-29T09:30:00Z' }, { timestamp: '2026-10-29T11:00:00Z' },
    { error_logs: -1 }, { critical_logs: undefined }, { total_logs: Number.MAX_SAFE_INTEGER + 1 }, { log_error_rate: 0.5 }, { error_logs: 11 }]) {
    await assert.rejects(apiFor(envelope([{ ...row, ...change }])).health(params), ApiError)
  }
  await assert.rejects(apiFor(envelope([row,row])).health(params), ApiError)
  await assert.rejects(apiFor({ success: false, message: 'SENSITIVE-body', data: null }).health(params), error => error instanceof ApiError && !error.message.includes('SENSITIVE'))
  await assert.rejects(apiFor({ success: true, message: 'Success', data: { bucket: 'day', items: [] } }).health(params), ApiError)
})
test('unsupported range/bucket/fabricated environment is rejected before HTTP, seven days is allowed', async () => {
  let calls = 0
  const api = createAnalyticsApi(axios.create({ adapter: async config => { calls++; return { config, status: 200, statusText: 'OK', headers: {}, data: envelope([]) } } }))
  for (const change of [{ from: params.to }, { from: '2026-10-01T09:15:00Z' }, { bucket: 'day' }, { environment_id: 'ALL' }, { application_id: 'fake' }]) await assert.rejects(api.health({ ...params, ...change } as typeof params), ApiError)
  assert.equal(calls, 0)
  await api.health({ ...params, from: '2026-10-22T10:15:00Z' }); assert.equal(calls, 1)
})
test('cancelled analytics request cannot deliver a late successful body', async () => {
  let finish!: () => void
  const api = createAnalyticsApi(axios.create({ adapter: config => new Promise(resolve => { finish = () => resolve({ config, status: 200, statusText: 'OK', headers: {}, data: envelope([row]) }) }) }))
  const abort = new AbortController(), request = api.health(params, abort.signal)
  abort.abort(); finish(); await assert.rejects(request, error => axios.isCancel(error))
})
