import type { Scope } from '../applications/application.keys.ts'
import type { UserParams } from './user.types.ts'
export const userKeys = {
  root: (scope: Scope) => ['users', scope.generation, scope.userId, scope.role] as const,
  detail: (scope: Scope, id: string) => [...userKeys.root(scope), 'detail', id] as const,
  page: (scope: Scope, params: UserParams = {}) => [...userKeys.root(scope), 'page', { status: params.status ?? null, cursor: params.cursor ?? null, limit: params.limit ?? 50 }] as const,
}
