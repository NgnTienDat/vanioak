import { useEffect, useMemo } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { clients } from '../../api/client.ts'
import { applicationApi } from '../../api/application.api.ts'
import { ApiError, ended, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { applicationKeys } from './application.keys.ts'
import type { Scope } from './application.keys.ts'
import type { Application, ApplicationCommand } from './application.types.ts'

export type ApplicationOutcome = { kind: 'confirmed'; application: Application }
  | { kind: 'uncertain'; reconciled: boolean; application?: Application; notice: string }

export function createApplicationCommands({ client, session, store = authStore, api = applicationApi, renew = clients.refresh }: {
  client: QueryClient; session: Scope; store?: AuthStore; api?: typeof applicationApi; renew?: () => Promise<void>
}) {
  let pending: Promise<ApplicationOutcome> | null = null, controller: AbortController | null = null
  let uncertain: ApplicationCommand | null = null
  function guard(signal: AbortSignal) {
    const state = store.getState()
    if (signal.aborted || state.phase !== 'authenticated' || state.generation !== session.generation || state.user?.id !== session.userId) throw ended()
    if (session.role !== 'ADMIN' || state.user.role !== 'ADMIN') throw new ApiError(403, 'Administrator access is required.')
  }
  async function resetSnapshots(signal: AbortSignal) {
    await client.cancelQueries({ queryKey: applicationKeys.root(session) })
    guard(signal)
    // Retire every cursor/filter snapshot; the list remounts at the first page.
    await client.invalidateQueries({ queryKey: applicationKeys.root(session), refetchType: 'none' })
    guard(signal)
    client.removeQueries({ queryKey: applicationKeys.root(session) })
  }
  function denied(command: ApplicationCommand) {
    void client.cancelQueries({ queryKey: applicationKeys.root(session) })
    client.removeQueries({ queryKey: applicationKeys.root(session) })
    if (command.kind !== 'create') {
      const matches = (key: readonly unknown[]) => ['logs', 'incidents', 'analytics', 'overview'].includes(String(key[0]))
        && key[1] === session.generation && key[2] === session.userId && key[3] === session.role && key[4] === command.id
      void client.cancelQueries({ predicate: query => matches(query.queryKey) })
      client.removeQueries({ predicate: query => matches(query.queryKey) })
    }
  }
  async function read(command: ApplicationCommand, signal: AbortSignal): Promise<Application | undefined> {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal)
      const page = await api.list({ limit: 50, cursor }, signal)
      guard(signal)
      const found = page.items.find(app => command.kind === 'create' ? app.name === command.values.name : app.id === command.id)
      if (found) return found
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    return undefined
  }
  async function reconcile(command: ApplicationCommand, signal: AbortSignal): Promise<ApplicationOutcome> {
    uncertain = command
    try {
      await resetSnapshots(signal)
      const application = await read(command, signal)
      uncertain = null
      return { kind: 'uncertain', reconciled: true, application,
        notice: application ? `Current server record: ${application.name} (${application.status}). The earlier write outcome remains unconfirmed. Review before submitting again.`
          : 'No matching application is currently listed. The earlier write outcome remains unconfirmed. Review before submitting again.' }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { denied(command); throw safe }
      return { kind: 'uncertain', reconciled: false, notice: 'The write may have committed. Current state could not be checked. Check server state before submitting again.' }
    }
  }
  async function run(command: ApplicationCommand | 'check', signal: AbortSignal): Promise<ApplicationOutcome> {
    guard(signal)
    if (command === 'check') {
      if (!uncertain) throw new ApiError(409, 'There is no unconfirmed write to check.')
      return reconcile(uncertain, signal)
    }
    if (uncertain) throw new ApiError(409, 'Check server state before submitting another change.')
    let submitted = false
    try {
      if (command.kind !== 'create') {
        const before = await read(command, signal)
        if (!before) throw new ApiError(404, 'This application is no longer available.')
        if (command.kind === 'edit' && before.status === 'DISABLED') throw new ApiError(409, 'Enable this application before editing its details.')
        if (command.kind === 'status' && before.status === command.status) {
          await resetSnapshots(signal)
          return { kind: 'confirmed', application: before }
        }
      }
      guard(signal); submitted = true
      const application = command.kind === 'create' ? await api.create(command.values, signal)
        : command.kind === 'edit' ? await api.edit(command.id, command.values, signal) : await api.status(command.id, command.status, signal)
      guard(signal)
      await resetSnapshots(signal)
      return { kind: 'confirmed', application }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { denied(command); throw safe }
      if (submitted && safe.status === 401) { await renew(); guard(signal); return reconcile(command, signal) }
      if (!submitted || safe.status === 400 || safe.status === 404 || safe.status === 409) throw safe
      return reconcile(command, signal)
    }
  }
  return {
    execute(command: ApplicationCommand | 'check') {
      if (pending) return pending
      const owned = new AbortController(); controller = owned
      const work = run(command, owned.signal).finally(() => { if (pending === work) { pending = null; controller = null } })
      pending = work
      return work
    },
    cancel() { controller?.abort() },
    get needsReconciliation() { return uncertain !== null },
  }
}

export function useApplicationMutations() {
  const client = useQueryClient()
  const generation = useStore(authStore, state => state.generation)
  const user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER'
  const action = useMemo(() => createApplicationCommands({ client, session: { generation, userId, role } }), [client, generation, userId, role])
  useEffect(() => {
    const unsubscribe = authStore.subscribe(state => { if (state.phase !== 'authenticated' || state.generation !== generation) action.cancel() })
    return () => { unsubscribe(); action.cancel() }
  }, [action, generation])
  const mutation = useMutation({ mutationFn: (command: ApplicationCommand | 'check') => action.execute(command), retry: false, gcTime: 0 })
  return { execute: (command: ApplicationCommand) => mutation.mutateAsync(command), check: () => mutation.mutateAsync('check'),
    reset: mutation.reset, busy: mutation.isPending, error: mutation.error ? normalizeApiError(mutation.error) : null,
    needsReconciliation: action.needsReconciliation }
}
