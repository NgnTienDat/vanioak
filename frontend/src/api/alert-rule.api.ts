import type { AxiosInstance } from 'axios'
import { z } from 'zod'
import { clients, decodeEnvelope } from './client.ts'
import { ApiError, malformed, normalizeApiError } from '../lib/apiError.ts'
import { alertRuleSchema, alertRuleEditSchema, ruleNumbers } from '../features/alert-rules/alertRuleSchema.ts'
import type { AlertRule, AlertRulePage, AlertRuleParams, CreateAlertRule, UpdateAlertRule } from '../features/alert-rules/alertRule.types.ts'
const dto = z.object({ id: z.uuid(), environment_id: z.uuid(), level: z.enum(['ERROR', 'CRITICAL']), ...ruleNumbers, enabled: z.boolean(), created_at: z.iso.datetime({ offset: true }), updated_at: z.iso.datetime({ offset: true }) })
function decode(value: unknown, expected?: Pick<AlertRule, 'id' | 'environment_id' | 'level'>): AlertRule {
  const parsed = dto.safeParse(value)
  if (!parsed.success || (expected && (parsed.data.id !== expected.id || parsed.data.environment_id !== expected.environment_id || parsed.data.level !== expected.level))) throw malformed()
  return parsed.data
}
function identifier(id: string) { if (!z.uuid().safeParse(id).success) throw new ApiError(400, 'Select a valid rule.'); return id }
export function createAlertRuleApi(client: AxiosInstance) {
  return {
    async list(params: AlertRuleParams = {}, signal?: AbortSignal): Promise<AlertRulePage> {
      try {
        const limit = params.limit ?? 50
        if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'Select a valid page size.')
        const data = decodeEnvelope((await client.get('/alert-rules', { params: { limit, cursor: params.cursor ?? undefined }, signal })).data)
        const page = z.object({ items: z.array(z.unknown()).max(limit), next_cursor: z.string().min(1).nullable() }).safeParse(data)
        if (!page.success) throw malformed()
        return { items: page.data.items.map(value => decode(value)), next_cursor: page.data.next_cursor }
      } catch (error) { throw normalizeApiError(error) }
    },
    async create(values: CreateAlertRule, signal?: AbortSignal): Promise<AlertRule> {
      const parsed = alertRuleSchema.safeParse(values)
      if (!parsed.success) throw new ApiError(400, 'Check the rule scope and positive integer fields.')
      try {
        const rule = decode(decodeEnvelope((await client.post('/alert-rules', parsed.data, { signal })).data))
        if (rule.environment_id !== values.environment_id || rule.level !== values.level) throw malformed()
        return rule
      } catch (error) { throw normalizeApiError(error) }
    },
    async edit(rule: AlertRule, values: UpdateAlertRule, signal?: AbortSignal): Promise<AlertRule> {
      const parsed = alertRuleEditSchema.safeParse(values)
      if (!parsed.success) throw new ApiError(400, 'Change only supported numeric fields or enabled status.')
      try { return decode(decodeEnvelope((await client.patch(`/alert-rules/${identifier(rule.id)}`, parsed.data, { signal })).data), rule) }
      catch (error) { throw normalizeApiError(error) }
    },
    async disable(rule: AlertRule, signal?: AbortSignal): Promise<AlertRule> {
      try {
        const result = decode(decodeEnvelope((await client.delete(`/alert-rules/${identifier(rule.id)}`, { signal })).data), rule)
        if (result.enabled) throw malformed()
        return result
      } catch (error) { throw normalizeApiError(error) }
    },
  }
}
export const alertRuleApi = createAlertRuleApi(clients.apiClient)
