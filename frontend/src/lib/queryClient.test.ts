import { test } from 'node:test'
import assert from 'node:assert/strict'
import { QueryObserver } from '@tanstack/react-query'
import { bindSessionCache, createQueryClient } from './queryClient.ts'
import { createAuthStore } from '../store/authStore.ts'
import { applicationKeys } from '../features/applications/application.keys.ts'
import { ApiError } from './apiError.ts'
const tick = () => new Promise<void>(resolve => setImmediate(resolve))
test('logout/account generations clear queries and mutations, late uncooperative result cannot refill cache', async () => {
  const client = createQueryClient(), store = createAuthStore(), unbind = bindSessionCache(client,store)
  const key = applicationKeys.page({ generation: 1,userId: 'a',role: 'ENGINEER' },{})
  let finish!: (value: string[]) => void
  const pending = client.fetchQuery({ queryKey: key, queryFn: () => new Promise<string[]>(r => { finish = r }) })
  const rejected = assert.rejects(pending)
  client.getMutationCache().build(client,{ mutationFn: async () => 'old' })
  store.getState().clear()
  await rejected; finish(['old-account']); await tick()
  assert.equal(client.getQueryCache().getAll().length,0)
  assert.equal(client.getMutationCache().getAll().length,0)
  assert.equal(client.getQueryData(key),undefined)
  unbind(); client.clear()
})
test('403 on a cached page redacts previous data without automatic retry', async () => {
  const client = createQueryClient(), key = ['applications','denied']
  client.setQueryData(key,['private'])
  let calls = 0
  await assert.rejects(client.fetchQuery({ queryKey: key, queryFn: async () => { calls++; throw new ApiError(403,'denied') } }))
  assert.equal(client.getQueryData(key),undefined); assert.equal(calls,1)
  client.clear()
})
test('real QueryObserver sees query-owned page result; mutation retry is disabled', async () => {
  const client = createQueryClient()
  const observer = new QueryObserver(client,{ queryKey: ['page'],queryFn: async () => ({ items: ['actual'],next_cursor: null }) })
  const unsubscribe = observer.subscribe(() => {})
  await observer.refetch()
  assert.deepEqual(observer.getCurrentResult().data?.items,['actual'])
  let writes = 0
  const mutation = client.getMutationCache().build(client,{ mutationFn: async () => { writes++; throw new ApiError(401,'expired') } })
  await assert.rejects(mutation.execute(undefined)); assert.equal(writes,1)
  unsubscribe(); client.clear()
})
