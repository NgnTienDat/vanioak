import { test } from 'node:test'
import assert from 'node:assert/strict'
import { apiKeyRows, apiKeyOptions } from './useApiKeys.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { ApiKey } from './apiKey.types.ts'
import type { apiKeyApi } from '../../api/api-key.api.ts'
const key: ApiKey = { id: 'key', environment_id: 'env', key_prefix: '0123456789abcdef', status: 'ACTIVE', created_at: '2026-10-30T00:00:00Z', expires_at: null, revoked_at: null }
const scope = { applicationId: 'app', environmentId: 'env' }
test('metadata pagination dedups stable IDs, waits for pending page, rejects cursor loops and resets to fresh head', () => {
  const head = { items: [key], next_cursor: 'next' }
  assert.equal(apiKeyRows([head], [null]).next, 'next')
  assert.equal(apiKeyRows([head], [null, 'next']).next, null)
  assert.deepEqual(apiKeyRows([head, { items: [key], next_cursor: null }], [null, 'next']).items, [key])
  assert.throws(() => apiKeyRows([head, head], [null, 'next']), ApiError)
  assert.deepEqual(apiKeyRows([{ items: [], next_cursor: null }], [null]), { items: [], next: null })
})
test('metadata query uses exact environment and cursor; cancelled or retired sessions cannot refill data', async () => {
  for (const retire of ['cancel', 'logout'] as const) {
    const store = createAuthStore(), client = createQueryClient(); let deliver!: () => void
    await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'actor', role: 'ADMIN' } }))
    const session = { generation: store.getState().generation, userId: 'actor', role: 'ADMIN' as const }
    const api: typeof apiKeyApi = { list: async (selected, params) => { assert.deepEqual(selected, scope); assert.deepEqual(params, { cursor: 'cursor', limit: 50 }); await new Promise<void>(resolve => { deliver = resolve }); return { items: [key], next_cursor: null } }, create: async () => key, rotate: async () => key, revoke: async () => key }
    const options = apiKeyOptions(session, scope, 'cursor', api, store)
    try {
      const pending = client.fetchQuery(options), rejected = assert.rejects(pending)
      if (retire === 'cancel') await client.cancelQueries({ queryKey: options.queryKey }); else store.getState().clear()
      deliver(); await rejected; assert.equal(client.getQueryData(options.queryKey), undefined)
    } finally { client.clear() }
  }
})
test('Engineer role is denied before API key metadata requests', async () => {
  const store = createAuthStore(), client = createQueryClient(); let calls = 0
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'actor', role: 'ENGINEER' } }))
  const api: typeof apiKeyApi = { list: async () => { calls++; return { items: [], next_cursor: null } }, create: async () => key, rotate: async () => key, revoke: async () => key }
  try { await assert.rejects(client.fetchQuery(apiKeyOptions({ generation: store.getState().generation, userId: 'actor', role: 'ENGINEER' }, scope, null, api, store)), error => error instanceof ApiError && error.status === 403); assert.equal(calls, 0) }
  finally { client.clear() }
})
