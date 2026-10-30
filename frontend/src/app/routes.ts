import type { Role } from '../features/auth/auth.types.ts'
export type Route = { page: 'login' | 'applications' | 'users' | 'not-found' | 'forbidden' } | { page: 'settings'; id: string } | { page: 'workspace'; id: string; view?: 'logs' | 'incidents' | 'analytics'; incidentId?: string }
export function parseRoute(hash: string): Route {
  if (hash === '' || hash === '#' || hash === '#/' || hash === '#/login') return { page: 'login' }
  if (hash === '#/applications') return { page: 'applications' }
  if (hash === '#/users') return { page: 'users' }
  const settings = /^#\/applications\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/settings$/i.exec(hash)
  if (settings) return { page: 'settings', id: settings[1] }
  const match = /^#\/applications\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/(overview|logs|incidents|analytics)(?:\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))?$/i.exec(hash)
  if (!match || (match[3] && match[2].toLowerCase() !== 'incidents')) return { page: 'not-found' }
  const view = match[2].toLowerCase()
  return { page: 'workspace', id: match[1], ...(view === 'logs' || view === 'incidents' || view === 'analytics' ? { view } : {}), ...(match[3] ? { incidentId: match[3] } : {}) }
}
export function routeFor(hash: string, role: Role | null): Route {
  if (!role) return { page: 'login' }
  const route = parseRoute(hash)
  if ((route.page === 'settings' || route.page === 'users') && role !== 'ADMIN') return { page: 'forbidden' }
  return route.page === 'login' ? { page: 'applications' } : route
}
export const workspacePath = (id: string) => `#/applications/${id}/overview`
export const settingsPath = (id: string) => `#/applications/${id}/settings`
