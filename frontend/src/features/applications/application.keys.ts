import type { AuthState } from '../../store/authStore.ts'
import type { ApplicationParams } from './application.types.ts'
export type Scope = { generation: number; userId: string; role: 'ADMIN' | 'ENGINEER' }
export function sessionScope(state: AuthState): Scope | null {
  return state.phase === 'authenticated' && state.user ? { generation: state.generation, userId: state.user.id, role: state.user.role } : null
}
export const applicationKeys = {
  root: (scope: Scope) => ['applications', scope.generation, scope.userId, scope.role] as const,
  page: (scope: Scope, params: ApplicationParams) => [...applicationKeys.root(scope), 'page', { status: params.status ?? null, cursor: params.cursor ?? null, limit: params.limit ?? 50 }] as const,
  selection: (scope: Scope, id: string) => [...applicationKeys.root(scope), 'selection', id] as const,
}
