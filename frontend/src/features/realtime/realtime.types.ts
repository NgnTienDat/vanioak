import { z } from 'zod'
import { ApiError } from '../../lib/apiError.ts'

export type StreamScope = { applicationId: string; environmentId: string | null }
export type RealtimePhase = 'connecting' | 'live' | 'reconnecting' | 'paused' | 'disconnected' | 'forbidden' | 'unavailable'
export type RealtimeState = { phase: RealtimePhase; enabled: boolean; attempt: number; notice: string }
export type RealtimeView = RealtimeState & { toggle: () => void; retry: () => void }
export type RealtimeClock = { now: () => number; set: (fn: () => void, ms: number) => unknown; clear: (id: unknown) => void }
export const realtimeClock: RealtimeClock = { now: Date.now, set: (fn, ms) => setTimeout(fn, ms), clear: id => clearTimeout(id as ReturnType<typeof setTimeout>) }
export const streamId = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
const time = z.iso.datetime({ offset: true })
const count = z.number().int().nonnegative().safe()
const fields = {
  log: z.object({ event_id: streamId, application_id: streamId, environment_id: streamId,
    host_ip: z.string(), level: z.enum(['INFO', 'WARN', 'ERROR', 'CRITICAL']), message: z.string(),
    timestamp: time, trace_id: z.string(), metadata: z.record(z.string(), z.unknown()).optional() }),
  'incident.created': z.object({ incident_id: streamId, application_id: streamId, environment_id: streamId,
    level: z.enum(['ERROR', 'CRITICAL']), status: z.literal('OPEN'), message: z.string(), error_count: count }),
  'incident.updated': z.object({ incident_id: streamId, error_count: count, last_seen_at: time }),
  'incident.resolved': z.object({ incident_id: streamId, status: z.literal('RESOLVED'), resolved_at: time }),
}
export type RealtimeEvent = { [K in keyof typeof fields]: { type: K; data: z.infer<typeof fields[K]> } }[keyof typeof fields]
export const invalidStream = () => new ApiError(502, 'Realtime stream is invalid or out of sync. REST reconciliation is required.')
export function decodeRealtimeEvent(type: string, text: string, scope: StreamScope): RealtimeEvent | null {
  if (!Object.hasOwn(fields, type)) return null
  try {
    const schema = fields[type as keyof typeof fields]
    const result = schema.safeParse(JSON.parse(text))
    if (!result.success) throw invalidStream()
    const data = result.data
    if ('application_id' in data && (data.application_id !== scope.applicationId
      || (scope.environmentId !== null && data.environment_id !== scope.environmentId))) throw invalidStream()
    return { type, data } as RealtimeEvent
  } catch { throw invalidStream() }
}
