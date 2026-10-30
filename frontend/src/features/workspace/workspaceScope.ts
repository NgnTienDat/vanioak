import type { Application } from '../applications/application.types.ts'
import { ApiError } from '../../lib/apiError.ts'
import { createContext, useContext } from 'react'
import type { RealtimeView } from '../realtime/realtime.types.ts'

export type TimePreset = '1h' | '24h' | '7d'
export type WorkspaceScope = { environmentId: string | null; preset: TimePreset; from: string; to: string }
export type Workspace = {
  application: Application; scope: WorkspaceScope
  selectEnvironment: (id: string | null) => void; selectPreset: (preset: TimePreset) => void
  refreshWindow: () => void
  reconciliation: number; denied: boolean; realtime: RealtimeView
  incidentReconciliation: number; logSearch: string | null
  incidentWindow: WorkspaceScope; refreshIncidents: () => void
  analyticsWindow: WorkspaceScope; analyticsRevision: number; refreshAnalytics: () => void
  prepareLogSearch: (environmentId: string, message: string) => void
}
export const WorkspaceContext = createContext<Workspace | null>(null)
export function useWorkspace() { return useContext(WorkspaceContext) }
export function workspaceScope(application: Application, environmentId: string | null, preset: TimePreset, now = Date.now()): WorkspaceScope {
  if (environmentId !== null && !application.environments.some(env => env.id === environmentId)) {
    throw new ApiError(403, 'This environment is unavailable in the selected application.')
  }
  const hours = preset === '1h' ? 1 : preset === '24h' ? 24 : preset === '7d' ? 168 : 0
  if (!hours || !Number.isFinite(now)) throw new ApiError(400, 'Invalid reporting window.')
  return { environmentId, preset, from: new Date(now - hours * 3600000).toISOString(), to: new Date(now).toISOString() }
}
export function initialWorkspaceScope(application: Application, now = Date.now()) {
  return workspaceScope(application, application.environments.find(env => env.name === 'DEV')?.id
    ?? application.environments[0]?.id ?? null, '24h', now)
}
