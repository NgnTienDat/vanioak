import type { Scope } from '../applications/application.keys.ts'
import type { ApiKeyParams, ApiKeyScope } from './apiKey.types.ts'
export const apiKeyKeys = {
  root: (session: Scope, scope: ApiKeyScope) => ['api-keys', session.generation, session.userId, session.role, scope.applicationId, scope.environmentId] as const,
  page: (session: Scope, scope: ApiKeyScope, params: ApiKeyParams = {}) => [...apiKeyKeys.root(session, scope), 'page', { cursor: params.cursor ?? null, limit: params.limit ?? 50 }] as const,
}
