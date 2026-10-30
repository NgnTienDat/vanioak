export type ApiResponse<T> = { success: boolean; message: string; data: T }
export type Status = 'ACTIVE' | 'DISABLED'
export const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
