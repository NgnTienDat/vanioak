import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createIncidentResolution, canResolveIncident } from './useResolveIncident.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { createClients } from '../../api/client.ts'
import { createIncidentApi } from '../../api/incident.api.ts'
import { incidentKeys } from './incident.keys.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { IncidentDetail, IncidentResolution } from './incident.types.ts'

const app = '31801983-8baa-4b0a-ba77-8df1f212668b', env = '41801983-8baa-4b0a-ba77-8df1f212668b', id = '51801983-8baa-4b0a-ba77-8df1f212668b'
const actor = '61801983-8baa-4b0a-ba77-8df1f212668b', at = '2026-10-29T09:00:00.000Z'
const scope = { applicationId: app, environmentId: env }
const open: IncidentDetail = { id, application: { id: app, name: 'App' }, environment: { id: env, name: 'DEV' }, status: 'OPEN', level: 'ERROR', error_count: 3, fingerprint: 'fp', message: 'message', first_seen_at: at, last_seen_at: at, resolved_at: null, events: [] }
const manual: IncidentResolution = { id, status: 'RESOLVED', resolved_at: at, resolution_source: 'MANUAL', resolved_by_user_id: actor }
const pair = { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', token_type: 'Bearer' as const, expires_in: 3600 }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ENGINEER') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ ...pair, user: { id: actor, username: 'actor', role } }))
  const session = { generation: store.getState().generation, userId: actor, role }, unbind = bindSessionCache(client, store)
  return { store, client, session, cleanup() { unbind(); client.clear() } }
}
test('Engineer/Admin resolve only an owned OPEN Incident; disabled metadata does not remove this permission', async () => {
  for (const role of ['ENGINEER','ADMIN'] as const) {
    const f = await fixture(role); let patches = 0
    const disabledApplication = { id: app, status: 'DISABLED', environment: { id: env, status: 'DISABLED' } }
    assert.equal(canResolveIncident(open, role, { applicationId: disabledApplication.id, environmentId: disabledApplication.environment.id }), true)
    const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => open, resolve: async () => { patches++; return manual } } })
    try {
      const result = await action.execute(); assert.deepEqual(result, { kind: 'resolved', resolution: manual }); assert.equal(patches, 1)
      assert.equal(f.client.getQueryData<IncidentDetail>(incidentKeys.detail(f.session, scope, id))?.events.length, 0)
      assert.equal(f.client.getQueryState(incidentKeys.detail(f.session, scope, id))?.isInvalidated, true)
    } finally { f.cleanup() }
  }
  assert.equal(canResolveIncident(open, 'OTHER', scope), false)
  assert.equal(canResolveIncident(open, 'ENGINEER', { ...scope, applicationId: env }), false)
  assert.equal(canResolveIncident({ ...open, status: 'RESOLVED' }, 'ADMIN', scope), false)
})
test('server authorization denial prevents PATCH and clears denied scoped cache', async () => {
  const f = await fixture(); let patches = 0
  f.client.setQueryData(incidentKeys.detail(f.session, scope, id), open)
  f.client.setQueryData(['logs', f.session.generation, actor, 'ENGINEER', app, env], 'sensitive')
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => { throw new ApiError(403, 'Access denied.') }, resolve: async () => { patches++; return manual } } })
  try { await assert.rejects(action.execute(), error => error instanceof ApiError && error.status === 403); assert.equal(patches, 0); assert.equal(f.client.getQueryCache().getAll().length, 0) }
  finally { f.cleanup() }
})
test('concurrent confirms share one command; duplicate automatic winner audit is never replaced with local actor', async () => {
  const f = await fixture(); let patches = 0, reads = 0, finish!: () => void, ready!: () => void
  const started = new Promise<void>(resolve => { ready = resolve })
  const automatic: IncidentResolution = { ...manual, resolution_source: 'AUTOMATIC', resolved_by_user_id: null }
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => { reads++; return patches ? { ...open, status: 'RESOLVED' } : open },
    resolve: async () => { patches++; ready(); await new Promise<void>(resolve => { finish = resolve }); return automatic } } })
  try {
    const first = action.execute(), second = action.execute(); assert.equal(first, second)
    await started; assert.equal(patches, 1); finish()
    assert.deepEqual(await first, { kind: 'resolved', resolution: automatic })
    assert.equal((await action.execute()).kind, 'already-resolved'); assert.equal(patches, 1); assert.equal(reads, 2)
  } finally { f.cleanup() }
})
test('lost PATCH response reconciles real RESOLVED detail without claiming local manual success or timeline insertion', async () => {
  const f = await fixture(); let reads = 0, patches = 0
  const resolved: IncidentDetail = { ...open, status: 'RESOLVED', resolved_at: at, events: [{ id, event_type: 'RESOLVED', event_at: at, metadata: { resolution_source: 'AUTOMATIC' } }] }
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => ++reads === 1 ? open : resolved, resolve: async () => { patches++; throw new ApiError(0, 'Transport lost.') } } })
  try {
    const result = await action.execute(); assert.equal(result.kind, 'uncertain')
    if (result.kind !== 'uncertain') assert.fail('Expected uncertain outcome')
    assert.equal(result.reconciled, true); assert.equal(result.incident?.status, 'RESOLVED'); assert.ok(!('resolution' in result))
    assert.equal((await action.execute()).kind, 'already-resolved'); assert.equal(patches, 1)
  } finally { f.cleanup() }
})
test('failed reconciliation blocks another confirmation until explicit GET-only status check succeeds', async () => {
  const f = await fixture(); let reads = 0, patches = 0, available = false, failPatch = true
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => {
    if (++reads > 1 && !available) throw new ApiError(503, 'Unavailable.')
    return open
  }, resolve: async () => { patches++; if (failPatch) throw new ApiError(0, 'Lost response.'); return manual } } })
  try {
    const result = await action.execute(); assert.equal(result.kind, 'uncertain'); assert.equal(action.needsReconciliation, true)
    await assert.rejects(action.execute(), error => error instanceof ApiError && error.status === 409); assert.equal(patches, 1)
    available = true; await action.execute('reconcile'); assert.equal(action.needsReconciliation, false); assert.equal(patches, 1)
    failPatch = false; await action.execute(); assert.equal(patches, 2)
  } finally { f.cleanup() }
})
test('PATCH401 may renew shared credentials for reconciliation but never replays the write', async () => {
  const f = await fixture(); let patches = 0, gets = 0, renewals = 0
  const transport = createClients(f.store, { adapter: async config => {
    if (config.method === 'patch') {
      patches++
      throw new axios.AxiosError('SENSITIVE-response', 'ERR_BAD_REQUEST', config, undefined, { config, status: 401, statusText: 'Unauthorized', headers: {}, data: { sensitive: true } })
    }
    gets++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: open } }
  } })
  transport.bindRefresh(async () => { renewals++; return { ...pair, access_token: 'rotated', refresh_token: 'rotated-refresh' } })
  const action = createIncidentResolution({ ...f, scope, id, api: createIncidentApi(transport.apiClient), renew: transport.refresh })
  try {
    const result = await action.execute(); assert.equal(result.kind, 'uncertain'); assert.equal(patches, 1); assert.equal(renewals, 1); assert.equal(gets, 2)
    assert.ok(!JSON.stringify(result).includes('SENSITIVE')); assert.ok(!JSON.stringify(result).includes('synthetic-access'))
  } finally { f.cleanup() }
})
test('definite400/404 or denied PATCH fail without automatic retry or fabricated status', async () => {
  for (const status of [400,403,404]) {
    const f = await fixture(); let patches = 0, reads = 0
    const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => { reads++; return open }, resolve: async () => { patches++; throw new ApiError(status, 'Rejected.') } } })
    try { await assert.rejects(action.execute(), error => error instanceof ApiError && error.status === status); assert.equal(patches, 1); assert.equal(reads, 1) }
    finally { f.cleanup() }
  }
})
test('late committed mutation after logout cannot refill caches or reveal data to the next generation', async () => {
  const f = await fixture(); let finish!: () => void, ready!: () => void
  const started = new Promise<void>(resolve => { ready = resolve })
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => open, resolve: async () => { ready(); await new Promise<void>(resolve => { finish = resolve }); return manual } } })
  try {
    const result = action.execute(); const rejected = assert.rejects(result)
    await started; f.store.getState().clear(); finish(); await rejected
    assert.equal(f.client.getQueryCache().getAll().length, 0)
    await assert.rejects(action.execute()); assert.equal(f.client.getQueryCache().getAll().length, 0)
  } finally { f.cleanup() }
})
test('scope/unmount cancellation discards an unconfirmed in-flight PATCH response without interpreting abort as rollback', async () => {
  const f = await fixture(); let finish!: () => void, ready!: () => void, passed: AbortSignal | undefined
  const started = new Promise<void>(resolve => { ready = resolve })
  const action = createIncidentResolution({ ...f, scope, id, api: { detail: async () => open, resolve: async (_id, signal) => { passed = signal; ready(); await new Promise<void>(resolve => { finish = resolve }); return manual } } })
  try {
    const result = action.execute(); const rejected = assert.rejects(result)
    await started; action.cancel(); assert.equal(passed?.aborted, true); finish(); await rejected
    assert.equal(f.client.getQueryData<IncidentDetail>(incidentKeys.detail(f.session, scope, id))?.status, 'OPEN')
  } finally { f.cleanup() }
})
