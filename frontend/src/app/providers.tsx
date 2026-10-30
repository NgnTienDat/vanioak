import type { ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient, bindSessionCache } from '../lib/queryClient.ts'
import { authStore } from '../store/authStore.ts'
import { clients } from '../api/client.ts'
import { authApi } from '../api/auth.api.ts'

clients.bindRefresh(authApi.refresh)
const unbindCache = bindSessionCache(queryClient, authStore)
if (import.meta.hot) import.meta.hot.dispose(unbindCache)

export function AppProviders({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
