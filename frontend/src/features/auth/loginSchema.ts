import { z } from 'zod'
export const loginSchema = z.object({ username: z.string().min(1, 'Username is required.').max(100, 'Username must be at most 100 characters.'), password: z.string().min(1, 'Password is required.') })
export type LoginValues = z.infer<typeof loginSchema>
