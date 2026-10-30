import type { AnalyticsMetrics } from '../../features/analytics/analytics.types.ts'
import { severityGroups, formatCount } from '../../lib/presentation.ts'
import { PageState } from '../ui/PageState.tsx'
export function LevelDistribution({ metrics }: { metrics: AnalyticsMetrics }) {
  if (!metrics.hasData) return <PageState title="No matching logs" />
  return <div className="level-distribution">{severityGroups(metrics.totals).map(group => <div className="distribution-row" key={group.label}>
    <span className={`level ${group.label}`}>{group.label}</span><div className="track" aria-hidden="true"><span style={{ width: `${group.value / metrics.totals.total * 100}%`, background: group.color }} /></div>
    <strong title={`${formatCount(group.value)} logs`}>{(group.value / metrics.totals.total * 100).toFixed(2)}%<span className="sr-only">, {formatCount(group.value)} logs</span></strong>
  </div>)}<p className="tiny-note">Other includes all levels except ERROR and CRITICAL.</p></div>
}
