import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolutionAudit, incidentDetailQuery } from './useIncident.ts'
import type { IncidentDetail } from './incident.types.ts'
const id = '31801983-8baa-4b0a-ba77-8df1f212668b'
const detail: IncidentDetail = { id, application: { id, name: 'App' }, environment: { id, name: 'DEV' }, level: 'ERROR', status: 'RESOLVED', fingerprint: 'fp', message: 'message', error_count: 3, first_seen_at: '2026-10-29T09:00:00Z', last_seen_at: '2026-10-29T09:00:00Z', resolved_at: null, events: [] }
test('audit derives only from resolved history metadata; null legacy is unknown, never guessed automatic or named actor', () => {
  assert.deepEqual(resolutionAudit(detail), { source: null, actor: null })
  for (const metadata of [null, {}, { resolution_source: null, resolved_by_user_id: null }, { resolution_source: 'UNKNOWN' }]) {
    assert.deepEqual(resolutionAudit({ ...detail, events: [{ id, event_type: 'RESOLVED', event_at: detail.last_seen_at, metadata }] }), { source: null, actor: null })
  }
  assert.deepEqual(resolutionAudit({ ...detail, events: [{ id, event_type: 'RESOLVED', event_at: detail.last_seen_at, metadata: { resolution_source: 'MANUAL', resolved_by_user_id: id } }] }), { source: 'MANUAL', actor: id })
  assert.deepEqual(resolutionAudit({ ...detail, events: [{ id, event_type: 'RESOLVED', event_at: detail.last_seen_at, metadata: { resolution_source: 'AUTOMATIC' } }] }), { source: 'AUTOMATIC', actor: null })
})
test('detail query is ID/scope based and independent of list time window, preserving cancellation signal', async () => {
  const abort = new AbortController(), scope = { applicationId: id, environmentId: null }
  const query = incidentDetailQuery({ generation: 1, userId: 'engineer', role: 'ENGINEER' }, scope, id, { detail: async (target, passed, signal) => {
    assert.equal(target, id); assert.deepEqual(passed, scope); assert.equal(signal, abort.signal); return detail
  } })
  assert.deepEqual(await query.queryFn({ signal: abort.signal }), detail)
  assert.ok(!JSON.stringify(query.queryKey).includes('from'))
})
