import { test } from 'node:test'
import assert from 'node:assert/strict'
import { QueryObserver } from '@tanstack/react-query'
import { overviewParams, overviewSamples } from './useOverview.ts'
import { analyticsQuery } from '../analytics/useAnalytics.ts'
import { logPageQuery } from '../logs/useLogs.ts'
import { incidentPageQuery } from '../incidents/useIncidents.ts'
import { createQueryClient } from '../../lib/queryClient.ts'
import type { Application } from '../applications/application.types.ts'
import type { LogRecord } from '../logs/log.types.ts'
import type { Incident } from '../incidents/incident.types.ts'
const session = { generation: 1, userId: 'engineer', role: 'ENGINEER' as const }
const application: Application = { id: 'app', name: 'App', description: null, status: 'DISABLED', environments: [{ id: 'dev', name: 'DEV', status: 'DISABLED' }] }
const range = { environmentId: null, preset: '24h' as const, from: '2026-10-28T09:00:00Z', to: '2026-10-29T09:00:00Z' }
test('Overview uses the same domain query parameters/keys, ALL and real snapshot scopes; no extra endpoint', () => {
  const params = overviewParams({ application, scope: range, analyticsWindow: range, incidentWindow: range })
  assert.deepEqual(params.logs, { application_id: 'app', environment_id: undefined, from: range.from, to: range.to, limit: 50 })
  assert.deepEqual(params.incidents, params.logs)
  assert.equal(params.analytics.bucket, 'hour'); assert.equal(params.analytics.environment_id, undefined)
  assert.deepEqual(logPageQuery(session, params.logs, undefined, 0).queryKey, logPageQuery(session, { ...params.logs, limit: 50 }, undefined, 0).queryKey)
  assert.equal(incidentPageQuery(session, params.incidents).queryKey[0], 'incidents')
  assert.equal(analyticsQuery(session, params.analytics).queryKey[0], 'analytics')
})
test('Overview latest6/recent4 are bounded real samples; loaded open count is not a global total and unavailable is null', () => {
  const logs: LogRecord[] = Array.from({ length: 10 }, (_,index) => ({ event_id: String(index), application_id: 'app', environment_id: 'dev', level: 'INFO', host_ip: 'host', message: 'message', trace_id: '', timestamp: range.to, received_at: range.to, processed_at: range.to }))
  const incidents: Incident[] = Array.from({ length: 9 }, (_,index) => ({ id: String(index), application: { id: 'app', name: 'App' }, environment: { id: 'dev', name: 'DEV' }, status: index < 6 ? 'OPEN' : 'RESOLVED', level: 'ERROR', error_count: 1, message: 'message', fingerprint: 'fp', first_seen_at: range.from, last_seen_at: range.to }))
  const result = overviewSamples(logs, incidents)
  assert.equal(result.logs.length, 6); assert.equal(result.incidents.length, 4); assert.equal(result.loadedIncidentCount, 9); assert.equal(result.loadedOpenCount, 6)
  assert.equal(result.logs[0], logs[0]); assert.equal(result.incidents[0], incidents[0])
  assert.equal(overviewSamples(logs, undefined).logs.length, 6); assert.equal(overviewSamples(logs, undefined).loadedOpenCount, null)
  assert.equal(overviewSamples([], []).loadedOpenCount, 0); assert.equal(overviewSamples().loadedIncidentCount, null)
})
test('two consumers of the same Overview/Explorer head share an in-flight Query and never duplicate backend data state', async () => {
  const client = createQueryClient(); let calls = 0, finish!: () => void
  const params = overviewParams({ application, scope: range, analyticsWindow: range, incidentWindow: range })
  const query = logPageQuery(session, params.logs, { list: async () => { calls++; await new Promise<void>(resolve => { finish = resolve }); return { items: [], next_cursor: null } } }, 0)
  const first = new QueryObserver(client, query), second = new QueryObserver(client, query)
  const stopFirst = first.subscribe(() => {}), stopSecond = second.subscribe(() => {})
  try {
    assert.equal(calls, 1); finish(); await client.fetchQuery(query)
    assert.equal(client.getQueryCache().getAll().length, 1); assert.deepEqual(first.getCurrentResult().data, second.getCurrentResult().data)
  } finally { stopFirst(); stopSecond(); first.destroy(); second.destroy(); client.clear() }
})
