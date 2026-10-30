import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createClients } from './client.ts'
import { createAuthApi } from './auth.api.ts'
import { createAuthStore } from '../store/authStore.ts'
import { ApiError } from '../lib/apiError.ts'
test('auth domain decodes success envelope only and refresh returns no invented identity', async () => {
  const pair = { access_token: 'access', refresh_token: 'refresh', token_type: 'Bearer', expires_in: 60 }
  const transport = createClients(createAuthStore(), { adapter: async config => ({ data: { success: true, message: 'Success', data: { ...pair, user: { role: 'OWNER' } } }, status: 200, statusText: 'OK', headers: {}, config }) })
  const api = createAuthApi(transport.authClient)
  assert.deepEqual(await api.refresh('old'),pair)
  await assert.rejects(api.login({ username: 'name',password: 'password' }),ApiError)
})
test('login projects only documented identity/token fields before state or mutation handling', async () => {
  const data = { access_token: 'a',refresh_token: 'r',token_type: 'Bearer',expires_in: 60,
    user: { id: 'id',username: 'name',role: 'ADMIN',debug_password: 'private' },debug: 'private' }
  const transport = createClients(createAuthStore(),{ adapter: async config => ({ data: { success: true,message: 'Success',data },status: 200,statusText: 'OK',headers: {},config }) })
  const result = await createAuthApi(transport.authClient).login({ username: 'name',password: 'secret' })
  assert.deepEqual(Object.keys(result).sort(),['access_token','expires_in','refresh_token','token_type','user'])
  assert.deepEqual(Object.keys(result.user).sort(),['id','role','username'])
  assert.doesNotMatch(JSON.stringify(result),/private/)
})
