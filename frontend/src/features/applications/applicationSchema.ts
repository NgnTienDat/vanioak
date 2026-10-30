import { z } from 'zod'
const fields = { name: z.string().max(100, 'Use at most 100 characters.'), description: z.string().optional() }
export const applicationSchema = z.strictObject(fields)
export const applicationEditSchema = z.strictObject({ name: fields.name.optional(), description: fields.description })
  .refine(values => values.name !== undefined || values.description !== undefined, 'Change at least one field.')
export const applicationStatusSchema = z.strictObject({ status: z.enum(['ACTIVE', 'DISABLED']) })
