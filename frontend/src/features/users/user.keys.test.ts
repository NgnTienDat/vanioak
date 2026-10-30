import { test } from 'node:test'
import assert from 'node:assert/strict'
import { userKeys } from './user.keys.ts'
test('directory keys include exact supported filters and isolate account, generation and role', () => {
  const scope = { generation: 1, userId: 'actor', role: 'ADMIN' as const }
  const base = userKeys.page(scope)
  assert.deepEqual(base, ['users', 1, 'actor', 'ADMIN', 'page', { status: null, cursor: null, limit: 50 }])
  for (const key of [userKeys.page({ ...scope, userId: 'other' }), userKeys.page({ ...scope, generation: 2 }), userKeys.page({ ...scope, role: 'ENGINEER' }), userKeys.page(scope, { status: 'ACTIVE' }), userKeys.page(scope, { status: 'DISABLED' }), userKeys.page(scope, { cursor: 'next' }), userKeys.page(scope, { limit: 25 })]) assert.notDeepEqual(key, base)
  assert.doesNotMatch(JSON.stringify(base), /password|token|credential/)
})


test('user detail keys isolate target IDs and reuse the session directory root', () => {
  const scope = { generation: 2, userId: 'actor', role: 'ADMIN' as const }
  assert.deepEqual(userKeys.detail(scope, 'target'), ['users', 2, 'actor', 'ADMIN', 'detail', 'target'])
  assert.notDeepEqual(userKeys.detail(scope, 'target'), userKeys.detail(scope, 'other'))
  assert.notDeepEqual(userKeys.detail(scope, 'target'), userKeys.page(scope))
})
