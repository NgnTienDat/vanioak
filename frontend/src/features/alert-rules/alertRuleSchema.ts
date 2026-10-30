import { z } from 'zod'
export const ruleNumbers = { threshold: z.number().int().min(1).max(2147483647), window_seconds: z.number().int().min(1).max(2147483647),
  cooldown_seconds: z.number().int().min(1).max(2147483647), resolve_after_seconds: z.number().int().min(1).max(2147483647) }
export const alertRuleSchema = z.object({ environment_id: z.uuid(), level: z.enum(['ERROR', 'CRITICAL']), ...ruleNumbers, enabled: z.boolean() }).strict()
export const alertRuleEditSchema = z.object({ ...ruleNumbers, enabled: z.boolean() }).partial().strict().refine(values => Object.values(values).some(value => value !== undefined), 'Change at least one supported field.')
