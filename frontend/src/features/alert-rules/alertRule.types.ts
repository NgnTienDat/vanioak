export type AlertRule = { id: string; environment_id: string; level: 'ERROR' | 'CRITICAL'; threshold: number; window_seconds: number
  cooldown_seconds: number; resolve_after_seconds: number; enabled: boolean; created_at: string; updated_at: string }
export type AlertRulePage = { items: AlertRule[]; next_cursor: string | null }
export type AlertRuleParams = { cursor?: string | null; limit?: number }
export type CreateAlertRule = Pick<AlertRule, 'environment_id' | 'level' | 'threshold' | 'window_seconds' | 'cooldown_seconds' | 'resolve_after_seconds' | 'enabled'>
export type UpdateAlertRule = Partial<Omit<CreateAlertRule, 'environment_id' | 'level'>>
export type AlertRuleCommand = { kind: 'create'; values: CreateAlertRule } | { kind: 'edit'; id: string; values: UpdateAlertRule } | { kind: 'disable'; id: string }
