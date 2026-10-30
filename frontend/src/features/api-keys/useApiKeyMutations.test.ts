import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MutationObserver } from '@tanstack/react-query'
import axios from 'axios'
import { createApiKeyCommands } from './useApiKeyMutations.ts'
import { apiKeyKeys } from './apiKey.keys.ts'
import { apiKeyOptions } from './useApiKeys.ts'
import type { ApiKey } from './apiKey.types.ts'
import { createApiKeyApi, apiKeyApi } from '../../api/api-key.api.ts'
import { createClients } from '../../api/client.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { Application } from '../applications/application.types.ts'
const scope = { applicationId: '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId: '41801983-8baa-4b0a-ba77-8df1f212668b' }
const key: ApiKey = { id: '51801983-8baa-4b0a-ba77-8df1f212668b', environment_id: scope.environmentId, key_prefix: '0123456789abcdef', status: 'ACTIVE', expires_at: null, created_at: '2026-10-30T00:00:00Z', revoked_at: null }
const replacement = { ...key, id: '61801983-8baa-4b0a-ba77-8df1f212668b' }, sentinel = key.key_prefix + '.' + 'S'.repeat(43)
const app: Application = { id: scope.applicationId, name: 'orders', description: null, status: 'ACTIVE', environments: [{ id: scope.environmentId, name: 'DEV', status: 'ACTIVE' }] }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', expires_in: 3600, token_type: 'Bearer' as const }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: 'actor', role }, unbind = bindSessionCache(client, store)
  const calls = { list: 0, create: 0, rotate: 0, revoke: 0 }
  const api: typeof apiKeyApi = { list: async () => { calls.list++; return { items: [key], next_cursor: null } },
    create: async (_scope, _values, consume) => { calls.create++; consume(sentinel); return key },
    rotate: async (_scope, _key, consume) => { calls.rotate++; consume(sentinel); return replacement },
    revoke: async () => { calls.revoke++; return { ...key, status: 'REVOKED', revoked_at: key.created_at } } }
  return { store, client, session, scope, api, calls, applications: { list: async () => ({ items: [app], next_cursor: null as string | null }) }, cleanup() { unbind(); client.clear() } }
}
test('confirmed key create/rotate/revoke retires exact env pages, refetches metadata and never returns plaintext', async () => {
  const f = await fixture(), action = createApiKeyCommands(f), target = apiKeyKeys.page(f.session, scope), other = apiKeyKeys.page(f.session, { ...scope, environmentId: 'other' })
  f.client.setQueryData(other, 'other environment'); let received = 0
  try {
    for (const command of [{ kind: 'create' as const }, { kind: 'rotate' as const, id: key.id }, { kind: 'revoke' as const, id: key.id }]) {
      f.client.setQueryData(target, 'old'); f.client.setQueryData(apiKeyKeys.page(f.session, scope, { cursor: 'old' }), 'old')
      const result = await action.execute(command, value => { assert.ok(value === sentinel); received++ })
      assert.deepEqual(result, { kind: 'confirmed', reconciled: true }); assert.ok(!JSON.stringify(result).includes(sentinel))
      assert.equal(f.client.getQueriesData({ queryKey: apiKeyKeys.root(f.session, scope) }).length, 0)
      await f.client.fetchQuery(apiKeyOptions(f.session, scope, null, f.api, f.store))
      assert.equal(f.client.getQueryData(other), 'other environment')
    }
    assert.equal(received, 2); assert.deepEqual([f.calls.create, f.calls.rotate, f.calls.revoke], [1, 1, 1])
  } finally { f.cleanup() }
})
test('double-submit joins one command, keeps old cache until confirmation and invokes the reveal consumer once', async () => {
  const f = await fixture(); let finish!: () => void, started!: () => void, received = 0
  const ready = new Promise<void>(resolve => { started = resolve })
  f.api.create = async (_scope, _values, consume) => { f.calls.create++; started(); await new Promise<void>(resolve => { finish = resolve }); consume(sentinel); return key }
  const action = createApiKeyCommands(f), target = apiKeyKeys.page(f.session, scope); f.client.setQueryData(target, 'before')
  try {
    const first = action.execute({ kind: 'create' }, () => { received++ }), second = action.execute({ kind: 'create' }, () => { received++ })
    assert.equal(first, second); await ready; assert.equal(f.client.getQueryData(target), 'before'); finish(); await first
    assert.equal(f.calls.create, 1); assert.equal(received, 1)
  } finally { f.cleanup() }
})
test('disabled scopes reject create/rotate but permit actual metadata read and revocation', async () => {
  const f = await fixture()
  const action = createApiKeyCommands({ ...f, applications: { list: async () => ({ items: [{ ...app, status: 'DISABLED', environments: [{ ...app.environments[0], status: 'DISABLED' }] }], next_cursor: null }) } })
  try {
    await assert.rejects(action.execute({ kind: 'create' }, () => {}), error => error instanceof ApiError && error.status === 409)
    await assert.rejects(action.execute({ kind: 'rotate', id: key.id }, () => {}), error => error instanceof ApiError && error.status === 409)
    assert.equal((await action.execute({ kind: 'revoke', id: key.id })).kind, 'confirmed')
    assert.deepEqual([f.calls.create, f.calls.rotate, f.calls.revoke], [0, 0, 1])
  } finally { f.cleanup() }
})
test('fresh metadata rejects expired/missing foreign target and roster loops before global key writes', async () => {
  const f = await fixture()
  try {
    for (const status of ['EXPIRED', 'REVOKED'] as const) {
      f.api.list = async () => ({ items: [{ ...key, status }], next_cursor: null })
      await assert.rejects(createApiKeyCommands(f).execute({ kind: 'rotate', id: key.id }, () => {}), ApiError)
    }
    f.api.list = async () => ({ items: [], next_cursor: null })
    await assert.rejects(createApiKeyCommands(f).execute({ kind: 'revoke', id: key.id }), error => error instanceof ApiError && error.status === 404)
    f.api.list = async () => ({ items: [], next_cursor: 'repeat' })
    await assert.rejects(createApiKeyCommands(f).execute({ kind: 'revoke', id: key.id }), error => error instanceof ApiError && error.status === 502)
    assert.equal(f.calls.revoke + f.calls.rotate, 0)
  } finally { f.cleanup() }
})
test('lost create/rotate replies reconcile metadata only and never recover, reveal or reissue a credential', async () => {
  for (const kind of ['create', 'rotate'] as const) {
    const f = await fixture(); let received = 0, writes = 0
    if (kind === 'create') f.api.create = async () => { writes++; throw new ApiError(0, 'Lost response.') }
    else f.api.rotate = async () => { writes++; throw new ApiError(502, 'Malformed response.') }
    try {
      const outcome = await createApiKeyCommands(f).execute(kind === 'create' ? { kind } : { kind, id: key.id }, () => { received++ })
      assert.deepEqual(outcome, { kind: 'uncertain', reconciled: true }); assert.equal(received, 0); assert.equal(writes, 1)
      assert.equal(f.calls.list, kind === 'create' ? 1 : 2)
    } finally { f.cleanup() }
  }
})
test('failed metadata reconciliation gates another issue until explicit GET succeeds', async () => {
  const f = await fixture(); let writes = 0, available = false
  f.api.create = async () => { writes++; throw new ApiError(503, 'Unavailable.') }
  f.api.list = async () => { if (!available) throw new ApiError(503, 'Unavailable.'); return { items: [], next_cursor: null } }
  const action = createApiKeyCommands(f)
  try {
    assert.deepEqual(await action.execute({ kind: 'create' }, () => {}), { kind: 'uncertain', reconciled: false })
    await assert.rejects(action.execute({ kind: 'create' }, () => {}), error => error instanceof ApiError && error.status === 409)
    assert.equal(action.needsReconciliation, true); available = true
    assert.deepEqual(await action.execute('check'), { kind: 'uncertain', reconciled: true }); assert.equal(writes, 1)
  } finally { f.cleanup() }
})
test('actual protected create/rotate/revoke401 join shared refresh for GET without POST/DELETE replay', async () => {
  for (const kind of ['create', 'rotate', 'revoke'] as const) {
    const f = await fixture(); let writes = 0, gets = 0, refreshes = 0, received = 0
    const transport = createClients(f.store, { adapter: async config => {
      assert.match(String(config.headers.get('Authorization')), /^Bearer /)
      if (config.method !== 'get') { writes++; throw new axios.AxiosError('Sensitive transport', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: { api_key: sentinel } }) }
      gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [key], next_cursor: null } } }
    } })
    transport.bindRefresh(async () => { refreshes++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
    try {
      const action = createApiKeyCommands({ ...f, api: createApiKeyApi(transport.apiClient), renew: transport.refresh })
      const outcome = await action.execute(kind === 'create' ? { kind } : { kind, id: key.id }, () => { received++ })
      assert.equal(outcome.kind, 'uncertain'); assert.equal(writes, 1); assert.equal(refreshes, 1); assert.equal(received, 0)
      assert.equal(gets, kind === 'create' ? 1 : 2); assert.ok(!JSON.stringify(outcome).includes(sentinel))
    } finally { f.cleanup() }
  }
})
test('definite rejections do not retry; forbidden clears only affected session/app metadata and related cache', async () => {
  for (const status of [400, 403, 404]) {
    const f = await fixture(); let writes = 0
    const own = apiKeyKeys.page(f.session, scope), other = apiKeyKeys.page({ ...f.session, generation: 99 }, scope)
    f.client.setQueryData(own, 'private'); f.client.setQueryData(other, 'other session')
    f.api.create = async () => { writes++; throw new ApiError(status, 'Rejected.') }
    try {
      await assert.rejects(createApiKeyCommands(f).execute({ kind: 'create' }, () => {}), error => error instanceof ApiError && error.status === status)
      assert.equal(writes, 1); if (status === 403) assert.equal(f.client.getQueryData(own), undefined)
      assert.equal(f.client.getQueryData(other), 'other session')
    } finally { f.cleanup() }
  }
})
test('cancelled scope/unmount and logout/new-generation delivery cannot reveal a late credential or report success', async () => {
  for (const retirement of ['cancel', 'logout', 'replace'] as const) {
    const f = await fixture(); let finish!: () => void, started!: () => void, received = 0
    const ready = new Promise<void>(resolve => { started = resolve })
    f.api.create = async (_scope, _values, consume) => { started(); await new Promise<void>(resolve => { finish = resolve }); consume(sentinel); return key }
    const action = createApiKeyCommands(f)
    try {
      const pending = action.execute({ kind: 'create' }, () => { received++ }); await ready
      if (retirement === 'cancel') action.cancel()
      else { f.store.getState().clear(); if (retirement === 'replace') await f.store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: 'actor', username: 'actor', role: 'ADMIN' } })) }
      finish(); await assert.rejects(pending, ApiError); assert.equal(received, 0); assert.equal(f.client.getQueryCache().getAll().length, 0)
    } finally { f.cleanup() }
  }
})
test('StrictMode-style cancellation leaves new command usable; Engineer role and logging-out deny before network', async () => {
  const f = await fixture(), engineer = await fixture('ENGINEER')
  try {
    const action = createApiKeyCommands(f); action.cancel()
    assert.equal((await action.execute({ kind: 'create' }, () => {})).kind, 'confirmed')
    await assert.rejects(createApiKeyCommands(engineer).execute({ kind: 'create' }, () => {}), error => error instanceof ApiError && error.status === 403)
    f.store.setState({ phase: 'logging-out' }); await assert.rejects(action.execute({ kind: 'create' }, () => {}), ApiError)
    assert.equal(engineer.calls.create, 0); assert.equal(f.calls.create, 1)
  } finally { f.cleanup(); engineer.cleanup() }
})
test('void-variable/void-result mutation leaves no credential in query/mutation state after direct consumer reveal', async () => {
  const f = await fixture(), action = createApiKeyCommands(f); let local: string | null = null
  const observer = new MutationObserver<void, Error, void>(f.client, { mutationFn: async () => { await action.execute({ kind: 'create' }, secret => { local = secret }) }, retry: false, gcTime: 0 })
  const unsubscribe = observer.subscribe(() => {})
  try {
    await observer.mutate(); assert.ok(local === sentinel)
    assert.equal(observer.getCurrentResult().data, undefined); assert.equal(observer.getCurrentResult().variables, undefined)
    const state = [...f.client.getQueryCache().getAll().map(query => query.state), ...f.client.getMutationCache().getAll().map(mutation => mutation.state)]
    assert.ok(!JSON.stringify(state).includes(sentinel)); local = null; assert.equal(local, null)
  } finally { unsubscribe(); observer.reset(); f.cleanup() }
})
test('write401 followed by failed shared renewal retains metadata-check gate', async () => {
  const f = await fixture(); let writes = 0
  f.api.create = async () => { writes++; throw new ApiError(401, 'Expired.') }
  const action = createApiKeyCommands({ ...f, renew: async () => { throw new ApiError(503, 'Unavailable.') } })
  try {
    await assert.rejects(action.execute({ kind: 'create' }, () => {}), ApiError); assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute({ kind: 'create' }, () => {}), error => error instanceof ApiError && error.status === 409)
    await action.execute('check'); assert.equal(writes, 1)
  } finally { f.cleanup() }
})

test('an issuing operation requires a reveal consumer and cannot silently create an unrecoverable credential', async () => {
  const f = await fixture()
  try {
    await assert.rejects(createApiKeyCommands(f).execute({ kind: 'create' }), error => error instanceof ApiError && error.status === 400)
    assert.equal(f.calls.create, 0)
  } finally { f.cleanup() }
})
