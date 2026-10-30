import type { Scope } from '../applications/application.keys.ts'
import type { AlertRuleParams } from './alertRule.types.ts'
export const alertRuleKeys = {
  root: (scope: Scope) => ['alert-rules', scope.generation, scope.userId, scope.role] as const,
  page: (scope: Scope, params: AlertRuleParams = {}) => [...alertRuleKeys.root(scope), 'page', { cursor: params.cursor ?? null, limit: params.limit ?? 50 }] as const,
}
