import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseRoute, routeFor, workspacePath } from './routes.ts'
const id = '31801983-8baa-4b0a-ba77-8df1f212668b'
test('both roles enter applications; anonymous deep links only reach login', () => {
  for (const role of ['ADMIN','ENGINEER'] as const) {
    assert.deepEqual(routeFor('#/login',role),{ page: 'applications' })
    assert.deepEqual(routeFor(workspacePath(id),role),{ page: 'workspace',id })
  }
  assert.deepEqual(routeFor(workspacePath(id),null),{ page: 'login' })
})
test('only intended routes are accepted; no external redirects, invented app IDs or unimplemented F2 views', () => {
  for (const hash of ['#https://elsewhere.test','#//elsewhere.test','#/users/unknown','#/applications/fake/overview',`#/applications/${id}/incidents/fake`, `#/applications/${id}/analytics/fake`, '#/applications?role=ADMIN']) assert.deepEqual(parseRoute(hash),{ page: 'not-found' })
})


test('authorized Logs Explorer route keeps the application ID and no extra routes', () => {
  for (const role of ['ADMIN','ENGINEER'] as const) assert.deepEqual(routeFor(`#/applications/${id}/logs`,role),{ page:'workspace',id,view:'logs' })
  assert.deepEqual(routeFor(`#/applications/${id}/logs`,null),{ page:'login' })
  for (const suffix of ['/logs/fake','/logs?token=secret','/logs/']) assert.deepEqual(parseRoute(`#/applications/${id}${suffix}`),{ page:'not-found' })
})


test('Incident list/detail routes preserve validated IDs and anonymous role guard', () => {
  for (const role of ['ADMIN','ENGINEER'] as const) {
    assert.deepEqual(routeFor(`#/applications/${id}/incidents`, role), { page: 'workspace', id, view: 'incidents' })
    assert.deepEqual(routeFor(`#/applications/${id}/incidents/${id}`, role), { page: 'workspace', id, view: 'incidents', incidentId: id })
  }
  assert.deepEqual(routeFor(`#/applications/${id}/incidents/${id}`, null), { page: 'login' })
  for (const suffix of ['/incidents/fake','/incidents/'+id+'/status','/incidents?status=OPEN','/logs/'+id]) assert.deepEqual(parseRoute(`#/applications/${id}${suffix}`), { page: 'not-found' })
})


test('Analytics route is authorized; no invented detail/management routes are added', () => {
  for (const role of ['ADMIN','ENGINEER'] as const) assert.deepEqual(routeFor(`#/applications/${id}/analytics`,role),{ page:'workspace',id,view:'analytics' })
  assert.deepEqual(routeFor(`#/applications/${id}/analytics`,null),{ page:'login' })
  for (const suffix of ['/analytics/fake','/analytics/'+id,'/settings/fake']) assert.deepEqual(parseRoute(`#/applications/${id}${suffix}`),{ page:'not-found' })
})

test('Application Settings is Admin-only before management hooks mount; no foreign/invalid fallback route', () => {
  assert.deepEqual(routeFor(`#/applications/${id}/settings`, 'ADMIN'), { page: 'settings', id })
  assert.deepEqual(routeFor(`#/applications/${id}/settings`, 'ENGINEER'), { page: 'forbidden' })
  assert.deepEqual(routeFor(`#/applications/${id}/settings`, null), { page: 'login' })
  for (const hash of ['#/applications/unknown/settings', `#/applications/${id}/settings/users`, `#/applications/${id}/settings?admin=true`]) assert.deepEqual(parseRoute(hash), { page: 'not-found' })
})


test('Users management is Admin-only and keeps all shared workspace routes', () => {
  assert.deepEqual(routeFor('#/users', 'ADMIN'), { page: 'users' })
  assert.deepEqual(routeFor('#/users', 'ENGINEER'), { page: 'forbidden' })
  assert.deepEqual(routeFor('#/users', null), { page: 'login' })
  for (const hash of ['#/users/', '#/users?role=ADMIN', '#/users/new']) assert.deepEqual(parseRoute(hash), { page: 'not-found' })
  for (const view of ['overview', 'logs', 'incidents', 'analytics']) for (const role of ['ADMIN', 'ENGINEER'] as const) assert.equal(routeFor(`#/applications/${id}/${view}`, role).page, 'workspace')
})
