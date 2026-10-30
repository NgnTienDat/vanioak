import { test } from 'node:test'
import assert from 'node:assert/strict'
import { userOptions, userRows, userDetailOptions, searchUsers } from './useUsers.ts'
import { createQueryClient } from '../../lib/queryClient.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { ApiError } from '../../lib/apiError.ts'
const user = { id: 'engineer', username: 'e', role: 'ENGINEER' as const, status: 'ACTIVE' as const }
test('directory pages dedup, retain next cursor only after complete delivery and reset cleanly to an empty head', () => {
  const first = { items: [user], next_cursor: 'next' }
  assert.equal(userRows([first], [null]).next, 'next')
  assert.equal(userRows([first], [null, 'next']).next, null)
  assert.deepEqual(userRows([first, { items: [user], next_cursor: null }], [null, 'next']).items, [user])
  assert.throws(() => userRows([first, first], [null, 'next']), ApiError)
  assert.deepEqual(userRows([{ items: [], next_cursor: null }], [null]), { items: [], next: null })
})
test('directory query uses ACTIVE status and rejects late account replacement without retaining records', async () => {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'a', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'a', role: 'ADMIN' } }))
  const scope = { generation: store.getState().generation, userId: 'actor', role: 'ADMIN' as const }
  let deliver!: (value: { items: typeof user[]; next_cursor: null }) => void
  const options = userOptions(scope, 'ACTIVE', null, { list: async params => { assert.deepEqual(params, { status: 'ACTIVE', cursor: null, limit: 50 }); return new Promise(resolve => { deliver = resolve }) }, get: async () => user }, store)
  try {
    const pending = client.fetchQuery(options), rejection = assert.rejects(pending)
    store.getState().clear(); deliver({ items: [user], next_cursor: null }); await rejection
    assert.equal(client.getQueryData(options.queryKey), undefined)
  } finally { client.clear() }
})


test('Users search applies only to loaded usernames and status/cursor keys reset to the head', () => {
  const loaded = [user, { ...user, id: 'second', username: 'Taylor', status: 'DISABLED' as const }]
  assert.deepEqual(searchUsers(loaded, 'TAY'), [loaded[1]])
  assert.deepEqual(searchUsers(loaded, 'absent'), [])
  const scope = { generation: 1, userId: 'actor', role: 'ADMIN' as const }
  assert.notDeepEqual(userOptions(scope, 'ACTIVE', 'next').queryKey, userOptions(scope, 'DISABLED', null).queryKey)
  assert.equal(userOptions(scope, 'DISABLED', null).queryKey[5].cursor, null)
})
test('detail query is an exact fresh GET and refuses Engineer access before a request', async () => {
  const store = createAuthStore(), client = createQueryClient(); let calls = 0
  const api = { list: async () => ({ items: [], next_cursor: null }), get: async (id: string) => { calls++; assert.equal(id, user.id); return user } }
  try {
    await store.getState().login({ username: 'e', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'e', role: 'ENGINEER' } }))
    await assert.rejects(client.fetchQuery(userDetailOptions({ generation: store.getState().generation, userId: 'actor', role: 'ENGINEER' }, user.id, api, store)), ApiError)
    assert.equal(calls, 0)
    await store.getState().login({ username: 'a', password: 'synthetic' }, async () => ({ access_token: 'test', refresh_token: 'test', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'a', role: 'ADMIN' } }))
    const options = userDetailOptions({ generation: store.getState().generation, userId: 'actor', role: 'ADMIN' }, user.id, api, store)
    assert.equal(options.refetchOnMount, 'always'); assert.equal(options.gcTime, 0)
    assert.deepEqual(await client.fetchQuery(options), user); assert.deepEqual(await client.fetchQuery(options), user); assert.equal(calls, 2)
  } finally { client.clear() }
})
