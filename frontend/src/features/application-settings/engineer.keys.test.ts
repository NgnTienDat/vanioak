import { test } from 'node:test'
import assert from 'node:assert/strict'
import { engineerKeys } from './engineer.keys.ts'
test('roster keys isolate exact application, cursor, limit, actor, generation and role', () => {
  const scope = { generation: 1, userId: 'actor', role: 'ADMIN' as const }
  const base = engineerKeys.page(scope, 'app')
  assert.deepEqual(base, ['assigned-engineers', 1, 'actor', 'ADMIN', 'app', 'page', { cursor: null, limit: 50 }])
  for (const key of [engineerKeys.page(scope, 'other-app'), engineerKeys.page(scope, 'app', { cursor: 'next' }), engineerKeys.page(scope, 'app', { limit: 25 }), engineerKeys.page({ ...scope, generation: 2 }, 'app'), engineerKeys.page({ ...scope, userId: 'other' }, 'app'), engineerKeys.page({ ...scope, role: 'ENGINEER' }, 'app')]) assert.notDeepEqual(key, base)
  assert.doesNotMatch(JSON.stringify(base), /password|token|credential/)
})
