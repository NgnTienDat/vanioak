import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { Application } from '../applications/application.types.ts'
import { workspaceScope, initialWorkspaceScope } from './workspaceScope.ts'
import { ApiError } from '../../lib/apiError.ts'
const application: Application = { id: 'app', name: 'App', status: 'DISABLED', description: null,
  environments: [{ id: 'test', name: 'TEST', status: 'ACTIVE' }, { id: 'dev', name: 'DEV', status: 'DISABLED' }] }
const now = Date.parse('2026-10-29T10:00:00.000Z')
test('workspace defaults to actual DEV ID and disabled environments remain readable', () => {
  assert.deepEqual(initialWorkspaceScope(application, now), { environmentId: 'dev', preset: '24h', from: '2026-10-28T10:00:00.000Z', to: '2026-10-29T10:00:00.000Z' })
  assert.equal(initialWorkspaceScope({ ...application, environments: [] }, now).environmentId, null)
})
test('ALL is null and 1h/24h/7d freeze exact UTC ranges without mutating earlier cursor bounds', () => {
  const before = workspaceScope(application, null, '7d', now)
  assert.equal(before.from, '2026-10-22T10:00:00.000Z'); assert.equal(before.environmentId, null)
  assert.equal(workspaceScope(application, 'test', '1h', now + 1000).from, '2026-10-29T09:00:01.000Z')
  assert.equal(before.to, '2026-10-29T10:00:00.000Z')
})
test('foreign environment is rejected before query creation, no silent fallback', () => {
  assert.throws(() => workspaceScope(application, 'foreign', '24h', now), error => error instanceof ApiError && error.status === 403)
  assert.throws(() => workspaceScope(application, 'ALL', '24h', now), ApiError)
})
