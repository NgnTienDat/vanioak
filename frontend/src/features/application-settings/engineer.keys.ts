import type { Scope } from '../applications/application.keys.ts'
import type { EngineerPageParams } from '../applications/application.types.ts'
export const engineerKeys = {
  root: (scope: Scope, applicationId: string) => ['assigned-engineers', scope.generation, scope.userId, scope.role, applicationId] as const,
  page: (scope: Scope, applicationId: string, params: EngineerPageParams = {}) => [...engineerKeys.root(scope, applicationId), 'page', { cursor: params.cursor ?? null, limit: params.limit ?? 50 }] as const,
}
