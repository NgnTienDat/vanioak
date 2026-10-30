import { useState } from 'react'
import { useWorkspace } from '../workspace/workspaceScope.ts'
import { useOverview } from './useOverview.ts'
import { Kpi } from '../../components/ui/Kpi.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { HourlyActivityChart } from '../../components/charts/HourlyActivityChart.tsx'
import { LogTable } from '../logs/LogTable.tsx'
import { LogDetailDrawer } from '../logs/LogDetailDrawer.tsx'
import { IncidentTable } from '../incidents/IncidentTable.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { LiveStatus } from '../realtime/LiveStatus.tsx'

export function OverviewPage() {
  const workspace = useWorkspace()!
  if (workspace.denied) return <><div className="page-heading"><h1>Overview</h1><LiveStatus realtime={workspace.realtime} /></div><PageState title="Workspace access denied" description="Scoped data was cleared. Retry after access is restored." error /></>
  return <OverviewContent />
}
function OverviewContent() {
  const workspace = useWorkspace()!, { application, scope, realtime } = workspace
  const view = useOverview(workspace)
  const [selection, setSelection] = useState<{ id: string; snapshot: string } | null>(null)
  const snapshot = JSON.stringify([scope, workspace.reconciliation])
  const selected = selection?.snapshot === snapshot && !view.logs.error ? view.samples.logs.find(log => log.event_id === selection.id) : undefined
  const busy = view.analytics.isFetching || view.logs.loading || view.incidents.loading
  const totals = view.analytics.data?.totals
  const fallback = view.analytics.isFetching && !view.analytics.error ? 'Loading...' : 'Unavailable'
  return <>
    <div className="page-heading"><div><div className="eyebrow">Application health</div><h1>Overview</h1><p className="subtitle">Monitor {application.name} across {scope.environmentId ? application.environments.find(env => env.id === scope.environmentId)?.name ?? scope.environmentId : 'all environments'}.</p></div>
      <div className="heading-actions"><a className="btn" href={`#/applications/${application.id}/logs`}>View logs <Icon name="arrowRight" size={15} /></a><LiveStatus realtime={realtime} /><Button title="Refresh overview" aria-label="Refresh overview" disabled={busy} onClick={() => { workspace.refreshWindow(); workspace.refreshIncidents(); workspace.refreshAnalytics() }}><Icon name="refresh" size={15} /></Button></div>
    </div>
    <div className="intro-banner"><div><h3><Icon name="activity" size={16} /> Monitor your application in one place</h3><p>Investigate recent errors, explore activity and respond to incidents.</p></div></div>
    {view.partial && <p className="form-feedback" role="status">Some sections are unavailable. Showing the data available for this window.</p>}
    <div className="kpi-grid"><Kpi title="Total logs" value={totals?.total ?? fallback} icon="logs" note="In selected reporting window" /><Kpi title="Error logs" value={totals?.errors ?? fallback} icon="alert" note="ERROR events in selected window" /><Kpi title="Critical logs" value={totals?.critical ?? fallback} icon="bolt" note="CRITICAL events in selected window" /><Kpi title="Open incidents (loaded)" value={view.samples.loadedOpenCount ?? (view.incidents.initialLoading && !view.incidents.error ? 'Loading...' : 'Unavailable')} icon="shield" note="Loaded records only; not a total" accent="open" /></div>
    <div className="split-grid"><div className="panel"><div className="panel-head"><div><h2>Log activity</h2><p>Log volume over time &middot; hourly aggregation</p></div><span className="tag muted"><Icon name="clock" size={12} />Hourly</span></div>
      {view.analytics.error ? <PageState title="Analytics unavailable" description={view.analytics.error.message} error retry={view.analytics.error.status === 403 ? undefined : () => { void view.analytics.refetch() }} /> : view.analytics.data ? <HourlyActivityChart metrics={view.analytics.data} /> : <PageState title="Loading hourly activity…" busy />}
    </div><div className="panel"><div className="panel-head"><div><h2>Recent Incidents</h2><p>{view.incidents.error ? 'Loaded Incident count unavailable' : view.incidents.loading && !view.incidents.items.length ? 'Loading Incidents…' : `${view.samples.loadedOpenCount} OPEN in ${view.samples.loadedIncidentCount} loaded records; not a total`}</p></div><a className="btn btn-sm btn-ghost" href={`#/applications/${application.id}/incidents`}>View all <Icon name="arrowRight" size={13} /></a></div>
      <p className="tiny-note log-window">Most recent 4 from loaded records.</p>
      {view.incidents.error ? <PageState title="Incidents unavailable" description={view.incidents.error.message} error retry={view.incidents.error.status === 403 ? undefined : view.incidents.retry} />
        : view.samples.incidents.length ? <IncidentTable items={view.samples.incidents} compact /> : <PageState title={view.incidents.loading ? 'Loading Incidents…' : 'No recent Incidents'} busy={view.incidents.loading} />}
    </div></div>
    <div className="panel"><div className="panel-head"><div><h2>Latest logs</h2><p>Most recent events from this application</p></div><a className="btn btn-sm btn-ghost" href={`#/applications/${application.id}/logs`}>Open explorer <Icon name="arrowRight" size={13} /></a></div>
      {view.logs.error ? <PageState title="Logs unavailable" description={view.logs.error.message} error retry={view.logs.error.status === 403 ? undefined : view.logs.retry} />
        : view.samples.logs.length ? <LogTable compact items={view.samples.logs} application={application} select={log => setSelection({ id: log.event_id, snapshot })} /> : <PageState title={view.logs.loading ? 'Loading logs…' : 'No matching logs'} busy={view.logs.loading} />}
    </div>
    <details className="snapshot-details"><summary>Reporting windows (UTC)</summary><p>Logs: {scope.from} to {scope.to}. Incidents (last seen): {workspace.incidentWindow.from} to {workspace.incidentWindow.to}. Analytics: {workspace.analyticsWindow.from} to {workspace.analyticsWindow.to}. Panels refresh independently; disconnected data remains a snapshot.</p></details>
    {selected && <LogDetailDrawer key={selected.event_id} log={selected} application={application} close={() => setSelection(null)} />}
  </>
}
