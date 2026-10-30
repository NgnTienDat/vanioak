import { test } from 'node:test'
import assert from 'node:assert/strict'
import { apiKeySchema, apiKeyFormSchema } from './apiKeySchema.ts'
test('expiration is optional or a valid future UTC instant, never local time/null/past/invalid date', () => {
  const schema = apiKeySchema(Date.parse('2026-10-30T00:00:00Z'))
  assert.deepEqual(schema.parse({}), {})
  assert.deepEqual(schema.parse({ expires_at: '2026-11-01T00:00:00Z' }), { expires_at: '2026-11-01T00:00:00Z' })
  for (const value of [null, '', '2026-10-29T00:00:00Z', '2026-10-30T00:00:00Z', '2099-01-01T00:00:00', '2099-02-30T00:00:00Z', '2099-01-01T00:00:00+07:00']) assert.equal(schema.safeParse({ expires_at: value }).success, false)
  assert.equal(schema.safeParse({ label: 'invented' }).success, false)
})
test('blank form expiration remains explicit optional UI state, valid future input passes and malformed input fails', () => {
  assert.equal(apiKeyFormSchema.safeParse({ expires_at: '' }).success, true)
  assert.equal(apiKeyFormSchema.safeParse({ expires_at: '2099-01-01T00:00:00Z' }).success, true)
  assert.equal(apiKeyFormSchema.safeParse({ expires_at: 'tomorrow' }).success, false)
})
