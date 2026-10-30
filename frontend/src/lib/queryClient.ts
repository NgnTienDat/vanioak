import { QueryClient, QueryCache } from '@tanstack/react-query'
import type { AuthStore } from '../store/authStore.ts'
import { ApiError } from './apiError.ts'
export function createQueryClient() {
  return new QueryClient({ queryCache: new QueryCache({ onError: (error, query) => {
    if (error instanceof ApiError && error.status === 403) query.setState({ data: undefined })
  } }), defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false },
    mutations: { retry: false, gcTime: 0 },
  } })
}
export function bindSessionCache(client: QueryClient, store: AuthStore) {
  return store.subscribe((state, previous) => {
    if (state.generation !== previous.generation || (state.phase === 'logging-out' && previous.phase !== 'logging-out')) {
      void client.cancelQueries()
      client.clear()
    }
  })
}
export const queryClient = createQueryClient()
