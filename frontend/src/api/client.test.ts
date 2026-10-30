import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createAuthStore } from '../store/authStore.ts'
import { createClients, decodeEnvelope } from './client.ts'
import { createAuthApi } from './auth.api.ts'
import { AxiosError } from 'axios'
import { apiBase } from './client.ts'
import type { AxiosAdapter } from 'axios'
import { ApiError } from '../lib/apiError.ts'

function adapterFor(fetcher: typeof fetch): AxiosAdapter {
  return async config => {
    const path = `${config.baseURL}${config.url}`
    const raw = await fetcher(path,{ method: config.method?.toUpperCase(), body: config.data, headers: config.headers.toJSON() as Record<string,string>, signal: config.signal as AbortSignal })
    const text = await raw.text()
    let data: unknown
    try { data = JSON.parse(text) } catch { data = text }
    const response = { data, status: raw.status, statusText: raw.statusText, headers: {}, config }
    if (!raw.ok) throw new AxiosError('private transport details',AxiosError.ERR_BAD_RESPONSE,config,undefined,response)
    return response
  }
}
function createSession(fetcher: typeof fetch, now: () => number = Date.now) {
  const store = createAuthStore(now), transport = createClients(store,{ adapter: adapterFor(fetcher), now })
  const auth = createAuthApi(transport.authClient)
  transport.bindRefresh(auth.refresh)
  return {
    login: (username: string,password: string) => store.getState().login({ username,password },auth.login),
    logout: () => store.getState().logout(transport.waitForRefresh,auth.logout),
    get: async (path: string) => decodeEnvelope((await transport.apiClient.get(path)).data),
    getSnapshot: () => { const { user,phase,notice } = store.getState(); return { user,phase,notice } },
  }
}
async function jsonRequest(path: string, options: { signal?: AbortSignal } = {}, fetcher: typeof fetch) {
  if (options.signal?.aborted) throw options.signal.reason
  const transport = createClients(createAuthStore(),{ adapter: adapterFor(fetcher) })
  return decodeEnvelope((await transport.authClient.get(path,{ signal: options.signal })).data)
}

const pair = (suffix = 'one') => ({ access_token: `access-${suffix}`, refresh_token: `refresh-${suffix}`, token_type: 'Bearer', expires_in: 60 })
const user = { id: 'user-1', username: 'test-user', role: 'ENGINEER' }
const ok = (data: unknown) => new Response(JSON.stringify({ success: true, message: 'Success', data }))
function deferred() { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const tick = () => new Promise<void>(resolve => setImmediate(resolve))

test('login accepts both actual roles, sends username/password and logout uses current tokens', async () => {
  for (const role of ['ADMIN','ENGINEER']) {
    const calls: { url: string; init?: RequestInit }[] = []
    const fake: typeof fetch = async (input,init) => { calls.push({ url: String(input), init }); return String(input).endsWith('/login') ? ok({ ...pair(), user: { ...user,role } }) : ok(null) }
    const session = createSession(fake)
    await session.login('test-user','synthetic-password')
    assert.equal(session.getSnapshot().user?.role,role)
    assert.deepEqual(JSON.parse(calls[0].init?.body as string),{ username: 'test-user', password: 'synthetic-password' })
    await session.logout()
    assert.equal(calls[1].url,'/api/v1/auth/logout')
    assert.equal((calls[1].init?.headers as Record<string,string>).Authorization,'Bearer access-one')
    assert.deepEqual(JSON.parse(calls[1].init?.body as string),{ refresh_token: 'refresh-one' })
    assert.equal(session.getSnapshot().user,null)
    assert.equal(createSession(fake).getSnapshot().user,null)
  }
})
test('unknown roles, invalid tokens and malformed envelopes fail closed without raw bodies', async () => {
  for (const data of [{ ...pair(), user: { ...user,role: 'OWNER' } }, { ...pair(), expires_in: 0,user }, { ...pair(), access_token: '',user }, { ...pair(),user: null }]) {
    const session = createSession(async () => ok(data))
    await assert.rejects(session.login('user','secret'),ApiError)
    assert.equal(session.getSnapshot().user,null)
  }
  for (const response of [new Response('<html>secret</html>'), new Response(JSON.stringify({ success: false, message: 'secret', data: null })), new Response('{}')]) {
    await assert.rejects(jsonRequest('/auth/login',{},async () => response),error => error instanceof ApiError && !error.message.includes('secret'))
  }
})
test('HTTP status and network failures have safe distinct diagnostics', async () => {
  for (const status of [400,401,403,500]) await assert.rejects(jsonRequest('/applications',{},async () => new Response('password=secret',{ status })),error => error instanceof ApiError && error.status === status && !error.message.includes('secret'))
  await assert.rejects(jsonRequest('/applications',{},async () => { throw new Error('token=secret') }),error => error instanceof ApiError && error.status === 0 && !error.message.includes('secret'))
})
test('local expiry renews one rotated pair and keeps login identity without inventing refresh user fields', async () => {
  let clock = 0
  const calls: string[] = []
  const session = createSession(async (input,init) => {
    calls.push(String(input))
    if (String(input).endsWith('/login')) return ok({ ...pair(),user })
    if (String(input).endsWith('/refresh')) { assert.deepEqual(JSON.parse(init?.body as string),{ refresh_token: 'refresh-one' }); return ok(pair('two')) }
    assert.equal((init?.headers as Record<string,string>).Authorization,'Bearer access-two'); return ok([])
  }, () => clock)
  await session.login('user','secret'); clock = 61000
  await session.get('/applications')
  assert.equal(calls.filter(p => p.endsWith('/refresh')).length,1); assert.deepEqual(session.getSnapshot().user,user)
})
test('simultaneous401 and delayed old-token401 cause only one refresh', { timeout: 2000 }, async () => {
  const renewal = deferred(), late = deferred(); let refreshes = 0, oldReads = 0
  const session = createSession(async (input,init) => {
    const url = String(input)
    if (url.endsWith('/login')) return ok({ ...pair(),user })
    if (url.endsWith('/refresh')) { refreshes++; return renewal.promise }
    if ((init?.headers as Record<string,string>).Authorization === 'Bearer access-one') {
      oldReads++; return oldReads === 3 ? late.promise : new Response('',{ status: 401 })
    }
    return ok([])
  })
  await session.login('user','secret')
  const reads = [session.get('/applications'),session.get('/applications'),session.get('/applications')]
  await tick(); assert.equal(refreshes,1); renewal.resolve(ok(pair('two'))); await tick()
  late.resolve(new Response('',{ status: 401 })); await Promise.all(reads); assert.equal(refreshes,1)
})
test('second401 ends session; protected403 never refreshes', async () => {
  for (const denied of [401,403]) {
    let refreshes = 0, reads = 0
    const session = createSession(async input => {
      if (String(input).endsWith('/login')) return ok({ ...pair(),user })
      if (String(input).endsWith('/refresh')) { refreshes++; return ok(pair('two')) }
      reads++; return new Response('',{ status: denied })
    })
    await session.login('user','secret'); await assert.rejects(session.get('/applications'))
    assert.equal(refreshes,denied === 401 ? 1 : 0); assert.equal(reads,denied === 401 ? 2 : 1)
    assert.equal(!!session.getSnapshot().user,denied === 403)
  }
})
test('refresh denial, malformed response and lost outcome clear session without replay', async () => {
  for (const outcome of ['401','403','malformed','lost']) {
    let rotations = 0
    const session = createSession(async input => {
      if (String(input).endsWith('/login')) return ok({ ...pair(),user })
      if (String(input).endsWith('/refresh')) {
        rotations++; if (outcome === 'lost') throw new Error('uncertain')
        return outcome === 'malformed' ? ok({}) : new Response('',{ status: Number(outcome) })
      }
      return new Response('',{ status: 401 })
    })
    await session.login('user','secret'); await assert.rejects(session.get('/applications')); await assert.rejects(session.get('/applications'))
    assert.equal(rotations,1); assert.equal(session.getSnapshot().user,null)
  }
})
test('logout waits for rotation, blocks new reads and sends exactly one logout with newest pair', { timeout: 2000 }, async () => {
  const rotation = deferred(); let logoutCalls = 0
  const session = createSession(async (input,init) => {
    if (String(input).endsWith('/login')) return ok({ ...pair(),user })
    if (String(input).endsWith('/refresh')) return rotation.promise
    if (String(input).endsWith('/logout')) {
      logoutCalls++; assert.equal((init?.headers as Record<string,string>).Authorization,'Bearer access-two')
      assert.deepEqual(JSON.parse(init?.body as string),{ refresh_token: 'refresh-two' }); return ok(null)
    }
    return new Response('',{ status: 401 })
  })
  await session.login('user','secret'); const read = session.get('/applications'); const rejected = assert.rejects(read)
  await tick(); const a = session.logout(), b = session.logout()
  await assert.rejects(session.get('/applications')); rotation.resolve(ok(pair('two')))
  await Promise.all([a,b,rejected]); assert.equal(logoutCalls,1); assert.equal(session.getSnapshot().user,null)
})
test('late login and late protected data cannot restore a logged-out session', async () => {
  const pending = deferred()
  const session = createSession(async () => pending.promise)
  const login = session.login('user','secret'); const rejected = assert.rejects(login)
  await session.logout(); pending.resolve(ok({ ...pair(),user })); await rejected; assert.equal(session.getSnapshot().user,null)
  const data = deferred()
  const next = createSession(async input => String(input).endsWith('/login') ? ok({ ...pair(),user }) : String(input).endsWith('/logout') ? ok(null) : data.promise)
  await next.login('user','secret'); const read = next.get('/applications'); const refused = assert.rejects(read)
  await next.logout(); data.resolve(ok(['foreign-data'])); await refused; assert.equal(next.getSnapshot().user,null)
})
test('logout network failure clears memory but reports unconfirmed server revocation', async () => {
  const session = createSession(async input => { if (String(input).endsWith('/login')) return ok({ ...pair(),user }); throw new Error('offline') })
  await session.login('user','secret'); await session.logout()
  assert.equal(session.getSnapshot().user,null); assert.match(session.getSnapshot().notice,/could not be confirmed/)
})
test('expired concurrent requests share renewal before issuing protected reads', async () => {
  let clock = 0, renewals = 0
  const session = createSession(async input => {
    if (String(input).endsWith('/login')) return ok({ ...pair(),user })
    if (String(input).endsWith('/refresh')) { renewals++; return ok(pair('two')) }
    return ok([])
  }, () => clock)
  await session.login('user','secret'); clock = 61000
  await Promise.all(Array.from({ length: 8 },() => session.get('/applications')))
  assert.equal(renewals,1)
})
test('abort does not call transport and does not leak raw errors or token state', async () => {
  const abort = new AbortController(); abort.abort(); let calls = 0
  await assert.rejects(jsonRequest('/applications',{ signal: abort.signal },async () => { calls++; return ok([]) }))
  assert.equal(calls,0)
  const session = createSession(async () => ok({ ...pair(),user }))
  await session.login('user','synthetic-password')
  assert.doesNotMatch(JSON.stringify(session.getSnapshot()),/access-one|refresh-one|synthetic-password/)
})
test('old-account data cannot escape after logout and a new login', async () => {
  const old = deferred(); let signins = 0
  const session = createSession(async input => {
    if (String(input).endsWith('/login')) return ok({ ...pair(),user: { ...user,id: `user-${++signins}` } })
    if (String(input).endsWith('/logout')) return ok(null)
    return old.promise
  })
  await session.login('first','secret'); const read = session.get('/applications'); const rejected = assert.rejects(read)
  await session.logout(); await session.login('second','secret'); old.resolve(ok(['first-account-data'])); await rejected
  assert.equal(session.getSnapshot().user?.id,'user-2')
})
test('protected mutations never refresh/replay and unsafe targets never receive a token', async () => {
  const store = createAuthStore(); let sends = 0, renewals = 0
  await store.getState().login({ username: 'x',password: 'x' },async () => ({ ...pair(),token_type: 'Bearer',user: { ...user,role: 'ENGINEER' } }))
  const transport = createClients(store,{ adapter: async config => {
    sends++; throw new AxiosError('password=secret',AxiosError.ERR_BAD_REQUEST,config,undefined,{ data: 'secret',status: 401,statusText: '',headers: {},config })
  } })
  transport.bindRefresh(async () => { renewals++; return { ...pair('two'),token_type: 'Bearer' } })
  await assert.rejects(transport.apiClient.post('/applications',{}),error => error instanceof ApiError && !JSON.stringify(error).includes('secret'))
  await assert.rejects(transport.apiClient.get('https://outside.test'))
  await assert.rejects(transport.apiClient.get('/auth/refresh'))
  assert.equal(sends,1); assert.equal(renewals,0)
  for (const value of ['https://outside.test','//outside.test','/api/v1?token=x']) assert.throws(() => apiBase(value))
})
test('cancelled waiter does not cancel shared refresh or send its replay', async () => {
  const store = createAuthStore(), rotation = deferred(); let refreshes = 0, replays = 0
  await store.getState().login({ username: 'x',password: 'x' },async () => ({ ...pair(),token_type: 'Bearer',user: { ...user,role: 'ENGINEER' } }))
  const transport = createClients(store,{ adapter: async config => {
    if (config.headers.get('Authorization') === 'Bearer access-one') throw new AxiosError('unauthorized',AxiosError.ERR_BAD_REQUEST,config,undefined,{ data: null,status: 401,statusText: '',headers: {},config })
    replays++; return { data: 'new',status: 200,statusText: 'OK',headers: {},config }
  } })
  transport.bindRefresh(async () => { refreshes++; const response = await rotation.promise; return response.json() })
  const abort = new AbortController()
  const first = transport.apiClient.get('/applications',{ signal: abort.signal }); const denied = assert.rejects(first)
  const second = transport.apiClient.get('/applications')
  await tick(); abort.abort(); rotation.resolve(new Response(JSON.stringify(pair('two'))))
  await denied; await second; assert.equal(refreshes,1); assert.equal(replays,1); assert.equal(store.getState().phase,'authenticated')
})
