import { test } from 'node:test'
import assert from 'node:assert/strict'
import { alertRuleKeys } from './alertRule.keys.ts'
test('rule keys are global pages under an isolated session, without fabricated app/env server filters', () => {
  const scope = { generation: 1, userId: 'actor', role: 'ADMIN' as const }, base = alertRuleKeys.page(scope)
  assert.deepEqual(base, ['alert-rules', 1, 'actor', 'ADMIN', 'page', { cursor: null, limit: 50 }])
  for (const key of [alertRuleKeys.page({ ...scope, generation: 2 }), alertRuleKeys.page({ ...scope, userId: 'other' }), alertRuleKeys.page({ ...scope, role: 'ENGINEER' }), alertRuleKeys.page(scope, { cursor: 'next' }), alertRuleKeys.page(scope, { limit: 5 })]) assert.notDeepEqual(key, base)
})
