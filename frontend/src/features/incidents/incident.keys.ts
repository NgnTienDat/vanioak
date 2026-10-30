import type { Scope } from '../applications/application.keys.ts'
import type { IncidentParams, IncidentScope } from './incident.types.ts'

export const incidentKeys = {
  root: (session: Scope, scope: IncidentScope) => ['incidents', session.generation, session.userId, session.role, scope.applicationId, scope.environmentId] as const,
  list: (session: Scope, params: IncidentParams) => [...incidentKeys.root(session, { applicationId: params.application_id, environmentId: params.environment_id ?? null }),
    'list', { status: params.status ?? null, level: params.level ?? null, from: params.from ?? null, to: params.to ?? null, limit: params.limit ?? 50 }] as const,
  page: (session: Scope, params: IncidentParams, revision = 0) => [...incidentKeys.list(session, params), 'reconcile', revision, 'page', params.cursor ?? null] as const,
  detail: (session: Scope, scope: IncidentScope, id: string) => [...incidentKeys.root(session, scope), 'detail', id] as const,
}
