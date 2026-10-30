import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createIncidentApi } from './incident.api.ts'
import { ApiError } from '../lib/apiError.ts'
const app = '31801983-8baa-4b0a-ba77-8df1f212668b', env = '41801983-8baa-4b0a-ba77-8df1f212668b', id = '51801983-8baa-4b0a-ba77-8df1f212668b'
const at = '2026-10-29T09:00:00.000Z'
const item = { id, application: { id: app, name: 'App' }, environment: { id: env, name: 'DEV' }, level: 'ERROR', status: 'OPEN', fingerprint: 'fingerprint', message: '<b>server text</b>', error_count: 3, first_seen_at: at, last_seen_at: at, resolved_at: null }
const envelope = (data: unknown) => ({ success: true, message: 'Success', data })
function apiFor(body: unknown) {
  return createIncidentApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data: body }) }))
}
test('list maps exact REST filters and cursor; ALL/optional bounds omitted without text search or totals', async () => {
  const params = { application_id: app, environment_id: env, status: 'OPEN' as const, level: 'ERROR' as const, from: '2026-10-01T00:00:00Z', to: '2026-10-30T00:00:00Z', limit: 50, cursor: 'opaque+=' }
  const api = createIncidentApi(axios.create({ adapter: async config => {
    assert.equal(config.method, 'get'); assert.equal(config.url, '/incidents'); assert.deepEqual(config.params, params)
    return { config, status: 200, statusText: 'OK', headers: {}, data: envelope({ items: [item], next_cursor: null }) }
  } }))
  assert.deepEqual(await api.list(params), { items: [item], next_cursor: null })
  assert.equal((await apiFor(envelope({ items: [item], next_cursor: null })).list({ application_id: app })).items.length, 1)
})
test('list rejects malformed DTOs, foreign scope, unsupported filters, unsafe counts and last_seen outside half-open range', async () => {
  for (const change of [{ id: 'fake' }, { error_count: -1 }, { error_count: Number.MAX_SAFE_INTEGER + 1 }, { status: 'IN_PROGRESS' },
    { environment: { id: app, name: 'TEST' } }, { application: { id: env, name: 'Other' } }, { last_seen_at: 'bad' }]) {
    await assert.rejects(apiFor(envelope({ items: [{ ...item, ...change }], next_cursor: null })).list({ application_id: app, environment_id: env }), ApiError)
  }
  await assert.rejects(apiFor(envelope({ items: [item], next_cursor: null })).list({ application_id: app, to: at }), ApiError)
  await assert.rejects(apiFor({ success: false, message: 'sensitive-server-text', data: null }).list({ application_id: app }), error => error instanceof ApiError && !error.message.includes('sensitive'))
  let calls = 0
  const api = createIncidentApi(axios.create({ adapter: async () => { calls++; throw new Error('Unexpected request') } }))
  for (const params of [{ application_id: 'ALL' }, { application_id: app, level: 'INFO' }, { application_id: app, status: 'IN_PROGRESS' }, { application_id: app, limit: 101 }]) {
    await assert.rejects(api.list(params as { application_id: string }), ApiError)
  }
  assert.equal(calls, 0)
})
test('detail decodes newest-first history/null metadata, enforces app/env and newest100 without a fake history endpoint', async () => {
  const event = { id, event_type: 'OPENED', event_at: at, error_count: null, message: null, metadata: null }
  const value = { ...item, events: [event] }
  assert.deepEqual(await apiFor(envelope(value)).detail(id, { applicationId: app, environmentId: env }), value)
  for (const scope of [{ applicationId: env, environmentId: null }, { applicationId: app, environmentId: app }]) {
    await assert.rejects(apiFor(envelope(value)).detail(id, scope), error => error instanceof ApiError && error.status === 403)
  }
  for (const events of [Array(101).fill(event), [{ ...event, event_at: '2026-10-28T09:00:00Z' }, event]]) {
    await assert.rejects(apiFor(envelope({ ...item, events })).detail(id, { applicationId: app, environmentId: null }), ApiError)
  }
})
test('PATCH sends only RESOLVED and accepts exact winning manual/automatic/legacy nullable audit', async () => {
  for (const audit of [{ resolution_source: 'MANUAL', resolved_by_user_id: app, resolved_at: at },
    { resolution_source: 'AUTOMATIC', resolved_by_user_id: null, resolved_at: at }, { resolution_source: null, resolved_by_user_id: null, resolved_at: null }]) {
    const value = { id, status: 'RESOLVED', ...audit }; let calls = 0
    const api = createIncidentApi(axios.create({ adapter: async config => {
      calls++; assert.equal(config.method, 'patch'); assert.equal(config.url, `/incidents/${id}/status`)
      assert.deepEqual(JSON.parse(config.data), { status: 'RESOLVED' })
      return { config, status: 200, statusText: 'OK', headers: {}, data: envelope(value) }
    } }))
    assert.deepEqual(await api.resolve(id), value); assert.equal(calls, 1)
  }
})
test('malformed committed response or mismatched audit cannot fabricate successful resolution', async () => {
  for (const change of [{ id: app }, { resolution_source: 'MANUAL', resolved_by_user_id: null }, { resolution_source: 'AUTOMATIC', resolved_by_user_id: app }, { status: 'OPEN' }]) {
    await assert.rejects(apiFor(envelope({ id, status: 'RESOLVED', resolved_at: at, resolution_source: null, resolved_by_user_id: null, ...change })).resolve(id), ApiError)
  }
})
test('GET cancellation prevents late detail delivery even if adapter ignores abort', async () => {
  let deliver!: () => void
  const api = createIncidentApi(axios.create({ adapter: config => new Promise(resolve => { deliver = () => resolve({ config, status: 200, statusText: 'OK', headers: {}, data: envelope({ ...item, events: [] }) }) }) }))
  const abort = new AbortController(); const result = api.detail(id, { applicationId: app, environmentId: null }, abort.signal)
  abort.abort(); deliver(); await assert.rejects(result, error => axios.isCancel(error))
})
