import type { Status } from '../../types/api.types.ts'
export type Engineer = { id: string; username: string; role: 'ENGINEER'; status: Status }
export type UserPage = { items: Engineer[]; next_cursor: string | null }
export type UserParams = { status?: Status; cursor?: string | null; limit?: number }
export type CreateUser = { username: string; password: string }
export type EditUser = { username?: string; password?: string }
export type UserCommand = { kind: 'create'; values: CreateUser } | { kind: 'edit'; id: string; values: EditUser } | { kind: 'status'; id: string; status: Status }
