import { useEffect, useMemo, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { clients } from '../../api/client.ts'
import { userApi, userManagementApi } from '../../api/user.api.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import type { Scope } from '../applications/application.keys.ts'
import type { Engineer, UserCommand } from './user.types.ts'

type Check = { id?: string; username?: string; passwordChanged: boolean }
export type UserOutcome = { kind: 'confirmed'; user: Engineer } | { kind: 'uncertain'; reconciled: boolean; notice: string }
export function clearUserPassword(command: UserCommand | 'check') {
  if (command !== 'check' && command.kind !== 'status' && 'password' in command.values) command.values.password = ''
}
export function createUserCommands({ client, session, store = authStore, api = userManagementApi, reads = userApi, renew = clients.refresh }: {
  client: QueryClient; session: Scope; store?: AuthStore; api?: typeof userManagementApi; reads?: typeof userApi; renew?: () => Promise<void>
}) {
  let pending: Promise<UserOutcome> | null = null, controller: AbortController | null = null, uncertain: Check | null = null
  let active: UserCommand | 'check' | null = null
  const guard = (signal: AbortSignal) => guardEngineerAccess(session, signal, store)
  const matches = (key: readonly unknown[], domains: string[]) => domains.includes(String(key[0])) && key[1] === session.generation && key[2] === session.userId && key[3] === session.role
  function redact() {
    const state = store.getState()
    if (state.generation !== session.generation || state.user?.id !== session.userId) return
    const predicate = (query: { queryKey: readonly unknown[] }) => matches(query.queryKey, ['users', 'assigned-engineers', 'applications', 'api-keys', 'alert-rules'])
    void client.cancelQueries({ predicate }); client.removeQueries({ predicate })
  }
  async function retire(signal: AbortSignal) {
    const predicate = (query: { queryKey: readonly unknown[] }) => matches(query.queryKey, ['users', 'assigned-engineers'])
    await client.cancelQueries({ predicate }); guard(signal)
    await client.invalidateQueries({ predicate, refetchType: 'none' }); guard(signal)
    client.removeQueries({ predicate })
  }
  async function read(check: Check, signal: AbortSignal): Promise<Engineer | undefined> {
    guard(signal)
    if (check.id) {
      try { const user = await reads.get(check.id, signal); guard(signal); return user }
      catch (error) { guard(signal); if (normalizeApiError(error).status === 404) return undefined; throw error }
    }
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      const page = await reads.list({ cursor, limit: 50 }, signal); guard(signal)
      const found = page.items.find(user => user.username === check.username)
      if (found) return found
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    return undefined
  }
  async function reconcile(check: Check, signal: AbortSignal): Promise<UserOutcome> {
    uncertain = check
    try {
      await retire(signal)
      const user = await read(check, signal)
      uncertain = null
      return { kind: 'uncertain', reconciled: true, notice: `${user ? `Current account: ${user.username} (${user.status}).` : 'No matching Engineer account is currently available.'} The earlier write remains unconfirmed.${check.passwordChanged ? ' A GET cannot verify the password change. Do not assume the new password was saved.' : ''} Review before another confirmation.` }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { redact(); throw safe }
      return { kind: 'uncertain', reconciled: false, notice: 'The write may have committed. Check current account state before another change. Any password change remains unconfirmed.' }
    }
  }
  async function run(command: UserCommand | 'check', signal: AbortSignal): Promise<UserOutcome> {
    let submitted = false
    let check: Check | null = null
    try {
      guard(signal)
      if (command === 'check') {
        if (!uncertain) throw new ApiError(409, 'There is no unconfirmed user operation to check.')
        return await reconcile(uncertain, signal)
      }
      if (uncertain) throw new ApiError(409, 'Check current account state before another change.')
      check = command.kind === 'create' ? { username: command.values.username, passwordChanged: true }
        : { id: command.id, passwordChanged: command.kind === 'edit' && command.values.password !== undefined }
      if (command.kind !== 'create') {
        const before = await read(check, signal)
        if (!before) throw new ApiError(404, 'This Engineer account is no longer available.')
        if (command.kind === 'status' && before.status === command.status) { await retire(signal); return { kind: 'confirmed', user: before } }
      }
      guard(signal); submitted = true
      const user = command.kind === 'create' ? await api.create(command.values, signal)
        : command.kind === 'edit' ? await api.edit(command.id, command.values, signal) : await api.status(command.id, command.status, signal)
      clearUserPassword(command)
      guard(signal); await retire(signal)
      return { kind: 'confirmed', user }
    } catch (error) {
      clearUserPassword(command)
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { redact(); throw safe }
      if (submitted && check && safe.status === 401) { uncertain = check; await renew(); guard(signal); return await reconcile(check, signal) }
      if (!submitted || !check || [400, 404, 409].includes(safe.status)) throw safe
      return await reconcile(check, signal)
    } finally { clearUserPassword(command) }
  }
  return {
    execute(command: UserCommand | 'check') {
      if (pending) return pending
      const owned = new AbortController(); controller = owned; active = command
      const work = run(command, owned.signal).finally(() => { if (pending === work) { pending = null; controller = null; active = null } })
      pending = work; return work
    },
    cancel() { controller?.abort(); if (active) clearUserPassword(active); active = null }, redact,
    get needsReconciliation() { return uncertain !== null },
  }
}
export function useUserMutations(onOutcome: (outcome: UserOutcome) => void) {
  const client = useQueryClient(), generation = useStore(authStore, state => state.generation), user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER'
  const input = useRef<UserCommand | 'check' | null>(null), pending = useRef<Promise<void> | null>(null), callback = useRef(onOutcome)
  useEffect(() => { callback.current = onOutcome }, [onOutcome])
  const action = useMemo(() => createUserCommands({ client, session: { generation, userId, role } }), [client, generation, userId, role])
  useEffect(() => {
    const clear = () => { action.cancel(); if (input.current) clearUserPassword(input.current); input.current = null }
    const unsubscribe = authStore.subscribe(state => { if (state.phase !== 'authenticated' || state.generation !== generation || state.user?.id !== userId || state.user.role !== role) clear() })
    return () => { unsubscribe(); clear(); callback.current = () => {} }
  }, [action, generation, userId, role])
  const mutation = useMutation({ mutationFn: async () => {
    const command = input.current; input.current = null
    if (!command) throw new ApiError(400, 'Choose a user operation.')
    const outcome = await action.execute(command)
    guardEngineerAccess({ generation, userId, role }, new AbortController().signal)
    callback.current(outcome)
  }, retry: false, gcTime: 0 })
  function execute(command: UserCommand | 'check') {
    if (pending.current) return pending.current
    input.current = command
    const work = mutation.mutateAsync().finally(() => { if (input.current) clearUserPassword(input.current); input.current = null; pending.current = null })
    pending.current = work; return work
  }
  return { execute, busy: mutation.isPending, error: mutation.error ? normalizeApiError(mutation.error) : null,
    reset: mutation.reset, redact: action.redact, needsReconciliation: action.needsReconciliation }
}
