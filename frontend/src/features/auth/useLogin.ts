import { useRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authApi } from '../../api/auth.api.ts'
import { authStore } from '../../store/authStore.ts'
import { ApiError } from '../../lib/apiError.ts'
import type { LoginValues } from './loginSchema.ts'
export function useLogin() {
  const phase = useStore(authStore, s => s.phase)
  const notice = useStore(authStore, s => s.notice)
  const input = useRef<LoginValues | null>(null)
  const mutation = useMutation({ mutationFn: async () => {
    const values = input.current
    input.current = null
    if (!values) throw new ApiError(400, 'Login values are required.')
    await authStore.getState().login(values, authApi.login)
  } })
  async function login(values: LoginValues) {
    const current = authStore.getState().phase
    if (input.current || current === 'authenticating' || current === 'logging-out') throw new ApiError(409, 'Please wait for the current action.')
    input.current = values
    try { await mutation.mutateAsync() }
    finally { input.current = null; mutation.reset() }
  }
  return { login, busy: phase === 'authenticating' || phase === 'logging-out' || mutation.isPending, notice }
}
