import { useEffect, useMemo } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { applicationApi, applicationAccessApi } from '../../api/application.api.ts'
import { userApi } from '../../api/user.api.ts'
import { clients } from '../../api/client.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { applicationKeys } from '../applications/application.keys.ts'
import type { Scope } from '../applications/application.keys.ts'
import { userKeys } from '../users/user.keys.ts'
import { engineerKeys } from './engineer.keys.ts'
import { guardEngineerAccess } from './useAssignedEngineers.ts'

export type EngineerCommand = { kind: 'grant' | 'remove'; userId: string }
export type EngineerOutcome = { kind: 'confirmed'; assigned: boolean } | { kind: 'uncertain'; reconciled: boolean; assigned?: boolean }

export function createEngineerAccess({ client, session, applicationId, store = authStore, api = applicationAccessApi,
  applications = applicationApi, users = userApi, renew = clients.refresh }: {
  client: QueryClient; session: Scope; applicationId: string; store?: AuthStore; api?: typeof applicationAccessApi
  applications?: Pick<typeof applicationApi, 'list'>; users?: Pick<typeof userApi, 'get'>; renew?: () => Promise<void>
}) {
  let pending: Promise<EngineerOutcome> | null = null, controller: AbortController | null = null, uncertain: EngineerCommand | null = null
  const root = engineerKeys.root(session, applicationId)
  const guard = (signal: AbortSignal) => guardEngineerAccess(session, signal, store)
  async function retire(signal: AbortSignal) {
    await client.cancelQueries({ queryKey: root }); guard(signal)
    await client.invalidateQueries({ queryKey: root, refetchType: 'none' }); guard(signal)
    client.removeQueries({ queryKey: root })
  }
  function deny(signal: AbortSignal) {
    guard(signal)
    const roots = [root, userKeys.root(session), applicationKeys.root(session)]
    for (const key of roots) { void client.cancelQueries({ queryKey: key }); client.removeQueries({ queryKey: key }) }
    const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[1] === session.generation && query.queryKey[2] === session.userId && query.queryKey[3] === session.role
      && query.queryKey[4] === applicationId && ['logs', 'incidents', 'analytics', 'overview'].includes(String(query.queryKey[0]))
    void client.cancelQueries({ predicate }); client.removeQueries({ predicate })
  }
  async function membership(userId: string, signal: AbortSignal) {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal)
      const page = await api.list(applicationId, { cursor, limit: 50 }, signal); guard(signal)
      if (page.items.some(user => user.id === userId)) return true
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    return false
  }
  async function activeApplication(signal: AbortSignal) {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal)
      const page = await applications.list({ cursor, limit: 50 }, signal); guard(signal)
      const app = page.items.find(row => row.id === applicationId)
      if (app) { if (app.status !== 'ACTIVE') throw new ApiError(409, 'Enable this application before granting access.'); return }
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    throw new ApiError(404, 'This application is no longer available.')
  }
  async function reconcile(command: EngineerCommand, signal: AbortSignal): Promise<EngineerOutcome> {
    uncertain = command
    try {
      await retire(signal)
      const assigned = await membership(command.userId, signal)
      uncertain = null
      return { kind: 'uncertain', reconciled: true, assigned }
    } catch (error) {
      guard(signal)
      if (normalizeApiError(error).status === 403) { await deny(signal); throw normalizeApiError(error) }
      return { kind: 'uncertain', reconciled: false }
    }
  }
  async function run(command: EngineerCommand | 'check', signal: AbortSignal): Promise<EngineerOutcome> {
    guard(signal)
    if (command === 'check') {
      if (!uncertain) throw new ApiError(409, 'There is no unconfirmed access change to check.')
      return reconcile(uncertain, signal)
    }
    if (uncertain) throw new ApiError(409, 'Check current assignments before another change.')
    let submitted = false
    try {
      const assigned = await membership(command.userId, signal)
      if (command.kind === 'grant') {
        await activeApplication(signal)
        guard(signal)
        const user = await users.get(command.userId, signal); guard(signal)
        if (user.status !== 'ACTIVE') throw new ApiError(409, 'Only active Engineers can be granted access.')
      }
      if (assigned !== (command.kind === 'grant')) {
        guard(signal); submitted = true
        await api[command.kind](applicationId, command.userId, signal); guard(signal)
      }
      await retire(signal)
      return { kind: 'confirmed', assigned: command.kind === 'grant' }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { await deny(signal); throw safe }
      if (submitted && safe.status === 401) { uncertain = command; await renew(); guard(signal); return reconcile(command, signal) }
      if (!submitted || [400, 404, 409].includes(safe.status)) throw safe
      return reconcile(command, signal)
    }
  }
  return {
    execute(command: EngineerCommand | 'check') {
      if (pending) return pending
      const owned = new AbortController(); controller = owned
      const work = run(command, owned.signal).finally(() => { if (pending === work) { pending = null; controller = null } })
      pending = work
      return work
    },
    cancel() { controller?.abort() },
    redact() {
      const state = store.getState()
      if (state.phase === 'authenticated' && state.generation === session.generation && state.user?.id === session.userId && state.user.role === 'ADMIN') deny(new AbortController().signal)
    },
    get needsReconciliation() { return uncertain !== null },
  }
}
export function useEngineerAccess(applicationId: string) {
  const client = useQueryClient(), generation = useStore(authStore, state => state.generation), user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER'
  const action = useMemo(() => createEngineerAccess({ client, session: { generation, userId, role }, applicationId }), [client, generation, userId, role, applicationId])
  useEffect(() => {
    const unsubscribe = authStore.subscribe(state => { if (state.phase !== 'authenticated' || state.generation !== generation) action.cancel() })
    return () => { unsubscribe(); action.cancel() }
  }, [action, generation])
  const mutation = useMutation({ mutationFn: (command: EngineerCommand | 'check') => action.execute(command), retry: false, gcTime: 0 })
  return { execute: (command: EngineerCommand) => mutation.mutateAsync(command), check: () => mutation.mutateAsync('check'),
    busy: mutation.isPending, error: mutation.error ? normalizeApiError(mutation.error) : null, reset: mutation.reset,
    needsReconciliation: action.needsReconciliation, redact: action.redact }
}
