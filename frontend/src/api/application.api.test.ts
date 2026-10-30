import { test } from 'node:test'
import assert from 'node:assert/strict'
import { QueryObserver } from '@tanstack/react-query'
import { createApplicationApi, createApplicationAccessApi } from './application.api.ts'
import { createClients } from './client.ts'
import { createAuthStore } from '../store/authStore.ts'
import { bindSessionCache, createQueryClient } from '../lib/queryClient.ts'
import { resolveApplication } from '../features/applications/useApplications.ts'
import { applicationKeys } from '../features/applications/application.keys.ts'
import { ApiError } from '../lib/apiError.ts'
import axios from 'axios'
import type { Status } from '../types/api.types.ts'
function apiFor(session: { get: (path: string) => Promise<unknown> }) {
  const transport = createClients(createAuthStore(),{ adapter: async config => ({ data: { success: true, message: 'Success', data: await session.get(axios.getUri(config).replace('/api/v1','')) }, status: 200, statusText: 'OK', headers: {}, config }) })
  return createApplicationApi(transport.authClient)
}
function listApplications(session: { get: (path: string) => Promise<unknown> }, cursor: string | null = null, status?: Status) {
  return apiFor(session).list({ cursor,status })
}
async function findApplication(session: { get: (path: string) => Promise<unknown> }, id: string, signal = new AbortController().signal) {
  const client = createQueryClient()
  try { return await resolveApplication(client,{ generation: 1, userId: 'user',role: 'ENGINEER' },id,signal,apiFor(session)) }
  finally { client.clear() }
}

const app = { id: 'owned-id', name: 'owned-app', description: null, status: 'DISABLED', environments: [{ id: 'env-id', name: 'DEV', status: 'DISABLED' }] }
test('cursor/status map to real endpoint and nullable descriptions/disabled scopes remain readable', async () => {
  const session = { get: async (path: string) => {
    assert.equal(path,'/applications?limit=50&cursor=opaque%2B%3D&status=DISABLED')
    return { items: [app], next_cursor: null }
  } }
  assert.deepEqual(await listApplications(session,'opaque+=','DISABLED'),{ items: [app], next_cursor: null })
})
test('deep link lookup searches authorized pages without fallback or extra status filters', async () => {
  const paths: string[] = []
  const session = { get: async (path: string) => {
    paths.push(path); return paths.length === 1 ? { items: [], next_cursor: 'next' } : { items: [app], next_cursor: null }
  } }
  assert.deepEqual(await findApplication(session,app.id),app)
  assert.deepEqual(paths,['/applications?limit=50','/applications?limit=50&cursor=next'])
  await assert.rejects(findApplication({ get: async () => ({ items: [app], next_cursor: null }) },'foreign'),error => error instanceof ApiError && error.status === 403)
})
test('malformed DTOs, pagination loops and cancelled lookups fail closed', async () => {
  for (const data of [{}, { items: [ { ...app,status: 'UNKNOWN' } ], next_cursor: null }, { items: [ { ...app,environments: [{}] } ], next_cursor: null }, { items: [ { ...app,environments: [{ id: 'env',name: ['DEV'],status: 'ACTIVE' }] } ], next_cursor: null }, { items: [], next_cursor: 5 }]) await assert.rejects(listApplications({ get: async () => data }),ApiError)
  await assert.rejects(findApplication({ get: async () => ({ items: [], next_cursor: 'repeat' }) },'foreign'),ApiError)
  const abort = new AbortController(); abort.abort(); let calls = 0
  await assert.rejects(findApplication({ get: async () => { calls++; return {} } },'id',abort.signal)); assert.equal(calls,0)
})

const applicationId = '31801983-8baa-4b0a-ba77-8df1f212668b'
const actual = { ...app, id: applicationId, name: 'order-service', status: 'ACTIVE', environments: [{ id: '41801983-8baa-4b0a-ba77-8df1f212668b', name: 'DEV', status: 'ACTIVE' }] }
test('application writes use exact methods and separate detail/status JSON without owner or invented endpoints', async () => {
  const requests: { method?: string; url?: string; body: unknown }[] = []
  const transport = createClients(createAuthStore(), { adapter: async config => {
    const body = JSON.parse(config.data)
    requests.push({ method: config.method, url: config.url, body })
    return { config, status: config.method === 'post' ? 201 : 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { ...actual, ...(body.status ? { status: body.status } : {}) } } }
  } })
  const api = createApplicationApi(transport.authClient)
  assert.deepEqual(await api.create({ name: actual.name }), actual)
  await api.edit(applicationId, { description: '' })
  await api.status(applicationId, 'DISABLED')
  assert.deepEqual(requests, [
    { method: 'post', url: '/applications', body: { name: actual.name } },
    { method: 'patch', url: `/applications/${applicationId}`, body: { description: '' } },
    { method: 'patch', url: `/applications/${applicationId}`, body: { status: 'DISABLED' } },
  ])
})
test('application write validation rejects unsupported/null fields and invalid IDs before sending', async () => {
  let calls = 0
  const transport = createClients(createAuthStore(), { adapter: async () => { calls++; throw new Error('Must not send') } })
  const api = createApplicationApi(transport.authClient)
  await assert.rejects(api.create({ name: 'App', description: null } as never), ApiError)
  await assert.rejects(api.create({ name: 'App', owner: 'admin' } as never), ApiError)
  await assert.rejects(api.edit(applicationId, {}), ApiError)
  await assert.rejects(api.edit(applicationId, { name: 'App', status: 'DISABLED' } as never), ApiError)
  await assert.rejects(api.status('../foreign', 'ACTIVE'), ApiError)
  await assert.rejects(api.edit('not-a-uuid', { name: 'App' }), ApiError)
  assert.equal(calls, 0)
})
test('write response decoding rejects business failure, malformed fields, foreign ID and incorrect returned status', async () => {
  for (const data of [{ success: false, message: 'Failed', data: actual }, { success: true, message: 'OK', data: { ...actual, description: 42 } },
    { success: true, message: 'OK', data: { ...actual, id: 'foreign' } }, { success: true, message: 'OK', data: { ...actual, status: 'DISABLED' } }]) {
    const transport = createClients(createAuthStore(), { adapter: async config => ({ config, data, status: 200, statusText: 'OK', headers: {} }) })
    await assert.rejects(createApplicationApi(transport.authClient).status(applicationId, 'ACTIVE'), ApiError)
  }
  for (const data of [{ ...actual, id: 'invalid' }, { ...actual, environments: [{ ...actual.environments[0], id: 'invalid' }] }]) {
    const transport = createClients(createAuthStore(), { adapter: async config => ({ config, data: { success: true, message: 'OK', data }, status: 201, statusText: 'Created', headers: {} }) })
    await assert.rejects(createApplicationApi(transport.authClient).create({ name: actual.name }), ApiError)
  }
})

const engineerId = '51801983-8baa-4b0a-ba77-8df1f212668b'
test('assigned roster uses exact application path and bounded cursor params, preserves disabled users and projects only identity', async () => {
  const api = createApplicationAccessApi(axios.create({ adapter: async config => {
    assert.equal(axios.getUri(config), `/applications/${applicationId}/engineers?limit=50&cursor=opaque%2B%3D`)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [{ id: engineerId, username: 'engineer', status: 'DISABLED', extra: 'ignored' }], next_cursor: 'next' } } }
  } }))
  assert.deepEqual(await api.list(applicationId, { cursor: 'opaque+=' }), { items: [{ id: engineerId, username: 'engineer', status: 'DISABLED' }], next_cursor: 'next' })
})
test('grant/remove use POST/DELETE without invented body, and reject foreign action identities', async () => {
  const calls: string[] = []; let foreign = false
  const api = createApplicationAccessApi(axios.create({ adapter: async config => {
    calls.push(config.method!); assert.equal(config.url, `/applications/${applicationId}/engineers/${engineerId}`)
    assert.equal(config.data, undefined)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { application_id: foreign ? engineerId : applicationId, user_id: engineerId } } }
  } }))
  await api.grant(applicationId, engineerId); await api.remove(applicationId, engineerId)
  assert.deepEqual(calls, ['post', 'delete']); foreign = true
  await assert.rejects(api.grant(applicationId, engineerId), ApiError)
  await assert.rejects(api.remove(applicationId, engineerId), ApiError)
})
test('roster rejects malformed envelope, UUID/status/cursor and invalid scope/limit without requests', async () => {
  let payload: unknown, requests = 0
  const api = createApplicationAccessApi(axios.create({ adapter: async config => {
    requests++; return { config, status: 200, statusText: 'OK', headers: {}, data: payload }
  } }))
  for (const data of [{ items: [{ id: 'fake', username: 'x', status: 'ACTIVE' }], next_cursor: null }, { items: [{ id: engineerId, username: 'x', status: 'ADMIN' }], next_cursor: null }, { items: [], next_cursor: '' }, { items: [], next_cursor: 1 }]) {
    payload = { success: true, message: 'Success', data }; await assert.rejects(api.list(applicationId), ApiError)
  }
  payload = { success: false, message: 'Unsafe details', data: { items: [], next_cursor: null } }; await assert.rejects(api.list(applicationId), ApiError)
  const before = requests
  await assert.rejects(api.list('foreign'), ApiError); await assert.rejects(api.list(applicationId, { limit: 101 }), ApiError)
  await assert.rejects(api.grant(applicationId, 'fake'), ApiError); assert.equal(requests, before)
})

test('first application selection survives observer unmount/remount without Try again for Admin and Engineer', async () => {
  for (const role of ['ADMIN', 'ENGINEER'] as const) {
    const client = createQueryClient(), scope = { generation: 1, userId: 'actor', role }
    let finish!: () => void, requests = 0
    const response = new Promise<void>(resolve => { finish = resolve })
    const api = apiFor({ get: async () => { requests++; await response; return { items: [app], next_cursor: null } } })
    const signals: AbortSignal[] = []
    const observer = new QueryObserver(client, { queryKey: applicationKeys.selection(scope, app.id), queryFn: ({ signal }) => {
      signals.push(signal)
      return resolveApplication(client, scope, app.id, signal, api)
    } })
    const stopFirst = observer.subscribe(() => {})
    stopFirst()
    const stopActive = observer.subscribe(() => {})
    try {
      assert.equal(signals.length, 2)
      assert.equal(signals[0].aborted, true)
      finish()
      await new Promise<void>(resolve => setImmediate(resolve))
      assert.equal(observer.getCurrentResult().status, 'success')
      assert.deepEqual(observer.getCurrentResult().data, app)
      assert.equal(requests, 1)
    } finally { finish(); stopActive(); client.clear() }
  }
})

test('retired lookup cannot cancel a shared application page or continue its cursor traversal', async () => {
  const client = createQueryClient(), scope = { generation: 1, userId: 'actor', role: 'ADMIN' as const }
  const retired = new AbortController(), active = new AbortController()
  let finish!: () => void, requests = 0
  const response = new Promise<void>(resolve => { finish = resolve })
  const api = apiFor({ get: async () => { requests++; await response; return { items: [app], next_cursor: 'unread' } } })
  try {
    const previous = resolveApplication(client, scope, 'not-on-this-page', retired.signal, api)
    const cancelled = assert.rejects(previous, error => error instanceof ApiError && error.status === 499)
    retired.abort()
    const current = resolveApplication(client, scope, app.id, active.signal, api)
    finish()
    const results = await Promise.allSettled([cancelled, current])
    assert.equal(results[0].status, 'fulfilled')
    assert.deepEqual(results[1], { status: 'fulfilled', value: app })
    assert.equal(requests, 1)
  } finally { finish(); client.clear() }
})

test('session retirement still aborts nested application pages and prevents late cache restoration', async () => {
  const client = createQueryClient(), store = createAuthStore(), unbind = bindSessionCache(client, store)
  const scope = { generation: 0, userId: 'actor', role: 'ADMIN' as const }
  let finish!: () => void, pageSignal: { readonly aborted: boolean } | undefined
  const response = new Promise<void>(resolve => { finish = resolve })
  const api = createApplicationApi(axios.create({ adapter: async config => {
    pageSignal = config.signal
    await response
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [app], next_cursor: null } } }
  } }))
  try {
    const selection = client.fetchQuery({ queryKey: applicationKeys.selection(scope, app.id),
      queryFn: ({ signal }) => resolveApplication(client, scope, app.id, signal, api) })
    const cancelled = assert.rejects(selection)
    store.getState().clear()
    await cancelled
    assert.equal(pageSignal?.aborted, true)
    finish()
    await new Promise<void>(resolve => setImmediate(resolve))
    assert.equal(client.getQueryCache().getAll().length, 0)
  } finally { finish(); unbind(); client.clear() }
})
