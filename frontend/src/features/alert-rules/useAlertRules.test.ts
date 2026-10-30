import { test } from 'node:test'
import assert from 'node:assert/strict'
import { alertRuleRows, alertRuleOptions } from './useAlertRules.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { AlertRule } from './alertRule.types.ts'
import type { alertRuleApi } from '../../api/alert-rule.api.ts'
const rule: AlertRule = { id: 'rule', environment_id: 'own-env', level: 'ERROR', threshold: 10, window_seconds: 60, cooldown_seconds: 1, resolve_after_seconds: 300, enabled: true, created_at: '2026-10-30T00:00:00Z', updated_at: '2026-10-30T00:00:00Z' }
test('global nonmatching pages retain cursor; local app/env projection never invents a server filter or premature empty result', () => {
  const first = { items: [{ ...rule, id: 'foreign', environment_id: 'other-app-env' }], next_cursor: 'next' }
  assert.deepEqual(alertRuleRows([first], [null], ['own-env'], null), { items: [], next: 'next' })
  const second = { items: [rule, rule], next_cursor: null }
  assert.deepEqual(alertRuleRows([first, second], [null, 'next'], ['own-env'], null), { items: [rule], next: null })
  assert.deepEqual(alertRuleRows([first, second], [null, 'next'], ['own-env'], 'another-env').items, [])
  assert.deepEqual(alertRuleRows([first], [null], ['other-app-env'], null).items, first.items)
})
test('rule pagination handles in-flight pages, stable dedup, global cursor loops and exhausted empty pages', () => {
  const first = { items: [rule], next_cursor: 'next' }
  assert.equal(alertRuleRows([first], [null, 'next'], ['own-env'], null).next, null)
  assert.throws(() => alertRuleRows([first, first], [null, 'next'], ['own-env'], null), ApiError)
  assert.deepEqual(alertRuleRows([{ items: [], next_cursor: null }], [null], ['own-env'], null), { items: [], next: null })
})
test('global query has only cursor/limit and rejects late session delivery and Engineer role', async () => {
  const store = createAuthStore(), client = createQueryClient(); let deliver!: () => void, calls = 0
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'actor', role: 'ADMIN' } }))
  const session = { generation: store.getState().generation, userId: 'actor', role: 'ADMIN' as const }
  const api: typeof alertRuleApi = { list: async params => { calls++; assert.deepEqual(params, { cursor: 'cursor', limit: 50 }); await new Promise<void>(resolve => { deliver = resolve }); return { items: [rule], next_cursor: null } }, create: async () => rule, edit: async () => rule, disable: async () => rule }
  const options = alertRuleOptions(session, 'cursor', api, store)
  try {
    const pending = client.fetchQuery(options), rejected = assert.rejects(pending); store.getState().clear(); deliver(); await rejected
    assert.equal(client.getQueryData(options.queryKey), undefined)
    await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'actor', role: 'ENGINEER' } }))
    await assert.rejects(client.fetchQuery(alertRuleOptions({ ...session, generation: store.getState().generation, role: 'ENGINEER' }, null, api, store)), error => error instanceof ApiError && error.status === 403)
    assert.equal(calls, 1)
  } finally { client.clear() }
})
