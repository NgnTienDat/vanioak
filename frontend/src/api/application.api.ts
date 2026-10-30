import type { AxiosInstance } from 'axios'
import { clients, decodeEnvelope } from './client.ts'
import { isRecord } from '../types/api.types.ts'
import type { Application, ApplicationPage, ApplicationParams, CreateApplication, EditApplication, AssignedEngineerPage, EngineerPageParams } from '../features/applications/application.types.ts'
import type { Status } from '../types/api.types.ts'
import { applicationSchema, applicationEditSchema, applicationStatusSchema } from '../features/applications/applicationSchema.ts'
import { ApiError } from '../lib/apiError.ts'
import { malformed } from '../lib/apiError.ts'
const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function application(value: unknown): value is Application {
  return isRecord(value) && typeof value.id === 'string' && !!value.id && typeof value.name === 'string'
    && (value.description === null || typeof value.description === 'string') && (value.status === 'ACTIVE' || value.status === 'DISABLED')
    && Array.isArray(value.environments) && value.environments.every(e => isRecord(e) && typeof e.id === 'string' && !!e.id
      && (e.name === 'DEV' || e.name === 'TEST' || e.name === 'STAGING') && (e.status === 'ACTIVE' || e.status === 'DISABLED'))
}
export function createApplicationApi(client: AxiosInstance) {
  function decode(value: unknown, id?: string): Application {
    const data = decodeEnvelope(value)
    if (!application(data) || !idPattern.test(data.id) || !data.environments.every(env => idPattern.test(env.id)) || (id !== undefined && data.id !== id)) throw malformed()
    return data
  }
  function identifier(id: string) {
    if (!idPattern.test(id)) throw new ApiError(400, 'Select a valid application.')
    return id
  }
  return {
    async list(params: ApplicationParams = {}, signal?: AbortSignal): Promise<ApplicationPage> {
      const data = decodeEnvelope((await client.get('/applications', { params: { limit: params.limit ?? 50, cursor: params.cursor ?? undefined, status: params.status }, signal })).data)
      if (!isRecord(data) || !Array.isArray(data.items) || !data.items.every(application)
        || !(data.next_cursor === null || (typeof data.next_cursor === 'string' && !!data.next_cursor))) throw malformed()
      return data as ApplicationPage
    },
    async create(values: CreateApplication, signal?: AbortSignal): Promise<Application> {
      const parsed = applicationSchema.safeParse(values)
      if (!parsed.success) throw new ApiError(400, 'Please check the application details.')
      return decode((await client.post('/applications', parsed.data, { signal })).data)
    },
    async edit(id: string, values: EditApplication, signal?: AbortSignal): Promise<Application> {
      const parsed = applicationEditSchema.safeParse(values)
      if (!parsed.success) throw new ApiError(400, 'Change at least one supported field.')
      return decode((await client.patch(`/applications/${identifier(id)}`, parsed.data, { signal })).data, id)
    },
    async status(id: string, status: Status, signal?: AbortSignal): Promise<Application> {
      const parsed = applicationStatusSchema.safeParse({ status })
      if (!parsed.success) throw new ApiError(400, 'Select a valid application status.')
      const data = decode((await client.patch(`/applications/${identifier(id)}`, parsed.data, { signal })).data, id)
      if (data.status !== status) throw malformed()
      return data
    },
  }
}
export const applicationApi = createApplicationApi(clients.apiClient)

export function createApplicationAccessApi(client: AxiosInstance) {
  function path(id: string, userId?: string) {
    if (!idPattern.test(id) || (userId !== undefined && !idPattern.test(userId))) throw new ApiError(400, 'Select a valid application and engineer.')
    return `/applications/${id}/engineers${userId === undefined ? '' : `/${userId}`}`
  }
  async function command(method: 'post' | 'delete', id: string, userId: string, signal?: AbortSignal): Promise<void> {
    const url = path(id, userId)
    const response = method === 'post' ? await client.post(url, undefined, { signal }) : await client.delete(url, { signal })
    const data = decodeEnvelope(response.data)
    if (!isRecord(data) || data.application_id !== id || data.user_id !== userId) throw malformed()
  }
  return {
    async list(id: string, params: EngineerPageParams = {}, signal?: AbortSignal): Promise<AssignedEngineerPage> {
      const limit = params.limit ?? 50
      if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'Select a valid page size.')
      const data = decodeEnvelope((await client.get(path(id), { params: { limit, cursor: params.cursor ?? undefined }, signal })).data)
      if (!isRecord(data) || !Array.isArray(data.items) || data.items.length > limit || !data.items.every(row => isRecord(row)
        && typeof row.id === 'string' && idPattern.test(row.id) && typeof row.username === 'string'
        && (row.status === 'ACTIVE' || row.status === 'DISABLED'))
        || !(data.next_cursor === null || (typeof data.next_cursor === 'string' && !!data.next_cursor))) throw malformed()
      return { items: data.items.map(row => ({ id: row.id as string, username: row.username as string, status: row.status as Status })), next_cursor: data.next_cursor as string | null }
    },
    grant: (id: string, userId: string, signal?: AbortSignal) => command('post', id, userId, signal),
    remove: (id: string, userId: string, signal?: AbortSignal) => command('delete', id, userId, signal),
  }
}
export const applicationAccessApi = createApplicationAccessApi(clients.apiClient)
