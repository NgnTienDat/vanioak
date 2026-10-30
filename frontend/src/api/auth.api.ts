import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { isRecord } from '../types/api.types.ts'
import type { LoginRequest, LoginResponse, TokenPair, User } from '../features/auth/auth.types.ts'
import { malformed } from '../lib/apiError.ts'
function tokens(value: unknown): value is TokenPair {
  return isRecord(value) && typeof value.access_token === 'string' && !!value.access_token && typeof value.refresh_token === 'string' && !!value.refresh_token
    && value.token_type === 'Bearer' && Number.isSafeInteger(value.expires_in) && Number(value.expires_in) > 0
}
function user(value: unknown): value is User {
  return isRecord(value) && typeof value.id === 'string' && !!value.id && typeof value.username === 'string' && (value.role === 'ADMIN' || value.role === 'ENGINEER')
}
export function createAuthApi(client: AxiosInstance) {
  return {
    async login(values: LoginRequest): Promise<LoginResponse> {
      const data = decodeEnvelope((await client.post('/auth/login', values)).data)
      const identity = isRecord(data) ? data.user : null
      if (!tokens(data) || !user(identity)) throw malformed()
      return { access_token: data.access_token, refresh_token: data.refresh_token, token_type: data.token_type, expires_in: data.expires_in,
        user: { id: identity.id, username: identity.username, role: identity.role } }
    },
    async refresh(token: string): Promise<TokenPair> {
      const data = decodeEnvelope((await client.post('/auth/refresh', { refresh_token: token })).data)
      if (!tokens(data)) throw malformed()
      return { access_token: data.access_token, refresh_token: data.refresh_token, token_type: data.token_type, expires_in: data.expires_in }
    },
    async logout(pair: TokenPair): Promise<void> {
      const data = decodeEnvelope((await client.post('/auth/logout', { refresh_token: pair.refresh_token }, { headers: { Authorization: `Bearer ${pair.access_token}` } })).data)
      if (data !== null) throw malformed()
    },
  }
}
export const authApi = createAuthApi(clients.authClient)
