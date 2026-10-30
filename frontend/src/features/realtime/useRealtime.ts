import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { realtimeStream } from '../../api/realtime.stream.ts'
import { ApiError } from '../../lib/apiError.ts'
import { createRealtimeCache } from './realtimeCache.ts'
import { realtimeClock } from './realtime.types.ts'
import type { RealtimeClock, RealtimeState, RealtimeView, StreamScope } from './realtime.types.ts'

const debug = (...args: unknown[]) => {
  if (import.meta.env?.DEV) console.debug('[realtime]', ...args)
}

const delays = [1000, 2000, 4000, 8000, 16000]
export const STABLE_STREAM_MS = 30000
export const IDLE_STREAM_MS = 45000
export function createRealtimeSession(options: {
  scope: StreamScope; cache: ReturnType<typeof createRealtimeCache>; state: (state: RealtimeState) => void
  store?: AuthStore; transport?: typeof realtimeStream; clock?: RealtimeClock
}) {
  const store = options.store ?? authStore, transport = options.transport ?? realtimeStream, clock = options.clock ?? realtimeClock
  const generation = store.getState().generation
  let disposed = false, enabled = false, attempt = 0, sequence = 0
  let controller: AbortController | null = null, work: Promise<void> = Promise.resolve(), sentRevision: number | null = null
  let timers = new Set<unknown>()
  const authenticated = () => store.getState().generation === generation && store.getState().phase === 'authenticated'
  const current = () => !disposed && enabled && authenticated()
  const notify = (phase: RealtimeState['phase'], notice = '') => {
    if (!disposed) options.state({ phase, enabled, attempt, notice })
  }
  function clearTimers() { for (const id of timers) clock.clear(id); timers = new Set() }
  function timer(fn: () => void, ms: number) {
    const id = clock.set(() => { timers.delete(id); fn() }, ms)
    timers.add(id); return id
  }
  function clearTimer(id: unknown) { if (id !== undefined) { clock.clear(id); timers.delete(id) } }
  function retire() {
    sequence++; controller?.abort(); controller = null; sentRevision = null; clearTimers(); options.cache.suspend()
    return work.catch(() => {})
  }
  function replace() {
    const previous = retire(), serial = sequence
    debug('replacing stream after authorization revision change')
    notify('reconnecting', 'Renewing the authorized stream.')
    void previous.then(() => { if (current() && sequence === serial) connect() })
  }
  function connect() {
    if (!current()) return
    const serial = ++sequence, owned = new AbortController()
    controller = owned
    let complete = false, stableElapsed = false, reconciled = false
    let idle: unknown, expiry: unknown
    const valid = () => current() && sequence === serial && !owned.signal.aborted
    let timeoutError: ApiError | null = null
    const handshake = timer(() => { timeoutError = new ApiError(0, 'Realtime connection timed out.'); owned.abort() }, 25000)
    const resetIdle = () => {
      clearTimer(idle)
      idle = timer(() => { timeoutError = new ApiError(0, 'Realtime heartbeat timed out.'); owned.abort() }, IDLE_STREAM_MS)
    }
    const operation = transport.run(options.scope, generation, {
      request(revision) {
        if (!valid()) return
        sentRevision = revision; clearTimer(expiry)
        if (revision !== null) expiry = timer(() => { if (valid()) replace() }, Math.min(2147483647, Math.max(0, store.getState().expiresAt - clock.now())))
      },
      open() {
        if (!valid()) return
        clearTimer(handshake); resetIdle()
        debug('connection established; awaiting first SSE block')
        timer(() => { stableElapsed = true; if (complete && valid()) attempt = 0 }, STABLE_STREAM_MS)
      },
      block() {
        if (!valid()) return
        complete = true; resetIdle()
        if (stableElapsed) attempt = 0
        debug('SSE block received; stream is live')
        notify('live')
        if (!reconciled) { reconciled = true; options.cache.reconcile(true) }
      },
      event(event) { if (valid()) options.cache.event(event) },
    }, owned.signal)
    work = operation.catch(error => {
      if (!current() || sequence !== serial || (owned.signal.aborted && !timeoutError)) return
      clearTimers(); sentRevision = null; controller = null
      const failure = timeoutError ?? (error instanceof ApiError ? error : new ApiError(0, 'Realtime transport unavailable.'))
      debug('connection attempt failed', { status: failure.status, message: failure.message })
      if (failure.status === 403) {
        options.cache.forbidden(); enabled = false; notify('forbidden', 'Realtime access denied. Scoped data has been cleared.'); return
      }
      options.cache.suspend(); options.cache.reconcile()
      if ([400, 401, 404].includes(failure.status)) {
        enabled = false; notify('unavailable', failure.message); return
      }
      if (attempt >= delays.length) {
        enabled = false; notify(failure.status === 503 ? 'unavailable' : 'disconnected', 'Automatic reconnect limit reached. Use Retry to reconcile and reconnect.'); return
      }
      const delay = delays[attempt++]
      debug('scheduling reconnect', { delayMs: delay, attempt })
      notify('reconnecting', `${failure.message} Retrying in ${delay / 1000}s (${attempt}/5).`)
      timer(() => { if (current() && sequence === serial) connect() }, delay)
    })
  }
  const unsubscribe = store.subscribe((state, previous) => {
    if (!authenticated()) { enabled = false; void retire(); notify('disconnected', 'Session ended.'); return }
    if (enabled && sentRevision !== null && state.revision !== previous.revision && state.revision !== sentRevision) replace()
  })
  return {
    start(after: Promise<void> = Promise.resolve()) {
      const previous = retire(), serial = sequence
      enabled = true; attempt = 0; notify('connecting')
      work = Promise.all([after, previous]).then(() => { if (current() && sequence === serial) connect() })
    },
    pause() { enabled = false; void retire(); notify('paused', 'Live updates paused. REST snapshot may be stale.') },
    dispose() { disposed = true; enabled = false; unsubscribe(); const retired = retire(); options.cache.dispose(); return retired },
  }
}

export function useRealtime(scope: StreamScope, callbacks: { reconcileLogs: () => void; denied: (value: boolean) => void; reconcileIncidents?: () => void; reconcileAnalytics?: () => void }): RealtimeView {
  const { applicationId, environmentId } = scope
  const client = useQueryClient()
  const generation = useStore(authStore, s => s.generation)
  const [state, setState] = useState<RealtimeState>({ phase: 'connecting', enabled: true, attempt: 0, notice: '' })
  const callbackRef = useRef(callbacks)
  const session = useRef<ReturnType<typeof createRealtimeSession> | null>(null)
  const retired = useRef(Promise.resolve())
  const wanted = useRef(true)
  useEffect(() => { callbackRef.current = callbacks }, [callbacks])
  useEffect(() => {
    const state = authStore.getState()
    if (state.phase !== 'authenticated' || !state.user) return
    const target = { applicationId, environmentId }
    const cache = createRealtimeCache(client, { generation, userId: state.user.id, role: state.user.role }, target, {
      current: () => authStore.getState().generation === generation && authStore.getState().phase === 'authenticated',
      reconcileLogs: () => callbackRef.current.reconcileLogs(), denied: value => callbackRef.current.denied(value),
      reconcileIncidents: () => callbackRef.current.reconcileIncidents?.(),
      reconcileAnalytics: () => callbackRef.current.reconcileAnalytics?.(),
    })
    const runtime = createRealtimeSession({ scope: target, cache, state: value => {
      if (!value.enabled && value.phase !== 'paused') wanted.current = false
      setState(value)
    } })
    session.current = runtime
    if (wanted.current) runtime.start(retired.current)
    else runtime.pause()
    return () => { retired.current = runtime.dispose(); if (session.current === runtime) session.current = null }
  }, [client, generation, applicationId, environmentId])
  return { ...state, toggle: () => {
    if (wanted.current) { wanted.current = false; session.current?.pause() }
    else { wanted.current = true; session.current?.start() }
  }, retry: () => { wanted.current = true; session.current?.start() } }
}
