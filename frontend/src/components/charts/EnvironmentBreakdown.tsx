import type { AnalyticsMetrics } from '../../features/analytics/analytics.types.ts'
import type { Application } from '../../features/applications/application.types.ts'
import { formatErrorRate } from '../../features/analytics/analyticsMetrics.ts'
import { PageState } from '../ui/PageState.tsx'
import { formatCount } from '../../lib/presentation.ts'

export function EnvironmentBreakdown({ metrics, application }: { metrics: AnalyticsMetrics; application: Application }) {
  if (!metrics.hasData) return <PageState title="No environment activity" description="No matching aggregate data was returned." />
  return <>{metrics.environments.map(environment => {
    const metadata = application.environments.find(item => item.id === environment.environmentId)
    const label = metadata ? `${metadata.name}${metadata.status === 'DISABLED' ? ' (disabled)' : ''}` : environment.environmentId
    const share = environment.total / metrics.totals.total * 100
    return <div className="env-row" key={environment.environmentId}>
      <span className="env-name">{label}</span><div className="track" aria-hidden="true"><span style={{ width: `${share}%` }} /></div>
      <span className="env-number" title={`${formatCount(environment.total)} logs`}>{share.toFixed(2)}%<span className="sr-only">, {formatCount(environment.total)} logs</span></span>
    </div>
  })}<details className="environment-values"><summary>Environment values</summary>{metrics.environments.map(environment => <p className="tiny-note" key={environment.environmentId}>{application.environments.find(item => item.id === environment.environmentId)?.name ?? environment.environmentId}: {formatCount(environment.total)} logs, ERROR {formatCount(environment.errors)}, CRITICAL {formatCount(environment.critical)}, error rate {formatErrorRate(environment.rate)}</p>)}</details></>
}
