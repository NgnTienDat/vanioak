import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assignedEngineerOptions, assignedEngineerRows } from './useAssignedEngineers.ts'
import { createAuthStore } from '../../store/authStore.ts'
import { createQueryClient, bindSessionCache } from '../../lib/queryClient.ts'
import { ApiError } from '../../lib/apiError.ts'
const user = { id: 'engineer', username: 'e', status: 'ACTIVE' as const }
async function fixture(role: 'ADMIN' | 'ENGINEER' = 'ADMIN') {
  const store = createAuthStore(), client = createQueryClient()
  await store.getState().login({ username: 'actor', password: 'synthetic' }, async () => ({ access_token: 'test-access', refresh_token: 'test-refresh', expires_in: 3600, token_type: 'Bearer', user: { id: 'actor', username: 'actor', role } }))
  return { store, client, scope: { generation: store.getState().generation, userId: 'actor', role } }
}
test('roster preserves backend ordering, disabled members, stable dedup and does not treat an in-flight next page as a loop', () => {
  const first = { items: [user], next_cursor: 'next' }, second = { items: [user, { ...user, id: 'disabled', status: 'DISABLED' as const }], next_cursor: null }
  assert.deepEqual(assignedEngineerRows([first], [null]), { items: [user], next: 'next' })
  assert.deepEqual(assignedEngineerRows([first], [null, 'next']), { items: [user], next: null })
  assert.deepEqual(assignedEngineerRows([first, second], [null, 'next']).items, [user, second.items[1]])
  assert.throws(() => assignedEngineerRows([first, { ...second, next_cursor: 'next' }], [null, 'next']), ApiError)
  assert.deepEqual(assignedEngineerRows([], [null]), { items: [], next: null })
})
test('roster query sends exact scope/cursor and rejects Engineer access before requesting', async () => {
  for (const role of ['ADMIN', 'ENGINEER'] as const) {
    const f = await fixture(role); let calls = 0
    const options = assignedEngineerOptions(f.scope, 'app', 'cursor', { list: async (app, params) => { calls++; assert.equal(app, 'app'); assert.deepEqual(params, { cursor: 'cursor', limit: 50 }); return { items: [user], next_cursor: null } }, grant: async () => {}, remove: async () => {} }, f.store)
    try {
      if (role === 'ADMIN') assert.equal((await f.client.fetchQuery(options)).items[0], user)
      else await assert.rejects(f.client.fetchQuery(options), error => error instanceof ApiError && error.status === 403)
      assert.equal(calls, role === 'ADMIN' ? 1 : 0)
    } finally { f.client.clear() }
  }
})
test('cancelled obsolete roster reads cannot refill cache even when an adapter ignores AbortSignal', async () => {
  const f = await fixture(); let deliver!: (value: { items: typeof user[]; next_cursor: null }) => void
  const options = assignedEngineerOptions(f.scope, 'app', null, { list: async () => new Promise(resolve => { deliver = resolve }), grant: async () => {}, remove: async () => {} }, f.store)
  try {
    const pending = f.client.fetchQuery(options); const rejection = assert.rejects(pending)
    await f.client.cancelQueries({ queryKey: options.queryKey }); deliver({ items: [user], next_cursor: null }); await rejection
    assert.equal(f.client.getQueryData(options.queryKey), undefined)
  } finally { f.client.clear() }
})
test('logout/account generation invalidates pending roster delivery and sensitive cache', async () => {
  const f = await fixture(), unbind = bindSessionCache(f.client, f.store)
  let deliver!: (value: { items: typeof user[]; next_cursor: null }) => void
  const options = assignedEngineerOptions(f.scope, 'app', null, { list: async () => new Promise(resolve => { deliver = resolve }), grant: async () => {}, remove: async () => {} }, f.store)
  try {
    const pending = f.client.fetchQuery(options), rejection = assert.rejects(pending)
    f.store.getState().clear(); deliver({ items: [user], next_cursor: null }); await rejection
    assert.equal(f.client.getQueryCache().getAll().length, 0)
  } finally { unbind(); f.client.clear() }
})
