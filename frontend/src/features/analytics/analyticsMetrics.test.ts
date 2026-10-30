import { test } from 'node:test'
import assert from 'node:assert/strict'
import { analyticsMetrics, formatErrorRate } from './analyticsMetrics.ts'
import { ApiError } from '../../lib/apiError.ts'
const range = { from: '2026-10-29T09:15:00Z', to: '2026-10-29T11:15:00Z' }
const first = { application_id: 'app', environment_id: 'dev', timestamp: '2026-10-29T09:00:00Z', total_logs: 10, error_logs: 8, critical_logs: 1, log_error_rate: 0.9 }
const second = { ...first, environment_id: 'test', total_logs: 90, error_logs: 7, critical_logs: 2, log_error_rate: 0.1 }
test('weighted rate sums ERROR+CRITICAL numerators; never averages hourly/environment ratios', () => {
  const result = analyticsMetrics({ bucket: 'hour', items: [first,second] }, range)
  assert.deepEqual(result.totals, { total: 100, errors: 15, critical: 3, rate: 0.18 })
  assert.notEqual(result.totals.rate, 0.5); assert.equal(formatErrorRate(result.totals.rate), '18.00%')
  assert.equal(result.environments.find(env => env.environmentId === 'dev')?.rate, 0.9)
  assert.equal(result.hours[0].total, 100)
})
test('UTC buckets sort chronologically, preserve partial edges and derive sparse zeros only after successful response', () => {
  const result = analyticsMetrics({ bucket: 'hour', items: [{ ...first, timestamp: '2026-10-29T11:00:00Z' }, first] }, range)
  assert.deepEqual(result.hours.map(hour => [hour.timestamp, hour.total, hour.partial]), [
    ['2026-10-29T09:00:00.000Z',10,true], ['2026-10-29T10:00:00.000Z',0,false], ['2026-10-29T11:00:00.000Z',10,true],
  ])
  const aligned = analyticsMetrics({ bucket: 'hour', items: [first] }, { from: '2026-10-29T09:00:00Z', to: '2026-10-29T10:00:00Z' })
  assert.equal(aligned.hours.length, 1); assert.equal(aligned.hours[0].partial, false)
})
test('empty result has no data and undefined rate; no fabricated severity distribution or health verdict', () => {
  const result = analyticsMetrics({ bucket: 'hour', items: [] }, range)
  assert.equal(result.hasData, false); assert.equal(result.totals.total, 0); assert.equal(result.totals.rate, null); assert.equal(formatErrorRate(null), 'N/A')
  assert.deepEqual(result.environments, [])
  assert.ok(!('info' in result.totals)); assert.ok(!('warn' in result.totals)); assert.ok(!('health' in result))
})
test('sum overflow is rejected instead of silently rounding otherwise valid per-row counts', () => {
  const huge = { ...first, total_logs: Number.MAX_SAFE_INTEGER, error_logs: 0, critical_logs: 0, log_error_rate: 0 }
  assert.throws(() => analyticsMetrics({ bucket: 'hour', items: [huge,second] }, range), ApiError)
})
