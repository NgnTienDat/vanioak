import type { Scope } from '../applications/application.keys.ts'
import type { AnalyticsParams } from './analytics.types.ts'
export const analyticsKeys = {
  root: (session: Scope, params: AnalyticsParams) => ['analytics', session.generation, session.userId, session.role, params.application_id, params.environment_id ?? null] as const,
  health: (session: Scope, params: AnalyticsParams, revision = 0) => [...analyticsKeys.root(session, params), 'health', { from: params.from, to: params.to, bucket: 'hour' }, revision] as const,
}
