import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRealtimeSession, STABLE_STREAM_MS, IDLE_STREAM_MS } from './useRealtime.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createClients } from '../../api/client.ts'
import { createRealtimeStream } from '../../api/realtime.stream.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { StreamCallbacks } from '../../api/realtime.stream.ts'
import type { RealtimeEvent, RealtimeState, StreamScope } from './realtime.types.ts'

const scope = { applicationId: '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId: '41801983-8baa-4b0a-ba77-8df1f212668b' }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', token_type: 'Bearer' as const, expires_in: 3600 }
async function settle() { for (let i = 0; i < 30; i++) await Promise.resolve() }
function fakeClock() {
  let now = 0, id = 0
  const tasks = new Map<number, { at: number; run: () => void }>()
  return { now: () => now, set: (run: () => void, ms: number) => { tasks.set(++id, { at: now + ms, run }); return id },
    clear: (key: unknown) => { tasks.delete(key as number) }, tasks,
    advance(ms: number) {
      const end = now + ms
      for (let count = 0; count < 1000; count++) {
        const next = [...tasks.entries()].filter(([, task]) => task.at <= end).sort((a,b) => a[1].at - b[1].at)[0]
        if (!next) break
        now = next[1].at; tasks.delete(next[0]); next[1].run()
      }
      now = end
    },
  }
}
async function fixture({ block = true, open = true, expires = 3600 } = {}) {
  const clock = fakeClock(), store = createAuthStore(clock.now)
  await store.getState().login({ username: 'engineer', password: 'synthetic' }, async () => ({ ...pair, expires_in: expires,
    user: { id: scope.applicationId, username: 'engineer', role: 'ENGINEER' } }))
  const states: RealtimeState[] = [], events: RealtimeEvent[] = []
  let reconciliations = 0, denials = 0, active = 0, maximum = 0
  const connections: { scope: StreamScope; callbacks: StreamCallbacks; signal: AbortSignal; close: (error?: ApiError) => void }[] = []
  const cache = { event: (event: RealtimeEvent) => { events.push(event) }, reconcile: () => { reconciliations++ },
    forbidden: () => { denials++ }, suspend: () => {}, dispose: () => {} }
  const transport = { run: (target: StreamScope, _generation: number, callbacks: StreamCallbacks, signal: AbortSignal) => {
    active++; maximum = Math.max(maximum, active)
    callbacks.request(store.getState().revision)
    if (open) callbacks.open()
    if (open && block) callbacks.block()
    return new Promise<void>((_resolve, reject) => {
      let done = false
      const close = (error = new ApiError(0, 'Connection closed.')) => {
        if (done) return
        done = true; active--; signal.removeEventListener('abort', abort); reject(error)
      }
      const abort = () => close(new ApiError(499, 'Cancelled.'))
      signal.addEventListener('abort', abort, { once: true })
      connections.push({ scope: target, callbacks, signal, close })
    })
  } }
  const make = (target: StreamScope = scope) => createRealtimeSession({ scope: target, cache, store, transport, clock, state: value => states.push(value) })
  return { clock, store, states, events, cache, transport, connections, make,
    get current() { return states.at(-1)! }, get reconciliations() { return reconciliations }, get denials() { return denials }, get active() { return active }, get maximum() { return maximum } }
}

test('bounded retry uses exactly1/2/4/8/16s; briefly opened HTTP never resets outage budget', async () => {
  const f = await fixture(); const runtime = f.make()
  try {
    runtime.start(); await settle()
    for (const [i, delay] of [1000,2000,4000,8000,16000].entries()) {
      f.connections.at(-1)!.close(); await settle()
      assert.equal(f.current.phase, 'reconnecting'); assert.equal(f.current.attempt, i + 1)
      const before = f.connections.length
      f.clock.advance(delay - 1); await settle(); assert.equal(f.connections.length, before)
      f.clock.advance(1); await settle(); assert.equal(f.connections.length, before + 1)
    }
    f.connections.at(-1)!.close(); await settle()
    assert.equal(f.current.phase, 'disconnected'); assert.equal(f.current.enabled, false)
    assert.equal(f.connections.length, 6); assert.equal(f.clock.tasks.size, 0)
    runtime.start(); await settle(); assert.equal(f.current.attempt, 0); assert.equal(f.connections.length, 7)
  } finally { await runtime.dispose() }
})
test('budget resets only after30s still open with a complete block, not headers alone', async () => {
  for (const hasBlock of [false, true]) {
    const f = await fixture({ block: hasBlock }); const runtime = f.make()
    try {
      runtime.start(); await settle(); f.connections[0].close(); await settle()
      f.clock.advance(1000); await settle()
      assert.equal(f.current.attempt, 1)
      f.clock.advance(STABLE_STREAM_MS); await settle()
      f.connections.at(-1)!.close(); await settle()
      assert.equal(f.current.attempt, hasBlock ? 1 : 2)
    } finally { await runtime.dispose() }
  }
})
test('pause/resume aborts reader and timers, reconciles on resume and never overlaps connections', async () => {
  const f = await fixture(); const runtime = f.make()
  try {
    runtime.start(); await settle(); const first = f.connections[0]
    runtime.pause(); await settle()
    assert.equal(first.signal.aborted, true); assert.equal(f.active, 0); assert.equal(f.current.phase, 'paused'); assert.equal(f.clock.tasks.size, 0)
    const before = f.reconciliations
    runtime.start(); await settle()
    assert.equal(f.active, 1); assert.equal(f.maximum, 1); assert.ok(f.reconciliations > before)
  } finally { await runtime.dispose() }
})
test('StrictMode setup-cleanup-setup and scope replacement wait for retirement; stale callbacks cannot update cache', async () => {
  const f = await fixture(); const first = f.make()
  first.start(); const retired = first.dispose()
  const second = f.make(); second.start(retired); await settle()
  assert.equal(f.connections.length, 1); assert.equal(f.maximum, 1)
  const old = f.connections[0]
  const previous = second.dispose(), third = f.make({ ...scope, environmentId: null })
  third.start(previous); await settle()
  try {
    assert.equal(old.signal.aborted, true); assert.equal(f.connections.at(-1)!.scope.environmentId, null); assert.equal(f.maximum, 1)
    const before = f.reconciliations
    old.callbacks.block(); old.callbacks.event({ type: 'incident.resolved', data: { incident_id: 'old', status: 'RESOLVED', resolved_at: '2026-10-29T09:00:00Z' } })
    assert.equal(f.reconciliations, before); assert.equal(f.events.length, 0)
  } finally { await third.dispose() }
})
test('403 stops without retry, malformed stream is never silently Live, idle/handshake reads are bounded', async () => {
  for (const status of [403,502,400,404]) {
    const f = await fixture({ block: false }); const runtime = f.make()
    try {
      runtime.start(); await settle(); f.connections[0].close(new ApiError(status, 'Safe failure.')); await settle()
      assert.notEqual(f.current.phase, 'live')
      if (status === 403) { assert.equal(f.denials, 1); assert.equal(f.current.phase, 'forbidden'); assert.equal(f.clock.tasks.size, 0) }
      if (status === 400 || status === 404) { assert.equal(f.current.phase, 'unavailable'); assert.equal(f.clock.tasks.size, 0) }
    } finally { await runtime.dispose() }
  }
  for (const open of [false,true]) {
    const f = await fixture({ open, block: false }); const runtime = f.make()
    try {
      runtime.start(); await settle(); f.clock.advance(open ? IDLE_STREAM_MS : 25000); await settle()
      assert.equal(f.connections[0].signal.aborted, true); assert.equal(f.current.phase, 'reconnecting')
    } finally { await runtime.dispose() }
  }
})
test('logout/account generation immediately aborts and token revision replaces only the old connection', async () => {
  const f = await fixture(); const runtime = f.make()
  try {
    runtime.start(); await settle(); const first = f.connections[0]
    f.store.getState().install(f.store.getState().generation, { ...pair, access_token: 'rotated' }); await settle()
    assert.equal(first.signal.aborted, true); assert.equal(f.connections.length, 2); assert.equal(f.maximum, 1)
    f.store.getState().clear(); await settle()
    assert.equal(f.active, 0); assert.equal(f.current.phase, 'disconnected'); assert.equal(f.clock.tasks.size, 0)
  } finally { await runtime.dispose() }
})
test('actual expiry joins existing refresh and retires old reader before opening new Bearer stream', async () => {
  const f = await fixture({ expires: 1 }); let renewals = 0, active = 0, maximum = 0
  const auth = createClients(f.store, { now: f.clock.now })
  auth.bindRefresh(async () => { renewals++; return { ...pair, access_token: 'rotated' } })
  const transport = createRealtimeStream({ store: f.store, auth, clock: f.clock, fetch: async (_url, init) => {
    active++; maximum = Math.max(maximum, active)
    const expected = renewals ? 'Bearer rotated' : 'Bearer synthetic-access'
    assert.equal((init?.headers as Record<string,string>).Authorization, expected)
    return new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(': heartbeat\n\n')) }, cancel() { active-- } }), { headers: { 'Content-Type': 'text/event-stream' } })
  } })
  const runtime = createRealtimeSession({ scope, cache: f.cache, store: f.store, clock: f.clock, transport, state: value => f.states.push(value) })
  try {
    runtime.start(); await settle(); assert.equal(active, 1)
    f.clock.advance(1000); await settle()
    assert.equal(renewals, 1); assert.equal(active, 1); assert.equal(maximum, 1); assert.equal(f.current.phase, 'live')
  } finally { await runtime.dispose() }
  assert.equal(active, 0); assert.equal(f.clock.tasks.size, 0)
})

test('rapid scope changes retain the ancestor retirement barrier while a reader is still draining', async () => {
  const f = await fixture(); let opens = 0, release!: () => void
  const transport = { run: (_scope: StreamScope, _generation: number, callbacks: StreamCallbacks, signal: AbortSignal) => {
    opens++; callbacks.request(f.store.getState().revision); callbacks.open()
    return new Promise<void>((_resolve, reject) => {
      signal.addEventListener('abort', () => {
        if (opens === 1) release = () => reject(new ApiError(499, 'Cancelled.'))
        else reject(new ApiError(499, 'Cancelled.'))
      }, { once: true })
    })
  } }
  const make = () => createRealtimeSession({ scope, store: f.store, clock: f.clock, cache: f.cache, transport, state: () => {} })
  const first = make(); first.start(); await settle()
  const previous = first.dispose(), second = make(); second.start(previous)
  const next = second.dispose(), third = make(); third.start(next)
  await settle(); assert.equal(opens, 1)
  release(); await settle(); assert.equal(opens, 2)
  await third.dispose()
})
test('logging-out phase aborts immediately before revocation completes, without waiting for generation change', async () => {
  const f = await fixture(); const runtime = f.make()
  runtime.start(); await settle(); let finish!: () => void
  const generation = f.store.getState().generation
  const logout = f.store.getState().logout(() => new Promise<void>(resolve => { finish = resolve }), async () => {})
  await settle()
  assert.equal(f.store.getState().generation, generation); assert.equal(f.store.getState().phase, 'logging-out')
  assert.equal(f.active, 0); assert.equal(f.clock.tasks.size, 0); assert.equal(f.current.enabled, false)
  finish(); await logout; await runtime.dispose()
})
