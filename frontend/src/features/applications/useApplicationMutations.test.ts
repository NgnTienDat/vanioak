import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createApplicationCommands } from './useApplicationMutations.ts'
import { applicationKeys } from './application.keys.ts'
import type { Application } from './application.types.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { createClients } from '../../api/client.ts'
import { createApplicationApi } from '../../api/application.api.ts'
import { ApiError } from '../../lib/apiError.ts'

const id = '31801983-8baa-4b0a-ba77-8df1f212668b', env = '41801983-8baa-4b0a-ba77-8df1f212668b'
const app: Application = { id, name: 'order-service', description: null, status: 'ACTIVE', environments: [{ id: env, name: 'DEV', status: 'ACTIVE' }] }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', token_type: 'Bearer' as const, expires_in: 3600 }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: 'actor', role }
  const unbind = bindSessionCache(client, store)
  const api = { list: async () => ({ items: [app], next_cursor: null as string | null }), create: async () => app, edit: async () => app, status: async () => app }
  return { store, client, session, api, cleanup() { unbind(); client.clear() } }
}
test('confirmed create retires every filter/cursor/selection snapshot in this session, preserving other sessions', async () => {
  const f = await fixture()
  const stale = [applicationKeys.page(f.session, {}), applicationKeys.page(f.session, { cursor: 'old' }), applicationKeys.page(f.session, { status: 'DISABLED' }), applicationKeys.selection(f.session, id)]
  stale.forEach(key => f.client.setQueryData(key, 'old'))
  const other = applicationKeys.page({ ...f.session, generation: 99 }, {})
  f.client.setQueryData(other, 'other')
  try {
    const result = await createApplicationCommands(f).execute({ kind: 'create', values: { name: app.name } })
    assert.deepEqual(result, { kind: 'confirmed', application: app })
    stale.forEach(key => assert.equal(f.client.getQueryData(key), undefined))
    assert.equal(f.client.getQueryData(other), 'other')
  } finally { f.cleanup() }
})
test('edit resolves the exact application across authorized pages and sends only the requested dirty fields', async () => {
  const f = await fixture(), cursors: (string | null | undefined)[] = [], edits: unknown[] = []
  const action = createApplicationCommands({ ...f, api: { ...f.api,
    list: async params => { cursors.push(params?.cursor); return params?.cursor ? { items: [app], next_cursor: null } : { items: [{ ...app, id: env }], next_cursor: 'next' } },
    edit: async (target, values) => { edits.push([target, values]); return { ...app, ...values } },
  } })
  try {
    assert.equal((await action.execute({ kind: 'edit', id, values: { description: '' } })).kind, 'confirmed')
    assert.deepEqual(cursors, [null, 'next']); assert.deepEqual(edits, [[id, { description: '' }]])
  } finally { f.cleanup() }
})
test('disabled application forbids detail edits but allows a separate explicit status reactivation', async () => {
  const f = await fixture(); let writes = 0
  const action = createApplicationCommands({ ...f, api: { ...f.api, list: async () => ({ items: [{ ...app, status: 'DISABLED' }], next_cursor: null }),
    edit: async () => { writes++; return app }, status: async (target, status) => { writes++; assert.equal(target, id); assert.equal(status, 'ACTIVE'); return app },
  } })
  try {
    await assert.rejects(action.execute({ kind: 'edit', id, values: { name: 'new' } }), error => error instanceof ApiError && error.status === 409)
    assert.equal(writes, 0)
    assert.equal((await action.execute({ kind: 'status', id, status: 'ACTIVE' })).kind, 'confirmed'); assert.equal(writes, 1)
  } finally { f.cleanup() }
})
test('an Engineer cannot use management commands and a missing target never falls back to another application', async () => {
  const engineer = await fixture('ENGINEER'), admin = await fixture(); let requests = 0
  try {
    const denied = createApplicationCommands({ ...engineer, api: { ...engineer.api, create: async () => { requests++; return app } } })
    await assert.rejects(denied.execute({ kind: 'create', values: { name: 'app' } }), error => error instanceof ApiError && error.status === 403)
    const missing = createApplicationCommands({ ...admin, api: { ...admin.api, status: async () => { requests++; return app } } })
    await assert.rejects(missing.execute({ kind: 'status', id: env, status: 'DISABLED' }), error => error instanceof ApiError && error.status === 404)
    assert.equal(requests, 0)
  } finally { engineer.cleanup(); admin.cleanup() }
})
test('duplicate submits share one command and cache does not claim optimistic success while the write is pending', async () => {
  const f = await fixture(); let writes = 0, finish!: (value: Application) => void
  const waiting = new Promise<Application>(resolve => { finish = resolve })
  const key = applicationKeys.page(f.session, {})
  f.client.setQueryData(key, { items: [], next_cursor: null })
  const action = createApplicationCommands({ ...f, api: { ...f.api, create: () => { writes++; return waiting } } })
  try {
    const first = action.execute({ kind: 'create', values: { name: app.name } })
    assert.equal(first, action.execute({ kind: 'create', values: { name: app.name } }))
    assert.equal(writes, 1); assert.deepEqual(f.client.getQueryData(key), { items: [], next_cursor: null })
    finish(app); await first; assert.equal(writes, 1)
  } finally { f.cleanup() }
})
test('lost create response GET-reconciles by exact name across pages without declaring the write successful', async () => {
  const f = await fixture(); let writes = 0, reads = 0
  const action = createApplicationCommands({ ...f, api: { ...f.api, create: async () => { writes++; throw new ApiError(0, 'Lost reply.') },
    list: async params => { reads++; assert.equal(params?.status, undefined); return params?.cursor ? { items: [app], next_cursor: null } : { items: [], next_cursor: 'next' } },
  } })
  try {
    const result = await action.execute({ kind: 'create', values: { name: app.name } })
    assert.equal(result.kind, 'uncertain')
    if (result.kind !== 'uncertain') assert.fail('Expected uncertain outcome')
    assert.equal(result.reconciled, true); assert.deepEqual(result.application, app)
    assert.equal(writes, 1); assert.equal(reads, 2); assert.equal(action.needsReconciliation, false)
  } finally { f.cleanup() }
})
test('failed reconciliation gates further writes until an explicit GET-only check succeeds', async () => {
  const f = await fixture(); let reads = 0, writes = 0, available = false
  const action = createApplicationCommands({ ...f, api: { ...f.api, create: async () => { writes++; throw new ApiError(502, 'Malformed write reply.') },
    list: async () => { reads++; if (!available) throw new ApiError(503, 'Unavailable.'); return { items: [], next_cursor: null } },
  } })
  try {
    const result = await action.execute({ kind: 'create', values: { name: app.name } })
    assert.equal(result.kind === 'uncertain' && result.reconciled, false); assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute({ kind: 'create', values: { name: 'second' } }), error => error instanceof ApiError && error.status === 409)
    await action.execute('check'); assert.equal(action.needsReconciliation, true)
    available = true
    const checked = await action.execute('check')
    assert.equal(checked.kind === 'uncertain' && checked.reconciled, true)
    assert.equal(action.needsReconciliation, false); assert.equal(writes, 1); assert.equal(reads, 3)
  } finally { f.cleanup() }
})
test('preflight pagination loop fails before writing and cancellation stops an adapter that ignores abort', async () => {
  const f = await fixture(); let writes = 0, finish!: () => void, ready!: () => void
  const started = new Promise<void>(resolve => { ready = resolve })
  try {
    const loop = createApplicationCommands({ ...f, api: { ...f.api, list: async () => ({ items: [], next_cursor: 'repeat' }), edit: async () => { writes++; return app } } })
    await assert.rejects(loop.execute({ kind: 'edit', id, values: { name: 'new' } }), ApiError)
    const cancelled = createApplicationCommands({ ...f, api: { ...f.api, list: async () => { ready(); await new Promise<void>(resolve => { finish = resolve }); return { items: [app], next_cursor: null } }, edit: async () => { writes++; return app } } })
    const pending = cancelled.execute({ kind: 'edit', id, values: { name: 'new' } })
    await started; cancelled.cancel(); finish(); await assert.rejects(pending, ApiError); assert.equal(writes, 0)
  } finally { f.cleanup() }
})
test('definite 400/403/404/409 write rejection is never replayed; forbidden removes sensitive app caches', async () => {
  for (const status of [400, 403, 404, 409]) {
    const f = await fixture(); let writes = 0, reads = 0
    const root = applicationKeys.page(f.session, {})
    const logKey = ['logs', f.session.generation, 'actor', 'ADMIN', id, env]
    f.client.setQueryData(root, 'private'); f.client.setQueryData(logKey, 'private')
    const action = createApplicationCommands({ ...f, api: { ...f.api, list: async () => { reads++; return { items: [app], next_cursor: null } }, edit: async () => { writes++; throw new ApiError(status, 'Rejected.') } } })
    try {
      await assert.rejects(action.execute({ kind: 'edit', id, values: { name: 'new' } }), error => error instanceof ApiError && error.status === status)
      assert.equal(writes, 1); assert.equal(reads, 1)
      if (status === 403) { assert.equal(f.client.getQueryData(root), undefined); assert.equal(f.client.getQueryData(logKey), undefined) }
    } finally { f.cleanup() }
  }
})
test('actual protected PATCH401 shares existing renewal for GET reconciliation and never replays PATCH', async () => {
  const f = await fixture(); let patches = 0, gets = 0, renewals = 0
  const transport = createClients(f.store, { adapter: async config => {
    assert.match(String(config.headers.get('Authorization')), /^Bearer /)
    if (config.method === 'patch') {
      patches++
      assert.deepEqual(JSON.parse(config.data), { status: 'DISABLED' })
      throw new axios.AxiosError('Sensitive error', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: {} })
    }
    gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [app], next_cursor: null } } }
  } })
  transport.bindRefresh(async () => { renewals++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
  try {
    const result = await createApplicationCommands({ ...f, api: createApplicationApi(transport.apiClient), renew: transport.refresh }).execute({ kind: 'status', id, status: 'DISABLED' })
    assert.equal(result.kind, 'uncertain'); assert.equal(patches, 1); assert.equal(gets, 2); assert.equal(renewals, 1)
    assert.doesNotMatch(JSON.stringify(result), /Sensitive|synthetic-access|rotated-refresh/)
  } finally { f.cleanup() }
})
test('late committed create after logout or same-user new generation cannot refill cache or report success', async () => {
  for (const nextLogin of [false, true]) {
    const f = await fixture(); let finish!: (value: Application) => void
    const waiting = new Promise<Application>(resolve => { finish = resolve })
    const action = createApplicationCommands({ ...f, api: { ...f.api, create: () => waiting } })
    try {
      const pending = action.execute({ kind: 'create', values: { name: app.name } })
      f.store.getState().clear()
      if (nextLogin) await f.store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role: 'ADMIN' } }))
      finish(app); await assert.rejects(pending, ApiError)
      assert.equal(f.client.getQueryCache().getAll().length, 0)
    } finally { f.cleanup() }
  }
})
test('logging-out phase blocks new commands before token revocation settles', async () => {
  const f = await fixture(); let writes = 0
  f.store.setState({ phase: 'logging-out' })
  try {
    await assert.rejects(createApplicationCommands({ ...f, api: { ...f.api, create: async () => { writes++; return app } } }).execute({ kind: 'create', values: { name: app.name } }), ApiError)
    assert.equal(writes, 0)
  } finally { f.cleanup() }
})
test('current target status is an authoritative no-op, not an unnecessary second PATCH', async () => {
  const f = await fixture(); let patches = 0
  try {
    const result = await createApplicationCommands({ ...f, api: { ...f.api, status: async () => { patches++; return app } } }).execute({ kind: 'status', id, status: 'ACTIVE' })
    assert.deepEqual(result, { kind: 'confirmed', application: app }); assert.equal(patches, 0)
  } finally { f.cleanup() }
})
