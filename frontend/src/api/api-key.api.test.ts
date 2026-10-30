import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createApiKeyApi } from './api-key.api.ts'
import { ApiError } from '../lib/apiError.ts'
const scope = { applicationId: '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId: '41801983-8baa-4b0a-ba77-8df1f212668b' }
const key = { id: '51801983-8baa-4b0a-ba77-8df1f212668b', environment_id: scope.environmentId, key_prefix: '0123456789abcdef', status: 'ACTIVE' as const, expires_at: null, created_at: '2026-10-30T00:00:00Z', revoked_at: null }
const sentinel = key.key_prefix + '.' + 'S'.repeat(43)
test('API key list sends only scoped cursor/limit and projects metadata without credential extras', async () => {
  const api = createApiKeyApi(axios.create({ adapter: async config => {
    assert.equal(axios.getUri(config), `/applications/${scope.applicationId}/environments/${scope.environmentId}/api-keys?limit=50&cursor=opaque%2B%3D`)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [{ ...key, api_key: sentinel }], next_cursor: 'next' } } }
  } }))
  const data = await api.list(scope, { cursor: 'opaque+=' })
  assert.deepEqual(data, { items: [key], next_cursor: 'next' }); assert.ok(!JSON.stringify(data).includes(sentinel))
})
test('create/rotate consume secret directly and return only metadata; revoke returns actual retained record', async () => {
  const calls: { method?: string; url?: string; body: unknown }[] = []; let received = 0
  const api = createApiKeyApi(axios.create({ adapter: async config => {
    calls.push({ method: config.method, url: config.url, body: config.data ? JSON.parse(config.data) : undefined })
    const metadata = config.url?.endsWith('/rotate') ? { ...key, id: '61801983-8baa-4b0a-ba77-8df1f212668b' } : config.method === 'delete' ? { ...key, status: 'REVOKED', revoked_at: key.created_at } : key
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: config.method === 'delete' ? metadata : { credential: metadata, api_key: sentinel } } }
  } }))
  const consume = (value: string) => { assert.ok(value === sentinel); received++ }
  const created = await api.create(scope, {}, consume), rotated = await api.rotate(scope, key, consume)
  assert.ok(!JSON.stringify([created, rotated]).includes(sentinel)); assert.equal(received, 2)
  assert.equal((await api.revoke(scope, key.id)).status, 'REVOKED')
  assert.deepEqual(calls, [{ method: 'post', url: `/applications/${scope.applicationId}/environments/${scope.environmentId}/api-keys`, body: {} }, { method: 'post', url: `/api-keys/${key.id}/rotate`, body: undefined }, { method: 'delete', url: `/api-keys/${key.id}`, body: undefined }])
})
test('API key DTO/foreign scope/malformed secret rejection and transport errors never expose plaintext', async () => {
  let value: unknown = { credential: key, api_key: sentinel }, reveals = 0
  const api = createApiKeyApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: value } }) }))
  for (value of [{ credential: { ...key, environment_id: key.id }, api_key: sentinel }, { credential: { ...key, status: 'REVOKED' }, api_key: sentinel }, { credential: key, api_key: 'invalid' }]) {
    await assert.rejects(api.create(scope, {}, () => { reveals++ }), error => error instanceof ApiError && error.status === 502 && !JSON.stringify(error).includes(sentinel))
  }
  value = { items: [{ ...key, key_prefix: sentinel }], next_cursor: null }; await assert.rejects(api.list(scope), ApiError)
  value = { items: [{ ...key, created_at: 'invalid' }], next_cursor: null }; await assert.rejects(api.list(scope), ApiError)
  const broken = createApiKeyApi(axios.create({ adapter: async () => { throw new Error(sentinel) } }))
  await assert.rejects(broken.create(scope, {}, () => { reveals++ }), error => error instanceof ApiError && !JSON.stringify(error).includes(sentinel) && !('response' in error) && !('cause' in error))
  assert.equal(reveals, 0)
  await assert.rejects(api.list({ ...scope, environmentId: 'ALL' }), ApiError)
  await assert.rejects(api.create(scope, { expires_at: '2020-01-01T00:00:00Z' }, () => {}), ApiError)
})

test('rotation preserves a non-null expiry and rejects changed expiry or reused credential ID before reveal', async () => {
  const previous = { ...key, expires_at: '2099-01-01T00:00:00Z' }; let metadata = { ...previous, id: '61801983-8baa-4b0a-ba77-8df1f212668b' }, received = 0
  const api = createApiKeyApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { credential: metadata, api_key: sentinel } } }) }))
  const consume = () => { received++ }
  assert.equal((await api.rotate(scope, previous, consume)).expires_at, previous.expires_at)
  metadata = { ...metadata, expires_at: '2099-02-01T00:00:00Z' }; await assert.rejects(api.rotate(scope, previous, consume), ApiError)
  metadata = previous; await assert.rejects(api.rotate(scope, previous, consume), ApiError)
  assert.equal(received, 1)
})
