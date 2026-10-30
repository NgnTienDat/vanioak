import { z } from 'zod'
const utc = z.iso.datetime()
export const apiKeySchema = (now = Date.now()) => z.object({ expires_at: utc.refine(value => Date.parse(value) > now, 'Expiration must be in the future (UTC).').optional() }).strict()
export const apiKeyFormSchema = z.object({ expires_at: z.string().refine(value => value === '' || (utc.safeParse(value).success && Date.parse(value) > Date.now()), 'Enter a future UTC timestamp ending in Z, or leave blank.') }).strict()
