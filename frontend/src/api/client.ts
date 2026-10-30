import axios from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { authStore } from '../store/authStore.ts'
import type { AuthStore } from '../store/authStore.ts'
import type { TokenPair } from '../features/auth/auth.types.ts'
import { ApiError, ended, malformed, normalizeApiError } from '../lib/apiError.ts'
import { isRecord } from '../types/api.types.ts'
import type { ApiResponse } from '../types/api.types.ts'

type AuthConfig = InternalAxiosRequestConfig & { session?: { generation: number; revision: number }; replayed?: boolean }
export function apiBase(value: unknown = '/api/v1') {
  if (value !== '/api/v1' && value !== '/api/v1/') throw new ApiError(0, 'VITE_API_BASE_URL must be /api/v1 on this origin.')
  return '/api/v1'
}
export function decodeEnvelope(value: unknown): unknown {
  if (!isRecord(value) || value.success !== true || typeof value.message !== 'string' || !('data' in value)) throw malformed()
  return (value as ApiResponse<unknown>).data
}
export function createClients(store: AuthStore, options: { adapter?: AxiosAdapter; baseURL?: string; now?: () => number } = {}) {
  const baseURL = apiBase(options.baseURL)
  const settings = { baseURL, timeout: 15000, withCredentials: false, adapter: options.adapter, headers: { Accept: 'application/json' } }
  const authClient = axios.create(settings), apiClient = axios.create(settings)
  const now = options.now ?? Date.now
  let renewal: Promise<void> | null = null
  let refreshOperation: ((token: string) => Promise<TokenPair>) | null = null
  function active(generation: number) {
    const s = store.getState()
    if (s.generation !== generation || s.phase !== 'authenticated' || !s.tokens) throw ended()
    return s
  }
  async function refresh() {
    if (renewal) return renewal
    const s = store.getState(), generation = s.generation, token = s.tokens?.refresh_token
    if (!token || !s.user || !refreshOperation) throw ended()
    const operation = refreshOperation
    const work = (async () => {
      try {
        const pair = await operation(token)
        store.getState().install(generation, pair)
      } catch {
        if (store.getState().generation === generation) store.getState().clear('Your session expired or could not be renewed. Please sign in again.')
        throw ended()
      }
    })()
    renewal = work
    try { await work } finally { if (renewal === work) renewal = null }
  }
  function checkSignal(config: InternalAxiosRequestConfig) {
    if (config.signal?.aborted) throw new ApiError(499, 'Request cancelled.')
  }
  apiClient.interceptors.request.use(async raw => {
    const config = raw as AuthConfig
    checkSignal(config)
    if (!config.url?.startsWith('/') || config.url.startsWith('//') || config.url.startsWith('/auth/')) throw new ApiError(0, 'Only relative protected API paths are allowed.')
    const generation = config.session?.generation ?? store.getState().generation
    const s = active(generation)
    if (!config.replayed && now() >= s.expiresAt) await refresh()
    checkSignal(config)
    const current = active(generation)
    config.session = { generation, revision: current.revision }
    config.headers.set('Authorization', `Bearer ${current.tokens!.access_token}`)
    return config
  })
  apiClient.interceptors.response.use(response => {
    const config = response.config as AuthConfig
    checkSignal(config)
    active(config.session?.generation ?? -1)
    return response
  }, async error => {
    const config = (axios.isAxiosError(error) ? error.config : undefined) as AuthConfig | undefined
    if (!config?.session) throw normalizeApiError(error)
    checkSignal(config)
    const state = active(config.session.generation)
    const status = axios.isAxiosError(error) ? error.response?.status : undefined
    if (status === 401 && config.method?.toUpperCase() === 'GET' && !config.replayed) {
      config.replayed = true
      if (state.revision === config.session.revision) await refresh()
      checkSignal(config); active(config.session.generation)
      return apiClient.request(config)
    }
    if (status === 401 && config.replayed) store.getState().clear('Your session expired. Please sign in again.')
    throw normalizeApiError(error)
  })
  authClient.interceptors.response.use(response => response, error => { throw normalizeApiError(error) })
  return { authClient, apiClient, refresh, waitForRefresh: async () => { if (renewal) await renewal },
    bindRefresh: (operation: (token: string) => Promise<TokenPair>) => { refreshOperation = operation } }
}
export const clients = createClients(authStore, { baseURL: import.meta.env?.VITE_API_BASE_URL ?? '/api/v1' })
