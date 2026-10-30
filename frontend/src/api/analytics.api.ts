import { z } from 'zod'
import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { ApiError, malformed } from '../lib/apiError.ts'
import type { AnalyticsParams, HealthAnalytics } from '../features/analytics/analytics.types.ts'

const id = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
const timestamp = z.iso.datetime({ offset: true }), count = z.number().int().nonnegative().safe()
const response = z.object({ bucket: z.literal('hour'), items: z.array(z.object({
  application_id: id, environment_id: id, timestamp, total_logs: count, error_logs: count, critical_logs: count,
  log_error_rate: z.number().finite().min(0).max(1),
})).max(100000) })
export function createAnalyticsApi(client: AxiosInstance) {
  return {
    async health(params: AnalyticsParams, signal?: AbortSignal): Promise<HealthAnalytics> {
      const { application_id, environment_id, from, to } = params
      const start = Date.parse(from), end = Date.parse(to)
      if (!id.safeParse(application_id).success || (environment_id !== undefined && !id.safeParse(environment_id).success)
        || !timestamp.safeParse(from).success || !timestamp.safeParse(to).success || start >= end || end - start > 7 * 86400000
        || start < Date.parse('1900-01-01T00:00:00Z') || end >= Date.parse('2300-01-01T00:00:00Z')
        || (params.bucket !== undefined && params.bucket !== 'hour')) throw new ApiError(400, 'Invalid analytics scope or reporting window.')
      const value = response.safeParse(decodeEnvelope((await client.get('/analytics/health', { params: { application_id, environment_id, from, to, bucket: 'hour' }, signal })).data))
      if (!value.success) throw malformed()
      const seen = new Set<string>()
      for (const row of value.data.items) {
        const hour = Date.parse(row.timestamp), key = `${row.environment_id}:${hour}`
        const failed = row.error_logs + row.critical_logs
        const rate = row.total_logs ? failed / row.total_logs : 0
        if (row.application_id !== application_id || (environment_id !== undefined && row.environment_id !== environment_id)
          || hour % 3600000 !== 0 || hour < Math.floor(start / 3600000) * 3600000 || hour >= end
          || !Number.isSafeInteger(failed) || failed > row.total_logs || Math.abs(row.log_error_rate - rate) > 1e-12 || seen.has(key)) throw malformed()
        seen.add(key)
      }
      return value.data
    },
  }
}
export const analyticsApi = createAnalyticsApi(clients.apiClient)
