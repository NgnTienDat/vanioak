import { test } from 'node:test'
import assert from 'node:assert/strict'
import { apiKeyKeys } from './apiKey.keys.ts'
test('API key metadata keys separate session/app/env/cursor/limit and contain no plaintext fields', () => {
  const session = { generation: 1, userId: 'actor', role: 'ADMIN' as const }, scope = { applicationId: 'app', environmentId: 'env' }, base = apiKeyKeys.page(session, scope)
  assert.deepEqual(base, ['api-keys', 1, 'actor', 'ADMIN', 'app', 'env', 'page', { cursor: null, limit: 50 }])
  for (const key of [apiKeyKeys.page({ ...session, generation: 2 }, scope), apiKeyKeys.page({ ...session, userId: 'other' }, scope), apiKeyKeys.page({ ...session, role: 'ENGINEER' }, scope), apiKeyKeys.page(session, { ...scope, applicationId: 'other' }), apiKeyKeys.page(session, { ...scope, environmentId: 'other' }), apiKeyKeys.page(session, scope, { cursor: 'next' }), apiKeyKeys.page(session, scope, { limit: 5 })]) assert.notDeepEqual(key, base)
  assert.doesNotMatch(JSON.stringify(base), /api_key|credential|password|token/)
})
