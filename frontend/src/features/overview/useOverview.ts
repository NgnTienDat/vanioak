import type { Workspace } from '../workspace/workspaceScope.ts'
import type { LogRecord } from '../logs/log.types.ts'
import type { Incident } from '../incidents/incident.types.ts'
import { useAnalytics } from '../analytics/useAnalytics.ts'
import { useLogs } from '../logs/useLogs.ts'
import { useIncidents } from '../incidents/useIncidents.ts'

export function overviewParams(workspace: Pick<Workspace, 'application' | 'scope' | 'analyticsWindow' | 'incidentWindow'>) {
  const base = { application_id: workspace.application.id, environment_id: workspace.scope.environmentId ?? undefined }
  return {
    analytics: { ...base, from: workspace.analyticsWindow.from, to: workspace.analyticsWindow.to, bucket: 'hour' as const },
    logs: { ...base, from: workspace.scope.from, to: workspace.scope.to, limit: 50 },
    incidents: { ...base, from: workspace.incidentWindow.from, to: workspace.incidentWindow.to, limit: 50 },
  }
}
export function overviewSamples(logs?: LogRecord[], incidents?: Incident[]) {
  return { logs: logs?.slice(0,6) ?? [], incidents: incidents?.slice(0,4) ?? [], loadedIncidentCount: incidents?.length ?? null,
    loadedOpenCount: incidents ? incidents.filter(item => item.status === 'OPEN').length : null }
}
export function useOverview(workspace: Workspace) {
  const params = overviewParams(workspace)
  const analytics = useAnalytics(params.analytics, workspace.analyticsRevision)
  const logs = useLogs(params.logs, workspace.reconciliation)
  const incidents = useIncidents(params.incidents, workspace.incidentReconciliation)
  return { analytics, logs, incidents, samples: overviewSamples(logs.error || (logs.loading && !logs.items.length) ? undefined : logs.items,
    incidents.error || (incidents.loading && !incidents.items.length) ? undefined : incidents.items),
    partial: !!(analytics.error || logs.error || incidents.error) }
}
