import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { MutationObserver } from '@tanstack/react-query'
import { createUserCommands } from './useUserMutations.ts'
import { createUserApi, createUserManagementApi, userManagementApi } from '../../api/user.api.ts'
import { createClients } from '../../api/client.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
import { userKeys } from './user.keys.ts'
import { userOptions } from './useUsers.ts'
import type { Engineer, UserCommand } from './user.types.ts'

const id = '51801983-8baa-4b0a-ba77-8df1f212668b'
const engineer: Engineer = { id, username: 'engineer', role: 'ENGINEER', status: 'ACTIVE' }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', expires_in: 3600, token_type: 'Bearer' as const }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: 'actor', role }, unbind = bindSessionCache(client, store)
  let current = engineer
  const calls = { get: 0, list: 0, create: 0, edit: 0, status: 0 }
  const reads = { get: async () => { calls.get++; return current }, list: async () => { calls.list++; return { items: [current], next_cursor: null as string | null } } }
  const api: typeof userManagementApi = {
    create: async values => { calls.create++; current = { ...engineer, username: values.username }; return current },
    edit: async (_id, values) => { calls.edit++; current = { ...current, username: values.username ?? current.username }; return current },
    status: async (_id, status) => { calls.status++; current = { ...current, status }; return current },
  }
  return { store, client, session, reads, api, calls, cleanup() { unbind(); client.clear() } }
}

test('create/edit/disable/enable confirm actual identity, retire directory/detail/roster caches and preserve other sessions', async () => {
  const f = await fixture(), action = createUserCommands(f), root = userKeys.root(f.session)
  try {
    const other = userKeys.page({ ...f.session, generation: f.session.generation + 1 })
    f.client.setQueryData(other, 'other session')
    for (const command of [{ kind: 'create', values: { username: 'new', password: 'synthetic' } }, { kind: 'edit', id, values: { username: 'updated' } }, { kind: 'status', id, status: 'DISABLED' }, { kind: 'status', id, status: 'ACTIVE' }] satisfies UserCommand[]) {
      f.client.setQueryData(userKeys.page(f.session, { cursor: 'old' }), 'old')
      f.client.setQueryData(userKeys.detail(f.session, id), engineer)
      const roster = ['assigned-engineers', f.session.generation, f.session.userId, f.session.role, 'app', 'page']
      f.client.setQueryData(roster, 'old')
      const result = await action.execute(command)
      assert.equal(result.kind, 'confirmed'); assert.equal(f.client.getQueriesData({ queryKey: root }).length, 0)
      assert.equal(f.client.getQueryData(roster), undefined)
      const page = await f.client.fetchQuery(userOptions(f.session, undefined, null, f.reads, f.store))
      if (result.kind === 'confirmed') assert.deepEqual(page.items, [result.user])
    }
    assert.equal(f.client.getQueryData(other), 'other session')
    assert.deepEqual([f.calls.create, f.calls.edit, f.calls.status], [1, 1, 2])
  } finally { f.cleanup() }
})
test('pending confirmations share one command without optimistic cache changes or mutation-held passwords', async () => {
  const f = await fixture(), sentinel = 'synthetic-private-value'
  let finish!: () => void, started!: () => void
  const ready = new Promise<void>(resolve => { started = resolve })
  f.api.create = async () => { f.calls.create++; started(); await new Promise<void>(resolve => { finish = resolve }); return engineer }
  const action = createUserCommands(f), command: UserCommand = { kind: 'create', values: { username: 'e', password: sentinel } }
  const observer = new MutationObserver(f.client, { mutationFn: async () => { await action.execute(command) }, retry: false, gcTime: 0 })
  f.client.setQueryData(userKeys.page(f.session), 'before')
  try {
    const work = observer.mutate(); await ready
    const joined = action.execute({ kind: 'status', id, status: 'DISABLED' })
    assert.equal(f.client.getQueryData(userKeys.page(f.session)), 'before')
    assert.equal(observer.getCurrentResult().variables, undefined)
    assert.ok(!JSON.stringify(f.client.getMutationCache().getAll().map(item => item.state)).includes(sentinel))
    finish(); await work; await joined
    assert.equal(f.calls.create, 1); assert.equal(f.calls.status, 0)
    assert.equal(observer.getCurrentResult().data, undefined)
    assert.equal(command.values.password, '')
    assert.ok(!JSON.stringify(f.client.getQueryCache().getAll().map(item => item.state)).includes(sentinel))
  } finally { f.cleanup() }
})
test('fresh target reads precede edit/status, disabled users remain editable and already-current status is a read-only no-op', async () => {
  const f = await fixture(), action = createUserCommands(f)
  try {
    f.reads.get = async () => { f.calls.get++; return { ...engineer, status: 'DISABLED' } }
    await action.execute({ kind: 'edit', id, values: { password: ' new ' } })
    await action.execute({ kind: 'status', id, status: 'DISABLED' })
    assert.equal(f.calls.get, 2); assert.equal(f.calls.edit, 1); assert.equal(f.calls.status, 0)
    f.reads.get = async () => { throw new ApiError(404, 'Not found') }
    await assert.rejects(action.execute({ kind: 'status', id, status: 'ACTIVE' }), error => error instanceof ApiError && error.status === 404)
    assert.equal(f.calls.status, 0)
  } finally { f.cleanup() }
})
test('Engineer role is rejected before any read/write and password input is cleared on failure', async () => {
  const f = await fixture('ENGINEER'), command: UserCommand = { kind: 'create', values: { username: 'e', password: 'synthetic' } }
  try {
    await assert.rejects(createUserCommands(f).execute(command), error => error instanceof ApiError && error.status === 403)
    assert.equal(command.values.password, ''); assert.equal(f.calls.create + f.calls.get + f.calls.list, 0)
  } finally { f.cleanup() }
})
test('lost password-update reply reconciles identity without claiming password or write success and never replays', async () => {
  const f = await fixture(), command: UserCommand = { kind: 'edit', id, values: { password: 'synthetic-private-value' } }
  f.api.edit = async () => { f.calls.edit++; throw new ApiError(0, 'Connection lost') }
  try {
    const outcome = await createUserCommands(f).execute(command)
    assert.equal(outcome.kind, 'uncertain')
    if (outcome.kind === 'uncertain') { assert.equal(outcome.reconciled, true); assert.match(outcome.notice, /GET cannot verify the password/); assert.match(outcome.notice, /write remains unconfirmed/); assert.doesNotMatch(outcome.notice, /synthetic-private-value/) }
    assert.equal(f.calls.get, 2); assert.equal(f.calls.edit, 1); assert.equal(command.values.password, '')
  } finally { f.cleanup() }
})
test('failed reconciliation gates later writes until explicit GET check succeeds', async () => {
  const f = await fixture(); let fail = true
  f.api.create = async () => { f.calls.create++; throw new ApiError(502, 'Invalid response') }
  f.reads.list = async () => { f.calls.list++; if (fail) throw new ApiError(503, 'Unavailable'); return { items: [engineer], next_cursor: null } }
  const action = createUserCommands(f)
  try {
    assert.deepEqual((await action.execute({ kind: 'create', values: { username: engineer.username, password: 'synthetic' } })).kind, 'uncertain')
    assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute({ kind: 'status', id, status: 'DISABLED' }), error => error instanceof ApiError && error.status === 409)
    assert.equal(f.calls.status, 0); fail = false
    const check = await action.execute('check'); assert.equal(check.kind, 'uncertain'); assert.equal(action.needsReconciliation, false)
    assert.equal(f.calls.create, 1)
  } finally { f.cleanup() }
})
test('uncertain create traverses real cursors; absence and a matching username both remain unconfirmed', async () => {
  const f = await fixture(); let pages = 0
  f.api.create = async () => { f.calls.create++; throw new ApiError(500, 'Unavailable') }
  f.reads.list = async () => ({ items: ++pages % 2 === 1 ? [] : [engineer], next_cursor: pages % 2 === 1 ? 'next' : null })
  try {
    const outcome = await createUserCommands(f).execute({ kind: 'create', values: { username: engineer.username, password: 'synthetic' } })
    assert.equal(pages, 2); assert.equal(outcome.kind, 'uncertain')
    if (outcome.kind === 'uncertain') assert.match(outcome.notice, /earlier write remains unconfirmed/)
    f.reads.list = async () => ({ items: [], next_cursor: null })
    const absent = await createUserCommands(f).execute({ kind: 'create', values: { username: 'absent', password: 'synthetic' } })
    if (absent.kind === 'uncertain') assert.match(absent.notice, /No matching/)
  } finally { f.cleanup() }
})
test('cursor loop during uncertain create keeps the reconciliation gate closed', async () => {
  const f = await fixture()
  f.api.create = async () => { throw new ApiError(500, 'Unavailable') }
  f.reads.list = async () => ({ items: [], next_cursor: 'loop' })
  const action = createUserCommands(f)
  try {
    const result = await action.execute({ kind: 'create', values: { username: 'e', password: 'synthetic' } })
    assert.equal(result.kind === 'uncertain' && result.reconciled, false); assert.equal(action.needsReconciliation, true)
  } finally { f.cleanup() }
})
test('definite 400/404 failures do not automatically retry or claim success; 403 redacts only this session management caches', async () => {
  const f = await fixture(), root = userKeys.page(f.session)
  try {
    for (const status of [400, 404]) {
      f.api.create = async () => { f.calls.create++; throw new ApiError(status, 'Rejected') }
      await assert.rejects(createUserCommands(f).execute({ kind: 'create', values: { username: 'e', password: 'synthetic' } }), error => error instanceof ApiError && error.status === status)
    }
    assert.equal(f.calls.create, 2); assert.equal(f.calls.list, 0)
    f.client.setQueryData(root, 'private')
    const other = userKeys.page({ ...f.session, userId: 'other' }); f.client.setQueryData(other, 'other')
    for (const domain of ['applications', 'assigned-engineers', 'api-keys', 'alert-rules']) f.client.setQueryData([domain, f.session.generation, f.session.userId, f.session.role], 'private')
    f.api.create = async () => { throw new ApiError(403, 'Denied') }
    await assert.rejects(createUserCommands(f).execute({ kind: 'create', values: { username: 'e', password: 'synthetic' } }), ApiError)
    assert.equal(f.client.getQueryData(root), undefined); assert.equal(f.client.getQueryData(other), 'other')
    for (const domain of ['applications', 'assigned-engineers', 'api-keys', 'alert-rules']) assert.equal(f.client.getQueryData([domain, f.session.generation, f.session.userId, f.session.role]), undefined)
  } finally { f.cleanup() }
})
test('actual protected PATCH401 shares refresh for GET reconciliation but never repeats the write', async () => {
  const f = await fixture(); let writes = 0, refreshes = 0, gets = 0
  const clients = createClients(f.store, { adapter: async config => {
    if (config.method === 'patch') { writes++; throw new axios.AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: null }) }
    gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: engineer } }
  } })
  clients.bindRefresh(async () => { refreshes++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
  try {
    const result = await createUserCommands({ ...f, api: createUserManagementApi(clients.apiClient), reads: createUserApi(clients.apiClient), renew: clients.refresh }).execute({ kind: 'edit', id, values: { password: 'synthetic' } })
    assert.equal(result.kind, 'uncertain'); assert.deepEqual([writes, refreshes, gets], [1, 1, 2])
  } finally { f.cleanup() }
})
test('failed renewal retains sanitized reconciliation gate and no password-bearing error or command result', async () => {
  const f = await fixture(), command: UserCommand = { kind: 'create', values: { username: 'e', password: 'synthetic-private-value' } }
  f.api.create = async () => { throw new ApiError(401, 'Expired') }
  const action = createUserCommands({ ...f, renew: async () => { throw new ApiError(503, 'Unavailable') } })
  try {
    await assert.rejects(action.execute(command), error => error instanceof ApiError && error.status === 503 && !error.message.includes('synthetic-private-value'))
    assert.equal(action.needsReconciliation, true); assert.equal(command.values.password, '')
    await assert.rejects(action.execute({ kind: 'status', id, status: 'ACTIVE' }), ApiError)
  } finally { f.cleanup() }
})
test('logout and same-user replacement reject late mutation results and do not refill new-session caches', async () => {
  for (const replacement of [false, true]) {
    const f = await fixture(); let finish!: (value: Engineer) => void
    f.api.create = async () => new Promise(resolve => { finish = resolve })
    const action = createUserCommands(f), command: UserCommand = { kind: 'create', values: { username: 'e', password: 'synthetic-private-value' } }
    try {
      const work = action.execute(command), rejection = assert.rejects(work)
      f.store.getState().clear()
      if (replacement) await f.store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role: 'ADMIN' } }))
      finish(engineer); await rejection
      assert.equal(f.client.getQueryCache().getAll().length, 0); assert.equal(command.values.password, '')
    } finally { f.cleanup() }
  }
})
test('cancellation before a late read prevents writes; cancellation after submission does not claim rollback', async () => {
  const f = await fixture(); let finish!: (value: Engineer) => void
  f.reads.get = async () => new Promise(resolve => { finish = resolve })
  const action = createUserCommands(f)
  try {
    const command: UserCommand = { kind: 'edit', id, values: { password: 'synthetic' } }
    const work = action.execute(command), rejection = assert.rejects(work)
    action.cancel(); assert.equal(command.values.password, ''); finish(engineer); await rejection; assert.equal(f.calls.edit, 0)
    f.api.create = async () => new Promise(resolve => { finish = resolve })
    const submitted = action.execute({ kind: 'create', values: { username: 'e', password: 'synthetic' } }), rejected = assert.rejects(submitted)
    action.cancel(); finish(engineer); await rejected
    assert.equal(f.client.getQueryCache().getAll().length, 0)
  } finally { f.cleanup() }
})
