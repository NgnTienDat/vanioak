import type { AxiosInstance } from 'axios'
import { z } from 'zod'
import { clients, decodeEnvelope } from './client.ts'
import { ApiError, malformed, normalizeApiError } from '../lib/apiError.ts'
import { apiKeySchema } from '../features/api-keys/apiKeySchema.ts'
import type { ApiKey, ApiKeyPage, ApiKeyScope, ApiKeyParams } from '../features/api-keys/apiKey.types.ts'
const uuid = z.uuid(), instant = z.iso.datetime({ offset: true })
const metadata = z.object({ id: uuid, environment_id: uuid, key_prefix: z.string().regex(/^[0-9a-f]{16}$/), status: z.enum(['ACTIVE', 'REVOKED', 'EXPIRED']),
  created_at: instant, expires_at: instant.nullable(), revoked_at: instant.nullable() })
function decode(value: unknown, scope: ApiKeyScope, id?: string): ApiKey {
  const parsed = metadata.safeParse(value)
  if (!parsed.success || parsed.data.environment_id !== scope.environmentId || (id && parsed.data.id !== id)) throw malformed()
  return parsed.data
}
function identifier(id: string) { if (!uuid.safeParse(id).success) throw new ApiError(400, 'Select a valid API key scope.'); return id }
function path(scope: ApiKeyScope) { return `/applications/${identifier(scope.applicationId)}/environments/${identifier(scope.environmentId)}/api-keys` }
export function createApiKeyApi(client: AxiosInstance) {
  async function issued(request: () => Promise<{ data: unknown }>, scope: ApiKeyScope, consume: (secret: string) => void, previous?: ApiKey): Promise<ApiKey> {
    try {
      const data = decodeEnvelope((await request()).data)
      if (typeof data !== 'object' || data === null || !('credential' in data) || !('api_key' in data)) throw malformed()
      const credential = decode(data.credential, scope)
      if (typeof data.api_key !== 'string' || !/^[0-9a-f]{16}\.[A-Za-z0-9_-]{43}$/.test(data.api_key)
        || !data.api_key.startsWith(`${credential.key_prefix}.`) || credential.status !== 'ACTIVE'
        || (previous && (credential.id === previous.id || credential.expires_at !== previous.expires_at))) throw malformed()
      consume(data.api_key)
      return credential
    } catch (error) { throw normalizeApiError(error) }
  }
  return {
    async list(scope: ApiKeyScope, params: ApiKeyParams = {}, signal?: AbortSignal): Promise<ApiKeyPage> {
      try {
        const limit = params.limit ?? 50
        if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'Select a valid page size.')
        const data = decodeEnvelope((await client.get(path(scope), { params: { limit, cursor: params.cursor ?? undefined }, signal })).data)
        const page = z.object({ items: z.array(z.unknown()).max(limit), next_cursor: z.string().min(1).nullable() }).safeParse(data)
        if (!page.success) throw malformed()
        return { items: page.data.items.map(item => decode(item, scope)), next_cursor: page.data.next_cursor }
      } catch (error) { throw normalizeApiError(error) }
    },
    async create(scope: ApiKeyScope, values: { expires_at?: string }, consume: (secret: string) => void, signal?: AbortSignal): Promise<ApiKey> {
      const parsed = apiKeySchema().safeParse(values)
      if (!parsed.success) throw new ApiError(400, 'Enter a valid future UTC expiration.')
      return issued(() => client.post(path(scope), parsed.data, { signal }), scope, consume)
    },
    rotate: async (scope: ApiKeyScope, key: ApiKey, consume: (secret: string) => void, signal?: AbortSignal) => {
      path(scope)
      if (key.environment_id !== scope.environmentId) throw new ApiError(403, 'This API key belongs to another environment.')
      return issued(() => client.post(`/api-keys/${identifier(key.id)}/rotate`, undefined, { signal }), scope, consume, key)
    },
    async revoke(scope: ApiKeyScope, id: string, signal?: AbortSignal): Promise<ApiKey> {
      try {
        path(scope)
        const data = decode(decodeEnvelope((await client.delete(`/api-keys/${identifier(id)}`, { signal })).data), scope, id)
        if (data.status !== 'REVOKED') throw malformed()
        return data
      } catch (error) { throw normalizeApiError(error) }
    },
  }
}
export const apiKeyApi = createApiKeyApi(clients.apiClient)
