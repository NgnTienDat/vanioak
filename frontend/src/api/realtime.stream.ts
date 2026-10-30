import { apiBase, clients } from './client.ts'
import { authStore } from '../store/authStore.ts'
import type { AuthStore } from '../store/authStore.ts'
import { ApiError, ended } from '../lib/apiError.ts'
import { createSseParser } from '../features/realtime/sseParser.ts'
import { decodeRealtimeEvent, realtimeClock, streamId } from '../features/realtime/realtime.types.ts'
import type { RealtimeClock, RealtimeEvent, StreamScope } from '../features/realtime/realtime.types.ts'

const debug = (...args: unknown[]) => {
  if (import.meta.env?.DEV) console.debug('[realtime]', ...args)
}

export type StreamCallbacks = {
  request: (revision: number | null) => void; open: () => void
  block: () => void; event: (event: RealtimeEvent) => void
}
function abortable<T>(work: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new ApiError(499, 'Realtime connection cancelled.'))
    if (signal.aborted) { abort(); void work.catch(() => {}); return }
    signal.addEventListener('abort', abort, { once: true })
    void work.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
  })
}
export function createRealtimeStream(options: {
  store?: AuthStore; auth?: Pick<typeof clients, 'refresh'>; fetch?: typeof fetch; base?: string; clock?: RealtimeClock
} = {}) {
  const store = options.store ?? authStore, auth = options.auth ?? clients
  const send = options.fetch ?? globalThis.fetch, clock = options.clock ?? realtimeClock
  const base = apiBase(options.base ?? import.meta.env?.VITE_API_BASE_URL ?? '/api/v1')
  return {
    async run(scope: StreamScope, generation: number, callbacks: StreamCallbacks, signal: AbortSignal): Promise<void> {
      const active = () => {
        if (signal.aborted) throw new ApiError(499, 'Realtime connection cancelled.')
        const state = store.getState()
        if (state.generation !== generation || state.phase !== 'authenticated' || !state.tokens) throw ended()
        return state
      }
      if (!streamId.safeParse(scope.applicationId).success || (scope.environmentId !== null && !streamId.safeParse(scope.environmentId).success)) throw new ApiError(400, 'Invalid realtime scope.')
      const params = new URLSearchParams({ application_id: scope.applicationId })
      if (scope.environmentId !== null) params.set('environment_id', scope.environmentId)
      let response: Response | undefined
      try {
        for (let attempt = 0; attempt < 2; attempt++) {
          let state = active()
          if (clock.now() >= state.expiresAt) { callbacks.request(null); await abortable(auth.refresh(), signal); state = active() }
          const revision = state.revision
          callbacks.request(revision)
          debug('connecting', { attempt: attempt + 1 })
          const pending = send(`${base}/realtime/stream?${params}`, {
            method: 'GET', headers: { Accept: 'text/event-stream', Authorization: `Bearer ${state.tokens!.access_token}` },
            credentials: 'omit', cache: 'no-store', redirect: 'error', signal,
          })
          // A cancelled transport must also retire a late response from a test adapter.
          void pending.then(value => { if (signal.aborted) void value.body?.cancel().catch(() => {}) }, () => {})
          response = await abortable(pending, signal)
          active()
          debug('response received', {
            status: response.status,
            contentType: response.headers.get('content-type') ?? '(missing)',
          })
          if (response.status !== 401) break
          void response.body?.cancel().catch(() => {})
          if (attempt === 1) {
            active(); store.getState().clear('Your session expired. Please sign in again.'); throw ended()
          }
          callbacks.request(null)
          if (active().revision === revision) await abortable(auth.refresh(), signal)
          active()
        }
        if (!response || response.status !== 200) {
          void response?.body?.cancel().catch(() => {})
          const status = response?.status ?? 0
          throw new ApiError(status, status === 403 ? 'Realtime access denied.' : status === 503 ? 'Realtime is unavailable.' : 'Realtime connection could not be established.')
        }
        if (!/^text\/event-stream(?:\s*;|\s*$)/i.test(response.headers.get('content-type') ?? '') || !response.body) {
          void response.body?.cancel().catch(() => {})
          throw new ApiError(502, 'Realtime response is not an event stream.')
        }
        const reader = response.body.getReader()
        const cancel = () => { void reader.cancel().catch(() => {}) }
        signal.addEventListener('abort', cancel, { once: true })
        try {
          active(); callbacks.open()
          debug('SSE stream opened; ready to receive events')
          const parser = createSseParser((type, data) => {
            active()
            const event = decodeRealtimeEvent(type, data, scope)
            if (event) debug('event received', { type })
            if (event) callbacks.event(event)
          }, () => {
            active()
            debug('SSE block completed')
            callbacks.block()
          })
          while (true) {
            const result = await reader.read()
            active()
            if (result.done) { parser.finish(); throw new ApiError(0, 'Realtime connection closed. REST reconciliation is required.') }
            parser.push(result.value)
          }
        } finally {
          signal.removeEventListener('abort', cancel)
          cancel(); reader.releaseLock()
        }
      } catch (error) {
        if (response?.body && !response.body.locked) void response.body.cancel().catch(() => {})
        debug('stream failed', { error: error instanceof Error ? error.message : 'unknown error' })
        if (error instanceof ApiError) throw error
        throw new ApiError(signal.aborted ? 499 : 0, signal.aborted ? 'Realtime connection cancelled.' : 'Realtime transport unavailable.')
      }
    },
  }
}
export const realtimeStream = createRealtimeStream()
