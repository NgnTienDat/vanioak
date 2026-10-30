import { z } from 'zod'
import type { EditUser } from './user.types.ts'

const username = z.string().max(100, 'Use at most 100 characters.').refine(value => /\S/.test(value), 'Enter a username.')
export const createUserSchema = z.object({ username, password: z.string().min(1, 'Enter a password.') }).strict()
export const editUserSchema = z.object({ username, password: z.string() }).strict()
export const editUserRequestSchema = z.object({ username: username.optional(), password: z.string().min(1).optional() }).strict()
  .refine(value => Object.values(value).some(field => field !== undefined), 'Change at least one field.')
export function userEditFields(original: string, values: { username: string; password: string }): EditUser {
  return { ...(values.username !== original ? { username: values.username } : {}), ...(values.password !== '' ? { password: values.password } : {}) }
}
