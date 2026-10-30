import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { isRecord } from '../types/api.types.ts'
import type { CreateUser, EditUser, Engineer, UserPage, UserParams } from '../features/users/user.types.ts'
import { createUserSchema, editUserRequestSchema } from '../features/users/userSchema.ts'
import { normalizeApiError } from '../lib/apiError.ts'
import { ApiError, malformed } from '../lib/apiError.ts'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function decode(value: unknown): Engineer {
  if (!isRecord(value) || typeof value.id !== 'string' || !uuid.test(value.id) || typeof value.username !== 'string' || !/\S/.test(value.username) || value.username.length > 100
    || value.role !== 'ENGINEER' || (value.status !== 'ACTIVE' && value.status !== 'DISABLED')) throw malformed()
  return { id: value.id, username: value.username, role: value.role, status: value.status }
}
export function createUserApi(client: AxiosInstance) {
  return {
    async list(params: UserParams = {}, signal?: AbortSignal): Promise<UserPage> {
      const limit = params.limit ?? 50
      if (!Number.isInteger(limit) || limit < 1 || limit > 100 || (params.status !== undefined && params.status !== 'ACTIVE' && params.status !== 'DISABLED')) throw new ApiError(400, 'Select a valid directory filter.')
      const data = decodeEnvelope((await client.get('/users', { params: { status: params.status, limit, cursor: params.cursor ?? undefined }, signal })).data)
      if (!isRecord(data) || !Array.isArray(data.items) || data.items.length > limit
        || !(data.next_cursor === null || (typeof data.next_cursor === 'string' && !!data.next_cursor))) throw malformed()
      const items = data.items.map(decode)
      if (params.status && items.some(row => row.status !== params.status)) throw malformed()
      return { items, next_cursor: data.next_cursor as string | null }
    },
    async get(id: string, signal?: AbortSignal): Promise<Engineer> {
      if (!uuid.test(id)) throw new ApiError(400, 'Select a valid engineer.')
      const user = decode(decodeEnvelope((await client.get(`/users/${id}`, { signal })).data))
      if (user.id !== id) throw malformed()
      return user
    },
  }
}
export const userApi = createUserApi(clients.apiClient)

export function createUserManagementApi(client: AxiosInstance) {
  async function write(method: 'post' | 'patch' | 'delete', id: string | null, values?: CreateUser | EditUser | { status: 'ACTIVE' }, signal?: AbortSignal) {
    if (id !== null && !uuid.test(id)) throw new ApiError(400, 'Select a valid engineer.')
    try {
      const result = decode(decodeEnvelope((await client.request({ method, url: id === null ? '/users' : `/users/${id}`, data: values, signal })).data))
      if (id !== null && result.id !== id) throw malformed()
      if (values && 'username' in values && values.username !== undefined && result.username !== values.username) throw malformed()
      if ((method === 'delete' && result.status !== 'DISABLED') || (values && 'status' in values && result.status !== values.status)
        || (method === 'post' && result.status !== 'ACTIVE')) throw malformed()
      return result
    } catch (error) { throw normalizeApiError(error) }
  }
  return {
    async create(values: CreateUser, signal?: AbortSignal) {
      if (!createUserSchema.safeParse(values).success) throw new ApiError(400, 'Check the username and password.')
      return write('post', null, values, signal)
    },
    async edit(id: string, values: EditUser, signal?: AbortSignal) {
      if (!editUserRequestSchema.safeParse(values).success) throw new ApiError(400, 'Change a supported user field.')
      return write('patch', id, values, signal)
    },
    async status(id: string, status: 'ACTIVE' | 'DISABLED', signal?: AbortSignal) {
      if (status !== 'ACTIVE' && status !== 'DISABLED') throw new ApiError(400, 'Select a valid account status.')
      return status === 'ACTIVE' ? write('patch', id, { status }, signal) : write('delete', id, undefined, signal)
    },
  }
}
export const userManagementApi = createUserManagementApi(clients.apiClient)
