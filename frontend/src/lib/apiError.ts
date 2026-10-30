import axios from 'axios'
export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) { super(message); this.status = status; this.name = 'ApiError' }
}
export const malformed = () => new ApiError(502, 'The server returned an invalid response. Please try again.')
export const ended = () => new ApiError(401, 'This session has ended. Please sign in again.')
export function normalizeApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  const status = axios.isCancel(error) ? 499 : axios.isAxiosError(error) ? error.response?.status ?? 0 : 0
  const message = status === 401 ? 'Your credentials or session are no longer valid.'
    : status === 403 ? 'You do not have permission to access this resource.'
      : status === 400 ? 'Please check the submitted values.' : status === 499 ? 'Request cancelled.'
        : status === 0 ? 'Unable to reach the service. Check your connection and try again.' : 'The service could not complete the request.'
  return new ApiError(status, message)
}
