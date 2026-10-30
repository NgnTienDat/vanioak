import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applicationSchema, applicationEditSchema, applicationStatusSchema } from './applicationSchema.ts'

test('application form follows actual name/optional-description constraints without prototype owner or name regex', () => {
  for (const name of ['', 'a', 'Service with spaces', 'n'.repeat(100)]) assert.equal(applicationSchema.safeParse({ name }).success, true)
  assert.deepEqual(applicationSchema.parse({ name: 'App', description: '' }), { name: 'App', description: '' })
  for (const values of [{}, { name: 'n'.repeat(101) }, { name: null }, { name: 'App', description: null }, { name: 'App', owner: 'admin' }, { name: 'App', status: 'ACTIVE' }]) assert.equal(applicationSchema.safeParse(values).success, false)
})
test('editing sends only supported dirty fields, preserves empty description and rejects null or status mixing', () => {
  for (const values of [{ name: 'Renamed' }, { description: '' }, { name: 'App', description: 'new' }]) assert.deepEqual(applicationEditSchema.parse(values), values)
  for (const values of [{}, { name: undefined }, { name: null }, { description: null }, { status: 'DISABLED' }, { name: 'App', owner: 'admin' }]) assert.equal(applicationEditSchema.safeParse(values).success, false)
})
test('status confirmation accepts only the separate ACTIVE or DISABLED payload', () => {
  for (const status of ['ACTIVE', 'DISABLED']) assert.deepEqual(applicationStatusSchema.parse({ status }), { status })
  for (const values of [{}, { status: null }, { status: 'DELETED' }, { status: 'ACTIVE', name: 'mixed' }]) assert.equal(applicationStatusSchema.safeParse(values).success, false)
})
