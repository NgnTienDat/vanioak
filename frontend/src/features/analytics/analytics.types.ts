export type HealthMetric = {
  application_id: string; environment_id: string; timestamp: string
  total_logs: number; error_logs: number; critical_logs: number; log_error_rate: number
}
export type HealthAnalytics = { bucket: 'hour'; items: HealthMetric[] }
export type AnalyticsParams = { application_id: string; environment_id?: string; from: string; to: string; bucket?: 'hour' }
export type MetricTotals = { total: number; errors: number; critical: number; rate: number | null }
export type HourMetric = MetricTotals & { timestamp: string; partial: boolean }
export type EnvironmentMetric = MetricTotals & { environmentId: string }
export type AnalyticsMetrics = { totals: MetricTotals; hours: HourMetric[]; environments: EnvironmentMetric[]; hasData: boolean }
