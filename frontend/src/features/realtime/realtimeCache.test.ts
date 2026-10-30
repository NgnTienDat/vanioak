import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRealtimeCache } from './realtimeCache.ts'
import { createQueryClient } from '../../lib/queryClient.ts'
import { logPageQuery, loadedLogs } from '../logs/useLogs.ts'
import type { RealtimeEvent } from './realtime.types.ts'
import { incidentKeys } from '../incidents/incident.keys.ts'

function fakeClock() {
  let now = 0, id = 0
  const tasks = new Map<number, { at: number; run: () => void }>()
  return { now: () => now, set: (run: () => void, ms: number) => { tasks.set(++id, { at: now + ms, run }); return id },
    clear: (key: unknown) => { tasks.delete(key as number) }, tasks,
    advance(ms: number) {
      const target = now + ms
      while (true) {
        const next = [...tasks.entries()].filter(([, task]) => task.at <= target).sort((a,b) => a[1].at - b[1].at)[0]
        if (!next) break
        now = next[1].at; tasks.delete(next[0]); next[1].run()
      }
      now = target
    },
  }
}
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }
const scope = { applicationId: 'app', environmentId: 'env' }
const event: RealtimeEvent = { type: 'log', data: { event_id: 'event', application_id: 'app', environment_id: 'env', host_ip: 'host',
  level: 'INFO', message: 'message', timestamp: '2026-10-29T09:00:00Z', trace_id: '' } }
const key = (domain: string, app = 'app', env: string | null = 'env', generation = 1) => [domain, generation, 'engineer', 'ENGINEER', app, env, 'page', null]

test('event bursts coalesce REST head invalidation at1s, analytics30s; never insert incomplete SSE rows', () => {
  const client = createQueryClient(), clock = fakeClock(); let reconciliations = 0
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, reconcileLogs: () => { reconciliations++ }, denied: () => {} })
  try {
    const original = { items: [], next_cursor: null }
    client.setQueryData(key('logs'), original); client.setQueryData(key('logs','other-app'), original); client.setQueryData(key('analytics'), original)
    for (let i = 0; i < 100; i++) cache.event(event)
    assert.equal(clock.tasks.size, 2)
    clock.advance(999); assert.equal(reconciliations, 0)
    clock.advance(1); assert.equal(reconciliations, 1)
    assert.deepEqual(client.getQueryData(key('logs')), original)
    assert.equal(client.getQueryState(key('logs'))?.isInvalidated, true)
    assert.equal(client.getQueryState(key('logs','other-app'))?.isInvalidated, false)
    assert.equal(client.getQueryState(key('analytics'))?.isInvalidated, false)
    clock.advance(29000); assert.equal(client.getQueryState(key('analytics'))?.isInvalidated, true)
    assert.equal(reconciliations, 1)
  } finally { cache.dispose(); client.clear() }
})
test('slow REST request is allowed to settle before a rolling-window reset, avoiding starvation', async () => {
  const client = createQueryClient(), clock = fakeClock(); let resets = 0, finish!: () => void
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, reconcileLogs: () => { resets++ }, denied: () => {} })
  try {
    const request = client.fetchQuery({ queryKey: key('logs'), queryFn: () => new Promise(resolve => { finish = () => resolve({ items: [] }) }) })
    cache.event(event); clock.advance(1000); assert.equal(resets, 0)
    finish(); await request
    clock.advance(1000); assert.equal(resets, 1)
  } finally { cache.dispose(); client.clear() }
})
test('403 removes all denied application caches, including ALL/other env, without touching other sessions/apps', () => {
  const client = createQueryClient(), clock = fakeClock(); let denied = false
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, reconcileLogs: () => {}, denied: value => { denied = value } })
  try {
    for (const domain of ['logs','incidents','analytics']) {
      for (const env of ['env','other-env',null]) client.setQueryData(key(domain,'app',env), 'sensitive')
      client.setQueryData(key(domain,'other-app'), 'preserved'); client.setQueryData(key(domain,'app','env',2), 'preserved')
    }
    cache.event(event); cache.forbidden()
    assert.equal(denied, true); assert.equal(clock.tasks.size, 0)
    for (const query of client.getQueryCache().getAll()) assert.equal(query.state.data, 'preserved')
    assert.equal(client.getQueryCache().getAll().length, 6)
    cache.reconcile(); assert.equal(denied, true)
    cache.reconcile(true); assert.equal(denied, false)
  } finally { cache.dispose(); client.clear() }
})
test('stale session, pause/disposal discard pending invalidations and incident payload never patches guessed scope', () => {
  const client = createQueryClient(), clock = fakeClock(); let current = true, resets = 0
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => current, reconcileLogs: () => { resets++ }, denied: () => {} })
  try {
    client.setQueryData(key('incidents'), { original: true })
    cache.event({ type: 'incident.updated', data: { incident_id: 'id', error_count: 3, last_seen_at: '2026-10-29T09:00:00Z' } })
    clock.advance(1000)
    assert.deepEqual(client.getQueryData(key('incidents')), { original: true })
    assert.equal(client.getQueryState(key('incidents'))?.isInvalidated, true)
    cache.event(event); cache.suspend(); assert.equal(clock.tasks.size, 0)
    cache.event(event); current = false; clock.advance(30000); assert.equal(resets, 0)
    cache.dispose(); assert.equal(clock.tasks.size, 0)
  } finally { cache.dispose(); client.clear() }
})
test('reconnect/resume marks REST stale, advances head revision and cannot reuse historical cursor chain', () => {
  const client = createQueryClient(), clock = fakeClock(); let revision = 0
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, reconcileLogs: () => { revision++ }, denied: () => {} })
  try {
    const params = { application_id: 'app', environment_id: 'env', from: 'from', to: 'to' }
    const old = logPageQuery(session, { ...params, cursor: 'historical' }, undefined, revision)
    client.setQueryData(old.queryKey, { items: [], next_cursor: 'older' })
    cache.reconcile()
    const head = logPageQuery(session, params, undefined, revision)
    assert.notDeepEqual(head.queryKey, old.queryKey); assert.equal(head.queryKey.at(-1), null)
    assert.equal(head.gcTime, 0); assert.equal(client.getQueryData(head.queryKey), undefined)
    assert.equal(client.getQueryState(old.queryKey)?.isInvalidated, true)
    assert.deepEqual(loadedLogs([{ items: [], next_cursor: null }], [null]).items, [])
  } finally { cache.dispose(); client.clear() }
})

test('three Incident SSE types coalesce list-head reset/detail invalidation without inserting history or altering log window', () => {
  const client = createQueryClient(), clock = fakeClock(); let revision = 0, logWindows = 0
  const target = { applicationId: 'app', environmentId: 'env' }
  const cache = createRealtimeCache(client, session, target, { clock, current: () => true, denied: () => {}, reconcileLogs: () => { logWindows++ }, reconcileIncidents: () => { revision++ } })
  const params = { application_id: 'app', environment_id: 'env' }
  const listKey = incidentKeys.page(session, params), detailKey = incidentKeys.detail(session, target, 'id')
  try {
    client.setQueryData(listKey, { items: [], next_cursor: null }); client.setQueryData(detailKey, { status: 'OPEN', events: [] })
    cache.event({ type: 'incident.created', data: { incident_id: 'id', application_id: 'app', environment_id: 'env', level: 'ERROR', status: 'OPEN', message: 'message', error_count: 2 } })
    cache.event({ type: 'incident.updated', data: { incident_id: 'id', error_count: 3, last_seen_at: '2026-10-29T09:00:00Z' } })
    cache.event({ type: 'incident.resolved', data: { incident_id: 'id', status: 'RESOLVED', resolved_at: '2026-10-29T09:00:00Z' } })
    assert.equal(clock.tasks.size, 1); clock.advance(1000)
    assert.equal(revision, 1); assert.equal(logWindows, 0)
    assert.equal(client.getQueryState(detailKey)?.isInvalidated, true)
    assert.deepEqual(client.getQueryData(detailKey), { status: 'OPEN', events: [] })
    assert.notDeepEqual(incidentKeys.page(session, params, revision), listKey)
    cache.reconcile(true); assert.equal(revision, 2)
  } finally { cache.dispose(); client.clear() }
})

test('Analytics SSE invalidation advances scoped REST snapshot at30s, not per event; reconnect reconciles immediately', () => {
  const client = createQueryClient(), clock = fakeClock(); let analyticsWindows = 0
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, denied: () => {}, reconcileLogs: () => {}, reconcileAnalytics: () => { analyticsWindows++ } })
  try {
    const original = { totals: { total: 10, errors: 1, critical: 0 } }
    client.setQueryData(key('analytics'), original); client.setQueryData(key('analytics','other-app'), original)
    for (let i = 0; i < 50; i++) cache.event(event)
    clock.advance(29999); assert.equal(analyticsWindows, 0)
    clock.advance(1); assert.equal(analyticsWindows, 1)
    assert.deepEqual(client.getQueryData(key('analytics')), original)
    assert.equal(client.getQueryState(key('analytics'))?.isInvalidated, true)
    assert.equal(client.getQueryState(key('analytics','other-app'))?.isInvalidated, false)
    cache.reconcile(true); assert.equal(analyticsWindows, 2)
    cache.event(event); cache.dispose(); clock.advance(30000); assert.equal(analyticsWindows, 2)
  } finally { cache.dispose(); client.clear() }
})

test('Analytics cadence lets a slow aggregate settle before replacing its snapshot and clears pending work on pause', async () => {
  const client = createQueryClient(), clock = fakeClock(); let windows = 0, finish!: () => void
  const cache = createRealtimeCache(client, session, scope, { clock, current: () => true, denied: () => {}, reconcileLogs: () => {}, reconcileAnalytics: () => { windows++ } })
  try {
    const request = client.fetchQuery({ queryKey: key('analytics'), queryFn: () => new Promise(resolve => { finish = () => resolve({ total: 10 }) }) })
    cache.event(event); clock.advance(30000); assert.equal(windows, 0)
    finish(); await request; clock.advance(1000); assert.equal(windows, 1)
    cache.event(event); cache.suspend(); clock.advance(30000); assert.equal(windows, 1)
  } finally { cache.dispose(); client.clear() }
})
