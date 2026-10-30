import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createEngineerAccess } from './useEngineerAccess.ts'
import { assignedEngineerOptions } from './useAssignedEngineers.ts'
import { engineerKeys } from './engineer.keys.ts'
import { userKeys } from '../users/user.keys.ts'
import { applicationKeys } from '../applications/application.keys.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { createClients } from '../../api/client.ts'
import { createApplicationAccessApi } from '../../api/application.api.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { Application } from '../applications/application.types.ts'
const applicationId = '31801983-8baa-4b0a-ba77-8df1f212668b', userId = '51801983-8baa-4b0a-ba77-8df1f212668b'
const user = { id: userId, username: 'engineer', role: 'ENGINEER' as const, status: 'ACTIVE' as const }
const app: Application = { id: applicationId, name: 'order-service', description: null, status: 'ACTIVE', environments: [] }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', expires_in: 3600, token_type: 'Bearer' as const }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: 'actor', role }, unbind = bindSessionCache(client, store)
  let assigned = false, writes = 0, reads = 0
  const api = { list: async () => { reads++; return { items: assigned ? [user] : [], next_cursor: null as string | null } },
    grant: async () => { writes++; assigned = true }, remove: async () => { writes++; assigned = false } }
  return { client, store, session, applicationId, api, users: { get: async () => user }, applications: { list: async () => ({ items: [app], next_cursor: null as string | null }) },
    get assigned() { return assigned }, set assigned(value: boolean) { assigned = value }, get writes() { return writes }, get reads() { return reads }, cleanup() { unbind(); client.clear() } }
}
test('confirmed grant/removal retires only this application roster and refetches actual membership', async () => {
  const f = await fixture(), root = engineerKeys.page(f.session, applicationId), other = engineerKeys.page(f.session, 'other')
  f.client.setQueryData(root, { items: [], next_cursor: 'old' }); f.client.setQueryData(engineerKeys.page(f.session, applicationId, { cursor: 'old' }), 'private')
  f.client.setQueryData(other, 'other application')
  try {
    const action = createEngineerAccess(f)
    assert.deepEqual(await action.execute({ kind: 'grant', userId }), { kind: 'confirmed', assigned: true })
    assert.equal(f.client.getQueryData(root), undefined)
    assert.equal(f.client.getQueriesData({ queryKey: engineerKeys.root(f.session, applicationId) }).length, 0)
    const current = await f.client.fetchQuery(assignedEngineerOptions(f.session, applicationId, null, f.api, f.store))
    assert.deepEqual(current.items, [user]); assert.equal(f.client.getQueryData(other), 'other application')
    assert.deepEqual(await action.execute({ kind: 'remove', userId }), { kind: 'confirmed', assigned: false })
    assert.deepEqual((await f.client.fetchQuery(assignedEngineerOptions(f.session, applicationId, null, f.api, f.store))).items, [])
    assert.equal(f.writes, 2)
  } finally { f.cleanup() }
})
test('absence in a partial roster never proves unassigned; fresh lookup traverses cursors before an authoritative no-op', async () => {
  const f = await fixture(), cursors: (string | null | undefined)[] = []
  const action = createEngineerAccess({ ...f, api: { ...f.api, list: async (id, params) => { assert.equal(id, applicationId); cursors.push(params?.cursor); return params?.cursor ? { items: [user], next_cursor: null } : { items: [], next_cursor: 'second' } } } })
  try {
    assert.deepEqual(await action.execute({ kind: 'grant', userId }), { kind: 'confirmed', assigned: true })
    assert.deepEqual(cursors, [null, 'second']); assert.equal(f.writes, 0)
  } finally { f.cleanup() }
})
test('disabled application or Engineer rejects grant, but disabled assignments can be removed', async () => {
  const f = await fixture()
  try {
    const inactiveApp = createEngineerAccess({ ...f, applications: { list: async () => ({ items: [{ ...app, status: 'DISABLED' }], next_cursor: null }) } })
    await assert.rejects(inactiveApp.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 409)
    const inactiveUser = createEngineerAccess({ ...f, users: { get: async () => ({ ...user, status: 'DISABLED' }) } })
    await assert.rejects(inactiveUser.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 409)
    assert.equal(f.writes, 0); f.assigned = true
    const remove = createEngineerAccess({ ...f, applications: { list: async () => { throw Error('Must not require active app for removal') } }, users: { get: async () => { throw Error('Must not exclude disabled member') } } })
    assert.deepEqual(await remove.execute({ kind: 'remove', userId }), { kind: 'confirmed', assigned: false }); assert.equal(f.writes, 1)
  } finally { f.cleanup() }
})
test('Admin guard and missing application prevent management writes; foreign scope never substitutes another app', async () => {
  const engineer = await fixture('ENGINEER'), admin = await fixture()
  try {
    await assert.rejects(createEngineerAccess(engineer).execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 403)
    const missing = createEngineerAccess({ ...admin, applications: { list: async () => ({ items: [{ ...app, id: userId }], next_cursor: null }) } })
    await assert.rejects(missing.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 404)
    assert.equal(engineer.reads, 0); assert.equal(admin.writes, 0)
  } finally { engineer.cleanup(); admin.cleanup() }
})
test('double confirmations share one pending command and do not optimistically patch roster', async () => {
  const f = await fixture(); let finish!: () => void, started!: () => void, writes = 0
  const ready = new Promise<void>(resolve => { started = resolve })
  const key = engineerKeys.page(f.session, applicationId); f.client.setQueryData(key, 'before')
  const action = createEngineerAccess({ ...f, api: { ...f.api, grant: async () => { writes++; started(); await new Promise<void>(resolve => { finish = resolve }) } } })
  try {
    const first = action.execute({ kind: 'grant', userId }), second = action.execute({ kind: 'grant', userId })
    assert.equal(first, second); await ready
    assert.equal(f.client.getQueryData(key), 'before'); assert.equal(writes, 1)
    finish(); await first; assert.equal(f.client.getQueryData(key), undefined)
  } finally { f.cleanup() }
})
test('lost grant response checks server membership and reports uncertainty without replaying or claiming request success', async () => {
  const f = await fixture(); let writes = 0
  const action = createEngineerAccess({ ...f, api: { ...f.api, grant: async () => { writes++; f.assigned = true; throw new ApiError(0, 'Connection lost.') } } })
  try {
    assert.deepEqual(await action.execute({ kind: 'grant', userId }), { kind: 'uncertain', reconciled: true, assigned: true })
    assert.equal(writes, 1); assert.equal(f.reads, 2); assert.equal(action.needsReconciliation, false)
  } finally { f.cleanup() }
})
test('failed GET reconciliation blocks another write until explicit GET-only check succeeds, traversing to exhaustion for absence', async () => {
  const f = await fixture(); f.assigned = true; let writes = 0, available = false, reads = 0
  const action = createEngineerAccess({ ...f, api: { ...f.api,
    list: async (_id, params) => { reads++; if (writes && !available) throw new ApiError(503, 'Unavailable.'); return writes ? { items: [], next_cursor: params?.cursor ? null : 'last' } : { items: [user], next_cursor: null } },
    remove: async () => { writes++; throw new ApiError(502, 'Invalid response.') },
  } })
  try {
    assert.deepEqual(await action.execute({ kind: 'remove', userId }), { kind: 'uncertain', reconciled: false }); assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 409)
    available = true
    assert.deepEqual(await action.execute('check'), { kind: 'uncertain', reconciled: true, assigned: false })
    assert.equal(action.needsReconciliation, false); assert.equal(writes, 1); assert.equal(reads, 4)
  } finally { f.cleanup() }
})
test('definite 400/403/404 rejections are not replayed; 403 clears roster, directory and application metadata', async () => {
  for (const status of [400, 403, 404]) {
    const f = await fixture(); let writes = 0
    const keys = [engineerKeys.page(f.session, applicationId), userKeys.page(f.session), applicationKeys.selection(f.session, applicationId)]
    keys.forEach(key => f.client.setQueryData(key, 'private'))
    try {
      const action = createEngineerAccess({ ...f, api: { ...f.api, grant: async () => { writes++; throw new ApiError(status, 'Rejected.') } } })
      await assert.rejects(action.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === status)
      assert.equal(writes, 1); assert.equal(f.reads, 1)
      if (status === 403) keys.forEach(key => assert.equal(f.client.getQueryData(key), undefined))
    } finally { f.cleanup() }
  }
})
test('actual protected POST401 uses shared renewal for GET reconciliation once, never replays POST', async () => {
  const f = await fixture(); let writes = 0, gets = 0, refreshes = 0
  const transport = createClients(f.store, { adapter: async config => {
    assert.match(String(config.headers.get('Authorization')), /^Bearer /)
    if (config.method === 'post') { writes++; throw new axios.AxiosError('Sensitive details', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: {} }) }
    gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [], next_cursor: null } } }
  } })
  transport.bindRefresh(async () => { refreshes++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
  try {
    const outcome = await createEngineerAccess({ ...f, api: createApplicationAccessApi(transport.apiClient), renew: transport.refresh }).execute({ kind: 'grant', userId })
    assert.deepEqual(outcome, { kind: 'uncertain', reconciled: true, assigned: false })
    assert.equal(writes, 1); assert.equal(gets, 2); assert.equal(refreshes, 1)
    assert.doesNotMatch(JSON.stringify(outcome), /Sensitive|synthetic-access|rotated-refresh/)
  } finally { f.cleanup() }
})
test('membership cursor loop and cancellation stop before any write, including adapters ignoring abort', async () => {
  const f = await fixture(); let finish!: () => void, started!: () => void
  const ready = new Promise<void>(resolve => { started = resolve })
  try {
    const loop = createEngineerAccess({ ...f, api: { ...f.api, list: async () => ({ items: [], next_cursor: 'repeat' }) } })
    await assert.rejects(loop.execute({ kind: 'grant', userId }), ApiError)
    const action = createEngineerAccess({ ...f, api: { ...f.api, list: async () => { started(); await new Promise<void>(resolve => { finish = resolve }); return { items: [], next_cursor: null } } } })
    const pending = action.execute({ kind: 'grant', userId }); await ready; action.cancel(); finish()
    await assert.rejects(pending, ApiError); assert.equal(f.writes, 0)
  } finally { f.cleanup() }
})
test('late write results after logout, account switch or same-user new generation cannot report success or refill caches', async () => {
  for (const nextUser of [null, 'other', 'actor']) {
    const f = await fixture(); let finish!: () => void, started!: () => void
    const ready = new Promise<void>(resolve => { started = resolve })
    const action = createEngineerAccess({ ...f, api: { ...f.api, grant: async () => { started(); await new Promise<void>(resolve => { finish = resolve }) } } })
    try {
      const pending = action.execute({ kind: 'grant', userId }); await ready; f.store.getState().clear()
      if (nextUser) await f.store.getState().login({ username: nextUser, password: 'synthetic' }, async () => ({ ...pair, user: { id: nextUser, username: nextUser, role: 'ADMIN' } }))
      finish(); await assert.rejects(pending, ApiError); assert.equal(f.client.getQueryCache().getAll().length, 0)
    } finally { f.cleanup() }
  }
})
test('logging-out phase prevents new reads and commands before revocation finishes', async () => {
  const f = await fixture(); f.store.setState({ phase: 'logging-out' })
  try { await assert.rejects(createEngineerAccess(f).execute({ kind: 'grant', userId }), ApiError); assert.equal(f.reads, 0); assert.equal(f.writes, 0) }
  finally { f.cleanup() }
})

test('failed shared renewal after a write401 preserves the reconciliation gate and never resubmits the write', async () => {
  const f = await fixture(); let writes = 0
  const action = createEngineerAccess({ ...f, api: { ...f.api, grant: async () => { writes++; throw new ApiError(401, 'Expired.') } }, renew: async () => { throw new ApiError(503, 'Unavailable.') } })
  try {
    await assert.rejects(action.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 503)
    assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute({ kind: 'grant', userId }), error => error instanceof ApiError && error.status === 409)
    assert.deepEqual(await action.execute('check'), { kind: 'uncertain', reconciled: true, assigned: false })
    assert.equal(writes, 1); assert.equal(action.needsReconciliation, false)
  } finally { f.cleanup() }
})
