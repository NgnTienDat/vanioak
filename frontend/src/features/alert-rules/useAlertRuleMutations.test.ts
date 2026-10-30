import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createAlertRuleCommands } from './useAlertRuleMutations.ts'
import { alertRuleKeys } from './alertRule.keys.ts'
import { alertRuleOptions } from './useAlertRules.ts'
import { createAlertRuleApi } from '../../api/alert-rule.api.ts'
import type { alertRuleApi } from '../../api/alert-rule.api.ts'
import type { AlertRule, CreateAlertRule } from './alertRule.types.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createClients } from '../../api/client.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { Application } from '../applications/application.types.ts'
const applicationId = '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId = '41801983-8baa-4b0a-ba77-8df1f212668b'
const rule: AlertRule = { id: '51801983-8baa-4b0a-ba77-8df1f212668b', environment_id: environmentId, level: 'ERROR', threshold: 10, window_seconds: 60, cooldown_seconds: 1, resolve_after_seconds: 300, enabled: true, created_at: '2026-10-30T00:00:00Z', updated_at: '2026-10-30T00:00:00Z' }
const values: CreateAlertRule = { environment_id: environmentId, level: 'ERROR', threshold: 10, window_seconds: 60, cooldown_seconds: 1, resolve_after_seconds: 300, enabled: true }
const app: Application = { id: applicationId, name: 'orders', description: null, status: 'ACTIVE', environments: [{ id: environmentId, name: 'DEV', status: 'ACTIVE' }] }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', expires_in: 3600, token_type: 'Bearer' as const }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: 'actor', role }, unbind = bindSessionCache(client, store)
  const calls = { list: 0, create: 0, edit: 0, disable: 0 }; let current = rule
  const api: typeof alertRuleApi = { list: async () => { calls.list++; return { items: [current], next_cursor: null } }, create: async () => { calls.create++; return current }, edit: async (_rule, update) => { calls.edit++; current = { ...current, ...update }; return current }, disable: async () => { calls.disable++; current = { ...current, enabled: false }; return current } }
  return { store, client, session, applicationId, calls, api, applications: { list: async () => ({ items: [app], next_cursor: null as string | null }) }, cleanup() { unbind(); client.clear() } }
}
test('confirmed rule create/edit/disable resets every global page in the current session and refetches actual enabled state', async () => {
  const f = await fixture(), action = createAlertRuleCommands(f), own = alertRuleKeys.page(f.session), other = alertRuleKeys.page({ ...f.session, generation: 99 })
  f.client.setQueryData(other, 'another session')
  try {
    for (const command of [{ kind: 'create' as const, values }, { kind: 'edit' as const, id: rule.id, values: { threshold: 20 } }, { kind: 'disable' as const, id: rule.id }]) {
      f.client.setQueryData(own, 'before'); f.client.setQueryData(alertRuleKeys.page(f.session, { cursor: 'old' }), 'old snapshot')
      assert.equal((await action.execute(command)).kind, 'confirmed')
      assert.equal(f.client.getQueriesData({ queryKey: alertRuleKeys.root(f.session) }).length, 0)
      await f.client.fetchQuery(alertRuleOptions(f.session, null, f.api, f.store))
    }
    assert.equal(f.client.getQueryData(other), 'another session')
    const page = await f.client.fetchQuery(alertRuleOptions(f.session, null, f.api, f.store))
    assert.equal(page.items[0].enabled, false); assert.equal(page.items[0].threshold, 20)
    assert.deepEqual([f.calls.create, f.calls.edit, f.calls.disable], [1, 1, 1])
  } finally { f.cleanup() }
})
test('fresh global lookup traverses nonmatching pages, enforces exact app ownership and preserves immutable fields', async () => {
  const f = await fixture(), cursors: (string | null | undefined)[] = [], updates: unknown[] = []
  f.api.list = async params => { cursors.push(params?.cursor); return params?.cursor ? { items: [rule], next_cursor: null } : { items: [{ ...rule, id: environmentId, environment_id: 'foreign' }], next_cursor: 'next' } }
  f.api.edit = async (before, update) => { updates.push([before.id, update]); return { ...before, ...update } }
  try {
    await createAlertRuleCommands(f).execute({ kind: 'edit', id: rule.id, values: { cooldown_seconds: 5 } })
    assert.deepEqual(cursors, [null, 'next']); assert.deepEqual(updates, [[rule.id, { cooldown_seconds: 5 }]])
    await assert.rejects(createAlertRuleCommands(f).execute({ kind: 'disable', id: environmentId }), error => error instanceof ApiError && error.status === 403)
    assert.equal(f.calls.disable, 0)
  } finally { f.cleanup() }
})
test('disabled app/env forbids create/enable but allows numeric PATCH and DELETE disabling', async () => {
  const f = await fixture(), action = createAlertRuleCommands({ ...f, applications: { list: async () => ({ items: [{ ...app, status: 'DISABLED', environments: [{ ...app.environments[0], status: 'DISABLED' }] }], next_cursor: null }) } })
  try {
    await assert.rejects(action.execute({ kind: 'create', values }), error => error instanceof ApiError && error.status === 409)
    await assert.rejects(action.execute({ kind: 'edit', id: rule.id, values: { enabled: true } }), error => error instanceof ApiError && error.status === 409)
    assert.equal((await action.execute({ kind: 'edit', id: rule.id, values: { threshold: 5 } })).kind, 'confirmed')
    const result = await action.execute({ kind: 'disable', id: rule.id }); assert.ok(result.kind === 'confirmed' && !result.rule.enabled)
    assert.deepEqual([f.calls.create, f.calls.edit, f.calls.disable], [0, 1, 1])
  } finally { f.cleanup() }
})
test('duplicate confirmation shares one pending mutation without optimistic rule cache changes', async () => {
  const f = await fixture(); let finish!: () => void, started!: () => void, writes = 0
  const ready = new Promise<void>(resolve => { started = resolve })
  f.api.edit = async () => { writes++; started(); await new Promise<void>(resolve => { finish = resolve }); return { ...rule, enabled: false } }
  const action = createAlertRuleCommands(f), own = alertRuleKeys.page(f.session); f.client.setQueryData(own, 'before')
  try {
    const first = action.execute({ kind: 'edit', id: rule.id, values: { enabled: false } }), second = action.execute({ kind: 'edit', id: rule.id, values: { enabled: false } })
    assert.equal(first, second); await ready; assert.equal(f.client.getQueryData(own), 'before')
    finish(); await first; assert.equal(writes, 1); assert.equal(f.client.getQueryData(own), undefined)
  } finally { f.cleanup() }
})
test('lost create reply reconciles exact environment/level across global pages without claiming the write committed', async () => {
  const f = await fixture(); let writes = 0, reads = 0
  f.api.create = async () => { writes++; throw new ApiError(502, 'Malformed response.') }
  f.api.list = async params => { reads++; return params?.cursor ? { items: [rule], next_cursor: null } : { items: [{ ...rule, id: environmentId, environment_id: 'foreign' }], next_cursor: 'next' } }
  try {
    assert.deepEqual(await createAlertRuleCommands(f).execute({ kind: 'create', values }), { kind: 'uncertain', reconciled: true })
    assert.equal(reads, 2); assert.equal(writes, 1)
  } finally { f.cleanup() }
})
test('failed uncertain-write GET gates another mutation until explicit global status reconciliation succeeds', async () => {
  const f = await fixture(); let writes = 0, available = false
  f.api.disable = async () => { writes++; throw new ApiError(0, 'Lost response.') }
  f.api.list = async () => { if (writes && !available) throw new ApiError(503, 'Unavailable.'); return { items: [rule], next_cursor: null } }
  const action = createAlertRuleCommands(f)
  try {
    assert.deepEqual(await action.execute({ kind: 'disable', id: rule.id }), { kind: 'uncertain', reconciled: false })
    await assert.rejects(action.execute({ kind: 'disable', id: rule.id }), error => error instanceof ApiError && error.status === 409)
    assert.equal(action.needsReconciliation, true); available = true
    assert.deepEqual(await action.execute('check'), { kind: 'uncertain', reconciled: true }); assert.equal(writes, 1)
  } finally { f.cleanup() }
})
test('actual protected PATCH401 joins shared refresh then GETs, never automatically replays the write', async () => {
  const f = await fixture(); let writes = 0, gets = 0, refreshes = 0
  const transport = createClients(f.store, { adapter: async config => {
    if (config.method === 'patch') { writes++; assert.deepEqual(JSON.parse(config.data), { enabled: true }); throw new axios.AxiosError('Sensitive details', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: {} }) }
    gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [rule], next_cursor: null } } }
  } })
  transport.bindRefresh(async () => { refreshes++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
  try {
    const result = await createAlertRuleCommands({ ...f, api: createAlertRuleApi(transport.apiClient), renew: transport.refresh }).execute({ kind: 'edit', id: rule.id, values: { enabled: true } })
    assert.equal(result.kind, 'uncertain'); assert.equal(writes, 1); assert.equal(gets, 2); assert.equal(refreshes, 1)
  } finally { f.cleanup() }
})
test('duplicate400, forbidden403 and missing404 are safe rejections with no replay; 403 redacts global rule pages', async () => {
  for (const status of [400, 403, 404]) {
    const f = await fixture(); let writes = 0
    f.api.create = async () => { writes++; throw new ApiError(status, 'Rejected.') }
    const own = alertRuleKeys.page(f.session), other = alertRuleKeys.page({ ...f.session, generation: 99 }); f.client.setQueryData(own, 'private'); f.client.setQueryData(other, 'other session')
    try {
      await assert.rejects(createAlertRuleCommands(f).execute({ kind: 'create', values }), error => error instanceof ApiError && error.status === status)
      assert.equal(writes, 1); if (status === 403) assert.equal(f.client.getQueryData(own), undefined)
      assert.equal(f.client.getQueryData(other), 'other session')
    } finally { f.cleanup() }
  }
})
test('missing target and global cursor loop fail before any mutation; Engineer role is rejected before reads', async () => {
  const f = await fixture(), engineer = await fixture('ENGINEER')
  try {
    f.api.list = async () => ({ items: [], next_cursor: null })
    await assert.rejects(createAlertRuleCommands(f).execute({ kind: 'disable', id: rule.id }), error => error instanceof ApiError && error.status === 404)
    f.api.list = async () => ({ items: [], next_cursor: 'repeat' })
    await assert.rejects(createAlertRuleCommands(f).execute({ kind: 'disable', id: rule.id }), error => error instanceof ApiError && error.status === 502)
    await assert.rejects(createAlertRuleCommands(engineer).execute({ kind: 'create', values }), error => error instanceof ApiError && error.status === 403)
    assert.equal(f.calls.disable, 0); assert.equal(engineer.calls.list, 0); assert.equal(engineer.calls.create, 0)
  } finally { f.cleanup(); engineer.cleanup() }
})
test('cancel/unmount and late logout/new-account results cannot restore rule data or show confirmed success', async () => {
  for (const retire of ['cancel', 'logout', 'replace'] as const) {
    const f = await fixture(); let finish!: () => void, started!: () => void
    const ready = new Promise<void>(resolve => { started = resolve })
    f.api.create = async () => { started(); await new Promise<void>(resolve => { finish = resolve }); return rule }
    const action = createAlertRuleCommands(f)
    try {
      const pending = action.execute({ kind: 'create', values }); await ready
      if (retire === 'cancel') action.cancel()
      else { f.store.getState().clear(); if (retire === 'replace') await f.store.getState().login({ username: 'other', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'other', username: 'other', role: 'ADMIN' } })) }
      finish(); await assert.rejects(pending, ApiError); assert.equal(f.client.getQueryCache().getAll().length, 0)
    } finally { f.cleanup() }
  }
})
