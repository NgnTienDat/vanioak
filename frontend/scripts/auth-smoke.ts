import { createClients } from '../src/api/client.ts'
import { createAuthApi } from '../src/api/auth.api.ts'
import { createApplicationApi } from '../src/api/application.api.ts'
import { createAuthStore } from '../src/store/authStore.ts'
import { ApiError } from '../src/lib/apiError.ts'

// Opt-in only. This script never creates infrastructure, accounts or applications.
async function main() {
  const { F1_SMOKE_APPROVED, F1_SMOKE_ORIGIN, F1_SMOKE_USERNAME, F1_SMOKE_PASSWORD } = process.env
  if (F1_SMOKE_APPROVED !== 'true' || !F1_SMOKE_ORIGIN || !F1_SMOKE_USERNAME || !F1_SMOKE_PASSWORD) {
    throw new Error('Smoke requires separate approval, a safe origin and run-owned credentials supplied through the process environment.')
  }
  const origin = new URL(F1_SMOKE_ORIGIN)
  if (!['http:','https:'].includes(origin.protocol) || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error('Smoke origin must be an approved HTTP(S) origin without embedded credentials or path.')
  const store = createAuthStore()
  const transport = createClients(store)
  const baseURL = new URL('/api/v1', origin).toString()
  transport.authClient.defaults.baseURL = baseURL
  transport.apiClient.defaults.baseURL = baseURL
  const auth = createAuthApi(transport.authClient)
  const applications = createApplicationApi(transport.apiClient)
  transport.bindRefresh(auth.refresh)
  let cleanupConfirmed = false
  let cleanupFailure: unknown
  try {
    await store.getState().login({ username: F1_SMOKE_USERNAME, password: F1_SMOKE_PASSWORD },auth.login)
    console.info('Login: PASS')
    await applications.list()
    console.info('Applications 1: PASS')
    await transport.refresh()
    console.info('Refresh: PASS')
    await applications.list()
    console.info('Applications 2: PASS')
  } finally {
    try {
      const current = store.getState().tokens
      if (current) {
        await auth.logout(current)
        cleanupConfirmed = true
        console.info('Logout: PASS')
      }
    } catch (error) { cleanupFailure = error }
    finally {
      store.getState().clear()
      if (!cleanupConfirmed) console.error('Server session cleanup: UNCONFIRMED')
    }
  }
  if (cleanupFailure) throw cleanupFailure
}

void main().catch(error => {
  console.error(error instanceof ApiError ? `Smoke failed (HTTP ${error.status}).` : 'Smoke not completed. Verify separate approval and safe configuration.')
  process.exitCode = 1
})
