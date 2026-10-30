import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createAuthStore } from './authStore.ts'
test('store has no server lists, generation changes only at session boundaries and pair installs atomically', async () => {
  const store = createAuthStore(() => 1000)
  const result = { access_token: 'a', refresh_token: 'r', token_type: 'Bearer' as const, expires_in: 60, user: { id: 'id', username: 'user',role: 'ENGINEER' as const } }
  await store.getState().login({ username: 'user',password: 'secret' },async () => result)
  const before = store.getState()
  store.getState().install(before.generation,{ ...result, access_token: 'b', refresh_token: 's' })
  assert.equal(store.getState().generation,before.generation)
  assert.equal(store.getState().revision,before.revision + 1)
  assert.equal(store.getState().tokens?.refresh_token,'s')
  assert.equal('applications' in store.getState(),false)
  store.getState().clear()
  assert.equal(store.getState().tokens,null)
  assert.throws(() => store.getState().install(before.generation,result))
})
