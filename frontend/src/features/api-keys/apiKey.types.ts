export type ApiKey = { id: string; environment_id: string; key_prefix: string; status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  created_at: string; expires_at: string | null; revoked_at: string | null }
export type ApiKeyPage = { items: ApiKey[]; next_cursor: string | null }
export type ApiKeyScope = { applicationId: string; environmentId: string }
export type ApiKeyParams = { cursor?: string | null; limit?: number }
export type ApiKeyCommand = { kind: 'create'; expires_at?: string } | { kind: 'rotate' | 'revoke'; id: string }
