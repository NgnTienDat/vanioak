import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createUserApi, createUserManagementApi } from './user.api.ts'
import { ApiError } from '../lib/apiError.ts'
const id = '51801983-8baa-4b0a-ba77-8df1f212668b'
const user = { id, username: 'engineer', role: 'ENGINEER', status: 'ACTIVE' }
test('Engineer directory uses only supported status/cursor/limit and projects public fields', async () => {
  const api = createUserApi(axios.create({ adapter: async config => {
    assert.equal(axios.getUri(config), '/users?status=ACTIVE&limit=50&cursor=opaque%2B%3D')
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [{ ...user, unexpected: 'ignored' }], next_cursor: 'next' } } }
  } }))
  assert.deepEqual(await api.list({ status: 'ACTIVE', cursor: 'opaque+=' }), { items: [user], next_cursor: 'next' })
})
test('directory and candidate read reject ADMIN, malformed/foreign DTOs and mismatched status filters', async () => {
  let value: unknown = user
  const api = createUserApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: config.url === '/users' ? { items: [value], next_cursor: null } : value } }) }))
  assert.deepEqual(await api.get(id), user)
  for (value of [{ ...user, role: 'ADMIN' }, { ...user, id: 'fake' }, { ...user, username: null }, { ...user, status: 'UNKNOWN' }]) await assert.rejects(api.list(), ApiError)
  value = { ...user, status: 'DISABLED' }; await assert.rejects(api.list({ status: 'ACTIVE' }), ApiError)
  assert.deepEqual((await api.list({ status: 'DISABLED' })).items, [value])
  value = { ...user, id: '61801983-8baa-4b0a-ba77-8df1f212668b' }; await assert.rejects(api.get(id), ApiError)
})
test('invalid directory params and candidate IDs never issue requests; empty response is distinct from malformed', async () => {
  let calls = 0
  const api = createUserApi(axios.create({ adapter: async config => { calls++; return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { items: [], next_cursor: null } } } } }))
  assert.deepEqual(await api.list(), { items: [], next_cursor: null })
  await assert.rejects(api.list({ limit: 0 }), ApiError); await assert.rejects(api.list({ limit: 1.5 }), ApiError)
  await assert.rejects(api.get('fake'), ApiError); assert.equal(calls, 1)
})


test('User writes send exact create/edit/enable/soft-disable bodies and project only public identity', async () => {
  const seen: unknown[] = []
  const api = createUserManagementApi(axios.create({ adapter: async config => {
    const body = config.data ? JSON.parse(config.data) : undefined
    seen.push([config.method, config.url, body])
    return { config, status: config.method === 'post' ? 201 : 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: { ...user, username: body?.username ?? user.username, status: config.method === 'delete' ? 'DISABLED' : 'ACTIVE', password_hash: 'must-not-project' } } }
  } }))
  assert.deepEqual(await api.create({ username: 'e', password: ' x ' }), { ...user, username: 'e' })
  await api.edit(id, { password: ' x ' }); await api.edit(id, { username: 'renamed' })
  await api.status(id, 'ACTIVE'); await api.status(id, 'DISABLED')
  assert.deepEqual(seen, [['post', '/users', { username: 'e', password: ' x ' }], ['patch', `/users/${id}`, { password: ' x ' }], ['patch', `/users/${id}`, { username: 'renamed' }], ['patch', `/users/${id}`, { status: 'ACTIVE' }], ['delete', `/users/${id}`, undefined]])
})
test('User writes reject invalid requests, ADMIN/foreign/malformed responses and normalize raw errors', async () => {
  let calls = 0, value: unknown = user
  const api = createUserManagementApi(axios.create({ adapter: async config => {
    calls++
    return { config, status: 200, statusText: 'OK', headers: {}, data: { success: true, message: 'Success', data: value } }
  } }))
  for (const values of [{}, { username: null }, { password: '' }, { role: 'ADMIN' }]) await assert.rejects(api.edit(id, values as never), ApiError)
  await assert.rejects(api.create({ username: ' ', password: 'x' }), ApiError)
  await assert.rejects(api.status('fake', 'ACTIVE'), ApiError); assert.equal(calls, 0)
  for (value of [{ ...user, role: 'ADMIN' }, null, { ...user, status: 'DISABLED' }]) await assert.rejects(api.create({ username: 'e', password: 'x' }), ApiError)
  value = { ...user, id: '61801983-8baa-4b0a-ba77-8df1f212668b' }; await assert.rejects(api.edit(id, { username: 'e' }), ApiError)
  value = user; await assert.rejects(api.status(id, 'DISABLED'), ApiError)
  const unsafe = createUserManagementApi(axios.create({ adapter: async () => { throw new Error('private-request-body') } }))
  await assert.rejects(unsafe.edit(id, { password: 'private-request-body' }), error => error instanceof ApiError && !error.message.includes('private-request-body') && !('cause' in error))
})
