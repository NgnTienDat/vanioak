import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRealtimeStream } from './realtime.stream.ts'
import { createClients } from './client.ts'
import { createAuthStore } from '../store/authStore.ts'
import { ApiError } from '../lib/apiError.ts'
import type { StreamCallbacks } from './realtime.stream.ts'

const scope = { applicationId: '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId: '41801983-8baa-4b0a-ba77-8df1f212668b' }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', token_type: 'Bearer' as const, expires_in: 3600 }
const clock = { now: () => 1000, set: () => 0, clear: () => {} }
async function loggedIn() {
  const store = createAuthStore(clock.now)
  await store.getState().login({ username: 'engineer', password: 'synthetic' }, async () => ({ ...pair, user: { id: scope.applicationId, username: 'engineer', role: 'ENGINEER' } }))
  return store
}
const callbacks = (block = () => {}): StreamCallbacks => ({ request: () => {}, open: () => {}, event: () => {}, block })
const stopped = (error: unknown) => error instanceof ApiError && error.status === 499
const heartbeat = () => new Response(': heartbeat\n\n', { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } })

test('Fetch sends scoped Bearer header without cookies, level, token query, replay header or Axios buffering', async () => {
  const store = await loggedIn(); const abort = new AbortController()
  const stream = createRealtimeStream({ store, clock, fetch: async (url, init) => {
    assert.equal(url, `/api/v1/realtime/stream?application_id=${scope.applicationId}&environment_id=${scope.environmentId}`)
    assert.equal(init?.credentials, 'omit'); assert.equal(init?.redirect, 'error'); assert.equal(init?.signal, abort.signal)
    assert.deepEqual(init?.headers, { Accept: 'text/event-stream', Authorization: 'Bearer synthetic-access' })
    return heartbeat()
  } })
  await assert.rejects(stream.run(scope, store.getState().generation, callbacks(() => abort.abort()), abort.signal), stopped)
})
test('ALL omits environment_id; fragmented real log is delivered without manufactured REST fields', async () => {
  const store = await loggedIn(); const events: unknown[] = []
  const value = { event_id: scope.applicationId, application_id: scope.applicationId, environment_id: scope.environmentId,
    host_ip: 'host', level: 'ERROR', message: '🌳 lỗi', timestamp: '2026-10-29T09:00:00Z', trace_id: '' }
  const bytes = new TextEncoder().encode(`event: log\ndata: ${JSON.stringify(value)}\n\n`)
  const stream = createRealtimeStream({ store, clock, fetch: async url => {
    assert.ok(!String(url).includes('environment_id='))
    return new Response(new ReadableStream({ start(controller) { for (const byte of bytes) controller.enqueue(Uint8Array.of(byte)); controller.close() } }), { headers: { 'Content-Type': 'text/event-stream' } })
  } })
  await assert.rejects(stream.run({ ...scope, environmentId: null }, store.getState().generation, { ...callbacks(), event: event => events.push(event) }, new AbortController().signal), error => error instanceof ApiError && error.status === 0)
  assert.deepEqual(events, [{ type: 'log', data: value }])
})
test('simultaneous stream401 shares real existing refresh, and each GET retries only once', async () => {
  const store = await loggedIn(); const auth = createClients(store, { now: clock.now })
  let refreshes = 0, finish!: () => void, sends = 0
  auth.bindRefresh(async () => { refreshes++; await new Promise<void>(resolve => { finish = resolve }); return { ...pair, access_token: 'new-access', refresh_token: 'new-refresh' } })
  const stream = createRealtimeStream({ store, auth, clock, fetch: async (_url, init) => {
    sends++
    return (init?.headers as Record<string, string>).Authorization === 'Bearer synthetic-access' ? new Response('unused', { status: 401 }) : heartbeat()
  } })
  const aborts = [new AbortController(), new AbortController()]
  const runs = aborts.map(abort => assert.rejects(stream.run(scope, store.getState().generation, callbacks(() => abort.abort()), abort.signal), stopped))
  for (let i = 0; i < 20 && !finish; i++) await Promise.resolve()
  assert.equal(refreshes, 1); finish(); await Promise.all(runs)
  assert.equal(sends, 4); assert.equal(store.getState().tokens?.refresh_token, 'new-refresh')
})
test('second401 ends session without third GET; forbidden does not refresh or leak body/errors', async () => {
  for (const status of [401,403,503]) {
    const store = await loggedIn(); let calls = 0, renewals = 0
    const auth = createClients(store, { now: clock.now })
    auth.bindRefresh(async () => { renewals++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
    const stream = createRealtimeStream({ store, auth, clock, fetch: async () => { calls++; return new Response('SENSITIVE-BODY', { status }) } })
    await assert.rejects(stream.run(scope, store.getState().generation, callbacks(), new AbortController().signal), error => error instanceof ApiError && error.status === status && !error.message.includes('SENSITIVE'))
    assert.equal(calls, status === 401 ? 2 : 1); assert.equal(renewals, status === 401 ? 1 : 0)
    assert.equal(store.getState().phase, status === 401 ? 'anonymous' : 'authenticated')
  }
})
test('expired token renews before opening; cancellation while renewing does not cancel shared rotation', async () => {
  const store = await loggedIn(); const auth = createClients(store, { now: () => 4000000 })
  let finish!: () => void, sends = 0
  auth.bindRefresh(async () => { await new Promise<void>(resolve => { finish = resolve }); return { ...pair, access_token: 'renewed' } })
  const stream = createRealtimeStream({ store, auth, clock: { ...clock, now: () => 4000000 }, fetch: async () => { sends++; return heartbeat() } })
  const abort = new AbortController()
  const rejected = assert.rejects(stream.run(scope, store.getState().generation, callbacks(), abort.signal), stopped)
  abort.abort(); await rejected; finish(); await auth.waitForRefresh()
  assert.equal(sends, 0); assert.equal(store.getState().tokens?.access_token, 'renewed')
})
test('wrong content type, malformed event and incomplete terminal data close safely', async () => {
  for (const [body, contentType] of [['SENSITIVE-body','application/json'], ['event: log\ndata: {}\n\n','text/event-stream'], ['data: incomplete\n','text/event-stream']]) {
    const store = await loggedIn(); let liveBlocks = 0
    const stream = createRealtimeStream({ store, clock, fetch: async () => new Response(body, { headers: { 'Content-Type': contentType } }) })
    await assert.rejects(stream.run(scope, store.getState().generation, callbacks(() => liveBlocks++), new AbortController().signal), error => error instanceof ApiError && error.status === 502 && !error.message.includes('SENSITIVE'))
    assert.equal(liveBlocks, 0)
  }
})
test('aborting a pending reader retires it; late fetch response is also cancelled', async () => {
  const store = await loggedIn(); let cancel = 0, opened!: () => void
  const ready = new Promise<void>(resolve => { opened = resolve })
  const stream = createRealtimeStream({ store, clock, fetch: async () => new Response(new ReadableStream({ cancel() { cancel++ } }), { headers: { 'Content-Type': 'text/event-stream' } }) })
  const abort = new AbortController()
  const run = assert.rejects(stream.run(scope, store.getState().generation, { ...callbacks(), open: opened }, abort.signal), stopped)
  await ready; abort.abort(); await run; assert.equal(cancel, 1)
  let deliver!: (response: Response) => void
  const late = createRealtimeStream({ store, clock, fetch: () => new Promise(resolve => { deliver = resolve }) })
  const abortLate = new AbortController()
  const rejected = assert.rejects(late.run(scope, store.getState().generation, callbacks(), abortLate.signal), stopped)
  abortLate.abort(); await rejected
  deliver(new Response(new ReadableStream({ cancel() { cancel++ } })))
  await Promise.resolve(); assert.equal(cancel, 2)
})

test('delayed old-token401 reuses the already rotated pair rather than renewing a second time', async () => {
  const store = await loggedIn(); const auth = createClients(store, { now: clock.now })
  let renewals = 0, sends = 0, release!: () => void
  auth.bindRefresh(async () => { renewals++; return { ...pair, access_token: 'rotated' } })
  const abort = new AbortController()
  const stream = createRealtimeStream({ store, auth, clock, fetch: async () => {
    sends++
    if (sends === 1) { await new Promise<void>(resolve => { release = resolve }); return new Response(null, { status: 401 }) }
    return heartbeat()
  } })
  const result = assert.rejects(stream.run(scope, store.getState().generation, callbacks(() => abort.abort()), abort.signal), stopped)
  await auth.refresh(); release(); await result
  assert.equal(renewals, 1); assert.equal(sends, 2)
})
