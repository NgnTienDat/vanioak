import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applicationKeys } from './application.keys.ts'
test('keys separate account, generation, role, filters, cursor, limit and selected application without tokens', () => {
  const scope = { generation: 1,userId: 'a',role: 'ENGINEER' as const }
  const base = applicationKeys.page(scope,{})
  for (const key of [applicationKeys.page({ ...scope,userId: 'b' },{}),applicationKeys.page({ ...scope,generation: 2 },{}),applicationKeys.page({ ...scope,role: 'ADMIN' },{}),applicationKeys.page(scope,{ status: 'DISABLED' }),applicationKeys.page(scope,{ cursor: 'next' }),applicationKeys.page(scope,{ limit: 10 }),applicationKeys.selection(scope,'id')]) assert.notDeepEqual(key,base)
  assert.notDeepEqual(applicationKeys.selection(scope,'a'),applicationKeys.selection(scope,'b'))
  assert.doesNotMatch(JSON.stringify(base),/token|password/)
})
