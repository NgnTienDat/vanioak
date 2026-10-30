import { test } from 'node:test'
import assert from 'node:assert/strict'
import { incidentKeys } from './incident.keys.ts'
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }
const params = { application_id: 'app', from: 'from', to: 'to' }
test('Incident keys isolate identity, app/env, filters, bounds, cursor, reconciliation and detail ID', () => {
  const original = incidentKeys.page(session, params)
  for (const change of [{ generation: 2 }, { userId: 'other' }, { role: 'ADMIN' as const }]) assert.notDeepEqual(incidentKeys.page({ ...session, ...change }, params), original)
  for (const change of [{ application_id: 'other' }, { environment_id: 'env' }, { from: 'new' }, { to: 'new' }, { status: 'OPEN' as const }, { level: 'ERROR' as const }, { cursor: 'next' }, { limit: 20 }]) assert.notDeepEqual(incidentKeys.page(session, { ...params, ...change }), original)
  assert.notDeepEqual(incidentKeys.page(session, params, 1), original)
  assert.notDeepEqual(incidentKeys.detail(session, { applicationId: 'app', environmentId: null }, 'id'), incidentKeys.detail(session, { applicationId: 'app', environmentId: null }, 'other'))
  assert.equal(original[4], 'app'); assert.equal(original[5], null); assert.ok(!JSON.stringify(original).includes('token'))
})
