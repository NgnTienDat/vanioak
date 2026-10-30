import { test } from 'node:test'
import assert from 'node:assert/strict'
import { alertRuleSchema, alertRuleEditSchema } from './alertRuleSchema.ts'
const values = { environment_id: '41801983-8baa-4b0a-ba77-8df1f212668b', level: 'CRITICAL', threshold: 1, window_seconds: 1, cooldown_seconds: 1, resolve_after_seconds: 1, enabled: false }
test('rule creation requires actual scope, ERROR/CRITICAL, boolean and positive int32 fields including cooldown', () => {
  assert.equal(alertRuleSchema.safeParse(values).success, true)
  for (const field of ['threshold', 'window_seconds', 'cooldown_seconds', 'resolve_after_seconds']) for (const value of [0, -1, 1.5, 2147483648, NaN, Infinity, '1', null]) assert.equal(alertRuleSchema.safeParse({ ...values, [field]: value }).success, false)
  for (const extra of [{ environment_id: 'ALL' }, { level: 'INFO' }, { enabled: 'true' }, { threshold_count: 1 }]) assert.equal(alertRuleSchema.safeParse({ ...values, ...extra }).success, false)
})
test('edit accepts dirty numeric fields/enabled only; immutable identity/scope/level and empty patches are rejected', () => {
  assert.deepEqual(alertRuleEditSchema.parse({ enabled: false, threshold: 2147483647 }), { threshold: 2147483647, enabled: false })
  for (const value of [{}, { threshold: undefined }, { threshold: null }, { environment_id: values.environment_id }, { level: 'ERROR' }, { id: 'foreign' }, { enabled: null }, { threshold_count: 5 }]) assert.equal(alertRuleEditSchema.safeParse(value).success, false)
})
