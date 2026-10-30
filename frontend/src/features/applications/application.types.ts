import type { Status } from '../../types/api.types.ts'
export type Application = {
  id: string; name: string; description: string | null; status: Status
  environments: { id: string; name: 'DEV' | 'TEST' | 'STAGING'; status: Status }[]
}
export type ApplicationPage = { items: Application[]; next_cursor: string | null }
export type ApplicationParams = { cursor?: string | null; status?: Status; limit?: number }
export type AssignedEngineer = { id: string; username: string; status: Status }
export type AssignedEngineerPage = { items: AssignedEngineer[]; next_cursor: string | null }
export type EngineerPageParams = { cursor?: string | null; limit?: number }
export type CreateApplication = { name: string; description?: string }
export type EditApplication = { name?: string; description?: string }
export type ApplicationCommand = { kind: 'create'; values: CreateApplication }
  | { kind: 'edit'; id: string; values: EditApplication }
  | { kind: 'status'; id: string; status: Status }
