import { useWorkspace } from '../workspace/workspaceScope.ts'
import { useAnalytics } from './useAnalytics.ts'
import type { AnalyticsMetrics } from './analytics.types.ts'
import { formatErrorRate } from './analyticsMetrics.ts'
import { HourlyActivityChart } from '../../components/charts/HourlyActivityChart.tsx'
import { LevelDistribution } from '../../components/charts/LevelDistribution.tsx'
import { Kpi } from '../../components/ui/Kpi.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { EnvironmentBreakdown } from '../../components/charts/EnvironmentBreakdown.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { LiveStatus } from '../realtime/LiveStatus.tsx'

export function AnalyticsKpis({ metrics, loading = false }: { metrics?: AnalyticsMetrics; loading?: boolean }) {
  const fallback = loading ? 'Loading…' : 'Unavailable'
  return <div className="kpi-grid"><Kpi title="Total log volume" value={metrics?.totals.total ?? fallback} icon="logs" note="Within selected time range" /><Kpi title="Error events" value={metrics?.totals.errors ?? fallback} icon="alert" note="ERROR logs" /><Kpi title="Critical events" value={metrics?.totals.critical ?? fallback} icon="bolt" note="CRITICAL logs" /><Kpi title="Error rate" value={metrics ? formatErrorRate(metrics.totals.rate) : fallback} icon="chart" note="(ERROR + CRITICAL) / total" /></div>
}
export function AnalyticsPage() {
  const workspace = useWorkspace()!
  if (workspace.denied) return <><div className="page-heading"><h1>Analytics</h1><LiveStatus realtime={workspace.realtime} /></div><PageState title="Analytics access denied" description="Scoped data was cleared. Retry after access is restored." error /></>
  return <AnalyticsContent />
}
function AnalyticsContent() {
  const { application, scope, analyticsWindow, analyticsRevision, refreshAnalytics, realtime } = useWorkspace()!
  const view = useAnalytics({ application_id: application.id, environment_id: scope.environmentId ?? undefined, from: analyticsWindow.from, to: analyticsWindow.to, bucket: 'hour' }, analyticsRevision)
  return <>
    <div className="page-heading"><div><div className="eyebrow">Trends and insights</div><h1>Analytics</h1><p className="subtitle">Understand log volume and severity trends for {application.name}.</p></div>
      <div className="heading-actions"><span className="tag muted"><Icon name="clock" size={13} />Hourly aggregates</span><LiveStatus realtime={realtime} /><Button disabled={view.isFetching} onClick={refreshAnalytics}><Icon name="refresh" size={15} /> Refresh</Button></div>
    </div>
    <details className="snapshot-details"><summary>Selected UTC window</summary><p>{analyticsWindow.from} to {analyticsWindow.to}. Disconnected data is a snapshot; boundary hours can be partial.</p></details>
    <AnalyticsKpis metrics={view.data} loading={view.isFetching && !view.data && !view.error} />
    {view.error && <PageState title={view.error.status === 403 ? 'Analytics access denied' : 'Analytics unavailable'} description={view.error.message} error retry={view.error.status === 403 ? undefined : () => { void view.refetch() }} />}
    {view.isFetching && <p className="tiny-note" role="status" aria-busy="true">Loading hourly aggregates…</p>}
    {view.data && <><div className="panel analytics-panel"><div className="panel-head"><div><h2>Log volume over time</h2><p>Severity breakdown &middot; UTC hourly buckets</p></div><span className="tag muted">Hourly</span></div><HourlyActivityChart metrics={view.data} kind="stacked" /></div>
      <div className="split-grid"><div className="panel"><div className="panel-head"><div><h2>Log level distribution</h2><p>Severity as a percentage of all logs</p></div></div><LevelDistribution metrics={view.data} /></div><div className="panel"><div className="panel-head"><div><h2>Environment breakdown</h2><p>Aggregated activity by environment</p></div></div><EnvironmentBreakdown metrics={view.data} application={application} /></div></div></>}
  </>
}
