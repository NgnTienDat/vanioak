import { useMutation } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authApi } from '../../api/auth.api.ts'
import { clients } from '../../api/client.ts'
import { authStore } from '../../store/authStore.ts'
export function useLogout() {
  const phase = useStore(authStore, s => s.phase)
  const mutation = useMutation({ mutationFn: async () => { await authStore.getState().logout(clients.waitForRefresh, authApi.logout) } })
  async function logout() { try { await mutation.mutateAsync() } finally { mutation.reset() } }
  return { logout, busy: phase === 'logging-out' || mutation.isPending }
}
