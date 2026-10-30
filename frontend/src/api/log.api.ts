import { z } from 'zod'
import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { malformed, ApiError } from '../lib/apiError.ts'
import type { LogParams, LogPage } from '../features/logs/log.types.ts'

const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
const timestamp = z.iso.datetime({ offset: true })
const pageSchema = z.object({
  items: z.array(z.object({
    event_id: uuid, application_id: uuid, environment_id: uuid, host_ip: z.string(),
    level: z.enum(['INFO', 'WARN', 'ERROR', 'CRITICAL']), message: z.string(),
    timestamp, trace_id: z.string(), metadata: z.record(z.string(), z.unknown()).optional(),
    received_at: timestamp, processed_at: timestamp,
  })), next_cursor: z.string().min(1).nullable(),
})
export function createLogApi(client: AxiosInstance) {
  return {
    async list(params: LogParams, signal?: AbortSignal): Promise<LogPage> {
      const { application_id, environment_id, level, from, to, trace_id, message, cursor } = params
      const limit = params.limit ?? 50
      if (!uuid.safeParse(application_id).success || (environment_id !== undefined && !uuid.safeParse(environment_id).success)
        || !timestamp.safeParse(from).success || !timestamp.safeParse(to).success
        || Date.parse(to) <= Date.parse(from) || Date.parse(to) - Date.parse(from) > 7 * 86400000
        || !Number.isInteger(limit) || limit < 1 || limit > 100
        || (level !== undefined && !['INFO','WARN','ERROR','CRITICAL'].includes(level))
        || (trace_id !== undefined && new TextEncoder().encode(trace_id).length > 1024)
        || (message !== undefined && new TextEncoder().encode(message).length > 4096)) {
        throw new ApiError(400, 'Invalid log filters or time range.')
      }
      const body = decodeEnvelope((await client.get('/logs', {
        params: { application_id, environment_id, level, from, to, trace_id, message, cursor: cursor ?? undefined, limit }, signal,
      })).data)
      const result = pageSchema.safeParse(body)
      if (!result.success) throw malformed()
      if (result.data.items.some(row => row.application_id !== application_id
        || (environment_id !== undefined && row.environment_id !== environment_id)
        || (level !== undefined && row.level !== level)
        || Date.parse(row.timestamp) < Date.parse(from) || Date.parse(row.timestamp) >= Date.parse(to))) throw malformed()
      return result.data
    },
  }
}
export const logApi = createLogApi(clients.apiClient)
