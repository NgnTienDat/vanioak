import { test } from 'node:test'
import assert from 'node:assert/strict'
import { analyticsKeys } from './analytics.keys.ts'
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }, params = { application_id: 'app', from: 'from', to: 'to' }
test('Analytics keys isolate every authorization/window dimension and normalize ALL/hour', () => {
  const key = analyticsKeys.health(session, params)
  for (const change of [{ generation: 2 }, { userId: 'other' }, { role: 'ADMIN' as const }]) assert.notDeepEqual(analyticsKeys.health({ ...session, ...change }, params), key)
  for (const change of [{ application_id: 'other' }, { environment_id: 'env' }, { from: 'new' }, { to: 'new' }]) assert.notDeepEqual(analyticsKeys.health(session, { ...params, ...change }), key)
  assert.notDeepEqual(analyticsKeys.health(session, params, 1), key)
  assert.deepEqual(analyticsKeys.health(session, { ...params, bucket: 'hour' }), key)
  assert.equal(key[5], null); assert.ok(!JSON.stringify(key).includes('token'))
})
