import { createStore } from 'zustand/vanilla'
import type { LoginRequest, LoginResponse, TokenPair, User } from '../features/auth/auth.types.ts'
import { ApiError, ended, normalizeApiError } from '../lib/apiError.ts'
export type AuthState = {
  user: User | null; tokens: TokenPair | null; expiresAt: number; generation: number; revision: number
  phase: 'anonymous' | 'authenticating' | 'authenticated' | 'logging-out'; notice: string
  clear: (notice?: string) => void
  install: (generation: number, tokens: TokenPair) => void
  login: (values: LoginRequest, send: (values: LoginRequest) => Promise<LoginResponse>) => Promise<void>
  logout: (wait: () => Promise<void>, send: (tokens: TokenPair) => Promise<void>) => Promise<void>
}
export function createAuthStore(now: () => number = Date.now) {
  let signingOut: Promise<void> | null = null
  return createStore<AuthState>((set, get) => ({
    user: null, tokens: null, expiresAt: 0, generation: 0, revision: 0, phase: 'anonymous', notice: '',
    clear(notice = '') { set(s => ({ tokens: null, user: null, expiresAt: 0, generation: s.generation + 1, phase: 'anonymous', notice })) },
    install(generation, tokens) {
      if (get().generation !== generation || !get().user) throw ended()
      set(s => ({ tokens: { access_token: tokens.access_token, refresh_token: tokens.refresh_token, token_type: tokens.token_type, expires_in: tokens.expires_in },
        expiresAt: now() + tokens.expires_in * 1000, revision: s.revision + 1 }))
    },
    async login(values, send) {
      if (signingOut || get().phase === 'authenticating' || get().phase === 'logging-out') throw new ApiError(409, 'Please wait for the current action.')
      const generation = get().generation + 1
      set({ generation, tokens: null, user: null, phase: 'authenticating', notice: '' })
      try {
        const data = await send(values)
        if (generation !== get().generation) throw ended()
        set(s => ({ tokens: { access_token: data.access_token, refresh_token: data.refresh_token, token_type: data.token_type, expires_in: data.expires_in }, user: data.user,
          expiresAt: now() + data.expires_in * 1000, revision: s.revision + 1, phase: 'authenticated' }))
      } catch (error) {
        if (get().generation === generation) get().clear(normalizeApiError(error).message)
        throw normalizeApiError(error)
      }
    },
    logout(wait, send) {
      if (signingOut) return signingOut
      const pendingLogin = get().phase === 'authenticating'
      set(s => ({ phase: 'logging-out', generation: s.generation + (pendingLogin ? 1 : 0) }))
      const work = (async () => {
        let notice = pendingLogin ? 'Signed out locally. Any in-flight sign-in session revocation is unconfirmed.' : ''
        try {
          try { await wait() } catch { notice = 'Signed out locally. Server session revocation could not be confirmed.' }
          const tokens = get().tokens
          if (tokens) await send(tokens)
        } catch { notice = 'Signed out locally. Server session revocation could not be confirmed.' }
        finally { get().clear(notice) }
      })()
      signingOut = work
      void work.finally(() => { if (signingOut === work) signingOut = null })
      return work
    },
  }))
}
export type AuthStore = ReturnType<typeof createAuthStore>
export const authStore = createAuthStore()
