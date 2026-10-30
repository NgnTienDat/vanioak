import { malformed } from '../../lib/apiError.ts'
import type { AnalyticsParams, AnalyticsMetrics, MetricTotals, HealthAnalytics, EnvironmentMetric } from './analytics.types.ts'

function empty(): MetricTotals { return { total: 0, errors: 0, critical: 0, rate: null } }
function add(target: MetricTotals, total: number, errors: number, critical: number) {
  target.total += total; target.errors += errors; target.critical += critical
  if (![target.total, target.errors, target.critical, target.errors + target.critical].every(Number.isSafeInteger)) throw malformed()
  target.rate = target.total ? (target.errors + target.critical) / target.total : null
}
export function analyticsMetrics(data: HealthAnalytics, range: Pick<AnalyticsParams, 'from' | 'to'>): AnalyticsMetrics {
  const start = Date.parse(range.from), end = Date.parse(range.to)
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end || end - start > 7 * 86400000) throw malformed()
  const totals = empty(), byHour = new Map<number, MetricTotals>(), byEnvironment = new Map<string, EnvironmentMetric>()
  for (const row of data.items) {
    add(totals, row.total_logs, row.error_logs, row.critical_logs)
    const hour = Date.parse(row.timestamp), hourly = byHour.get(hour) ?? empty()
    add(hourly, row.total_logs, row.error_logs, row.critical_logs); byHour.set(hour, hourly)
    const environment = byEnvironment.get(row.environment_id) ?? { ...empty(), environmentId: row.environment_id }
    add(environment, row.total_logs, row.error_logs, row.critical_logs); byEnvironment.set(row.environment_id, environment)
  }
  const hours = []
  for (let hour = Math.floor(start / 3600000) * 3600000; hour < end; hour += 3600000) {
    hours.push({ ...(byHour.get(hour) ?? empty()), timestamp: new Date(hour).toISOString(), partial: hour < start || hour + 3600000 > end })
  }
  return { totals, hours, environments: [...byEnvironment.values()].sort((a,b) => a.environmentId.localeCompare(b.environmentId)), hasData: totals.total > 0 }
}
export function formatErrorRate(rate: number | null) { return rate === null ? 'N/A' : `${(rate * 100).toFixed(2)}%` }
