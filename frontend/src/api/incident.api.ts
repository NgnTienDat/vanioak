import { z } from 'zod'
import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { ApiError, malformed } from '../lib/apiError.ts'
import type { Incident, IncidentDetail, IncidentPage, IncidentParams, IncidentResolution, IncidentScope } from '../features/incidents/incident.types.ts'

const idSchema = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
const time = z.iso.datetime({ offset: true })
const count = z.number().int().nonnegative().safe()
const ref = z.object({ id: idSchema, name: z.string() })
const item = z.object({
  id: idSchema, application: ref, environment: ref, level: z.enum(['ERROR','CRITICAL']), status: z.enum(['OPEN','RESOLVED']),
  fingerprint: z.string(), message: z.string(), error_count: count, first_seen_at: time, last_seen_at: time,
  resolved_at: time.nullable().optional(),
})
const history = z.object({ id: idSchema, event_type: z.string(), event_at: time,
  error_count: count.nullable().optional(), message: z.string().nullable().optional(),
  metadata: z.record(z.string(), z.unknown()).nullable().optional() })
const detail = item.extend({ events: z.array(history).max(100) })
const page = z.object({ items: z.array(item), next_cursor: z.string().min(1).nullable() })
const resolution = z.object({ id: idSchema, status: z.literal('RESOLVED'), resolved_at: time.nullable(),
  resolution_source: z.enum(['MANUAL','AUTOMATIC']).nullable(), resolved_by_user_id: idSchema.nullable() })
function checkId(id: string) { if (!idSchema.safeParse(id).success) throw new ApiError(400, 'Invalid Incident identifier.') }
function checkScope(scope: IncidentScope) { checkId(scope.applicationId); if (scope.environmentId !== null) checkId(scope.environmentId) }
function inScope(incident: Incident, scope: IncidentScope) {
  return incident.application.id === scope.applicationId && (scope.environmentId === null || incident.environment.id === scope.environmentId)
}
export function createIncidentApi(client: AxiosInstance) {
  return {
    async list(params: IncidentParams, signal?: AbortSignal): Promise<IncidentPage> {
      const { application_id, environment_id, status, level, from, to, cursor } = params
      checkScope({ applicationId: application_id, environmentId: environment_id ?? null })
      const limit = params.limit ?? 50
      if (!Number.isInteger(limit) || limit < 1 || limit > 100 || (from !== undefined && !time.safeParse(from).success)
        || (to !== undefined && !time.safeParse(to).success) || (from && to && Date.parse(from) >= Date.parse(to))
        || (status !== undefined && status !== 'OPEN' && status !== 'RESOLVED')
        || (level !== undefined && level !== 'ERROR' && level !== 'CRITICAL')) throw new ApiError(400, 'Invalid Incident filters.')
      const body = decodeEnvelope((await client.get('/incidents', { params: { application_id, environment_id, status, level, from, to, cursor: cursor ?? undefined, limit }, signal })).data)
      const result = page.safeParse(body)
      if (!result.success || result.data.items.some(row => !inScope(row, { applicationId: application_id, environmentId: environment_id ?? null })
        || (status !== undefined && row.status !== status) || (level !== undefined && row.level !== level)
        || (from !== undefined && Date.parse(row.last_seen_at) < Date.parse(from))
        || (to !== undefined && Date.parse(row.last_seen_at) >= Date.parse(to)))) throw malformed()
      return result.data
    },
    async detail(id: string, scope: IncidentScope, signal?: AbortSignal): Promise<IncidentDetail> {
      checkId(id); checkScope(scope)
      const result = detail.safeParse(decodeEnvelope((await client.get(`/incidents/${id}`, { signal })).data))
      if (!result.success || result.data.id !== id) throw malformed()
      if (!inScope(result.data, scope)) throw new ApiError(403, 'This Incident is unavailable in the selected application/environment.')
      if (result.data.events.some((event, index, events) => index > 0 && (Date.parse(event.event_at) > Date.parse(events[index - 1].event_at)
        || (event.event_at === events[index - 1].event_at && event.id > events[index - 1].id)))) throw malformed()
      return result.data
    },
    async resolve(id: string, signal?: AbortSignal): Promise<IncidentResolution> {
      checkId(id)
      const result = resolution.safeParse(decodeEnvelope((await client.patch(`/incidents/${id}/status`, { status: 'RESOLVED' }, { signal })).data))
      if (!result.success || result.data.id !== id) throw malformed()
      const value = result.data
      if ((value.resolution_source === 'MANUAL' && (!value.resolved_at || !value.resolved_by_user_id))
        || (value.resolution_source === 'AUTOMATIC' && (!value.resolved_at || value.resolved_by_user_id !== null))
        || (value.resolution_source === null && value.resolved_by_user_id !== null)) throw malformed()
      return value
    },
  }
}
export const incidentApi = createIncidentApi(clients.apiClient)
