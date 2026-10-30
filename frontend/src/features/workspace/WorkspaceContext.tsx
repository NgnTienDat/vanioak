import { useState } from 'react'
import type { ReactNode } from 'react'
import type { Application } from '../applications/application.types.ts'
import { initialWorkspaceScope, workspaceScope, WorkspaceContext } from './workspaceScope.ts'
import type { TimePreset } from './workspaceScope.ts'
import { useRealtime } from '../realtime/useRealtime.ts'

export function WorkspaceProvider({ application, children }: { application: Application; children: ReactNode }) {
  const [scope, setScope] = useState(() => initialWorkspaceScope(application))
  const [reconciliation, setReconciliation] = useState(0)
  const [deniedScope, setDeniedScope] = useState<string | null>(null)
  const [incidentReconciliation, setIncidentReconciliation] = useState(0)
  const [incidentWindow, setIncidentWindow] = useState(scope)
  const [logSearch, setLogSearch] = useState<string | null>(null)
  const [analyticsWindow, setAnalyticsWindow] = useState(scope)
  const [analyticsRevision, setAnalyticsRevision] = useState(0)
  const change = (id: string | null, preset: TimePreset) => setScope(workspaceScope(application, id, preset))
  const identity = `${application.id}:${scope.environmentId ?? 'ALL'}`
  const refreshIncidents = () => { setIncidentWindow(workspaceScope(application, scope.environmentId, scope.preset)); setIncidentReconciliation(value => value + 1) }
  const refreshAnalytics = () => { setAnalyticsWindow(workspaceScope(application, scope.environmentId, scope.preset)); setAnalyticsRevision(value => value + 1) }
  const realtime = useRealtime({ applicationId: application.id, environmentId: scope.environmentId }, {
    reconcileLogs: () => { change(scope.environmentId, scope.preset); setReconciliation(value => value + 1) },
    denied: value => setDeniedScope(value ? identity : null),
    reconcileIncidents: refreshIncidents,
    reconcileAnalytics: refreshAnalytics,
  })
  return <WorkspaceContext.Provider value={{ application, scope,
    selectEnvironment: id => { setLogSearch(null); change(id, scope.preset); setIncidentWindow(workspaceScope(application, id, scope.preset)); setAnalyticsWindow(workspaceScope(application, id, scope.preset)) },
    selectPreset: preset => { setLogSearch(null); change(scope.environmentId, preset); setIncidentWindow(workspaceScope(application, scope.environmentId, preset)); setAnalyticsWindow(workspaceScope(application, scope.environmentId, preset)) },
    refreshWindow: () => change(scope.environmentId, scope.preset),
    reconciliation, denied: deniedScope === identity, realtime,
    incidentReconciliation, logSearch, incidentWindow, refreshIncidents,
    analyticsWindow, analyticsRevision, refreshAnalytics,
    prepareLogSearch: (environmentId, message) => { change(environmentId, scope.preset); setIncidentWindow(workspaceScope(application, environmentId, scope.preset)); setAnalyticsWindow(workspaceScope(application, environmentId, scope.preset)); setLogSearch(message) },
  }}>{children}</WorkspaceContext.Provider>
}
