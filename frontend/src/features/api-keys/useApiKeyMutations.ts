import { useEffect, useMemo, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { apiKeyApi } from '../../api/api-key.api.ts'
import { applicationApi } from '../../api/application.api.ts'
import { clients } from '../../api/client.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import type { Scope } from '../applications/application.keys.ts'
import { apiKeyKeys } from './apiKey.keys.ts'
import type { ApiKey, ApiKeyCommand, ApiKeyScope } from './apiKey.types.ts'
export type ApiKeyOutcome = { kind: 'confirmed' | 'uncertain'; reconciled: boolean }
export function createApiKeyCommands({ client, session, scope, store = authStore, api = apiKeyApi, applications = applicationApi, renew = clients.refresh }: {
  client: QueryClient; session: Scope; scope: ApiKeyScope; store?: AuthStore
  api?: typeof apiKeyApi; applications?: Pick<typeof applicationApi, 'list'>; renew?: () => Promise<void>
}) {
  let pending: Promise<ApiKeyOutcome> | null = null, controller: AbortController | null = null, uncertain: ApiKeyCommand | null = null
  const root = apiKeyKeys.root(session, scope)
  function guard(signal: AbortSignal) { guardEngineerAccess(session, signal, store) }
  function redact() {
    const state = store.getState()
    if (state.phase !== 'authenticated' || state.generation !== session.generation || state.user?.id !== session.userId) return
    const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[1] === session.generation && query.queryKey[2] === session.userId && query.queryKey[3] === session.role
      && (['applications', 'alert-rules'].includes(String(query.queryKey[0])) || (query.queryKey[4] === scope.applicationId && ['api-keys', 'assigned-engineers', 'logs', 'incidents', 'analytics', 'overview'].includes(String(query.queryKey[0]))))
    void client.cancelQueries({ predicate }); client.removeQueries({ predicate })
  }
  async function retirePages(signal: AbortSignal) {
    await client.cancelQueries({ queryKey: root }); guard(signal)
    await client.invalidateQueries({ queryKey: root, refetchType: 'none' }); guard(signal)
    client.removeQueries({ queryKey: root })
  }
  async function read(id: string | undefined, signal: AbortSignal): Promise<ApiKey | undefined> {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal); const page = await api.list(scope, { cursor, limit: 50 }, signal); guard(signal)
      if (!id) return undefined
      const key = page.items.find(item => item.id === id)
      if (key) return key
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    return undefined
  }
  async function requireActiveScope(signal: AbortSignal) {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal); const page = await applications.list({ cursor, limit: 50 }, signal); guard(signal)
      const app = page.items.find(item => item.id === scope.applicationId), env = app?.environments.find(item => item.id === scope.environmentId)
      if (app) {
        if (!env) throw new ApiError(403, 'This environment does not belong to the selected application.')
        if (app.status !== 'ACTIVE' || env.status !== 'ACTIVE') throw new ApiError(409, 'An active application and environment are required.')
        return
      }
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    throw new ApiError(404, 'This application is no longer available.')
  }
  async function reconcile(command: ApiKeyCommand, signal: AbortSignal): Promise<ApiKeyOutcome> {
    uncertain = command
    try {
      await retirePages(signal); await read(command.kind === 'create' ? undefined : command.id, signal)
      uncertain = null
      return { kind: 'uncertain', reconciled: true }
    } catch (error) {
      guard(signal)
      if (normalizeApiError(error).status === 403) { redact(); throw normalizeApiError(error) }
      return { kind: 'uncertain', reconciled: false }
    }
  }
  async function run(command: ApiKeyCommand | 'check', signal: AbortSignal, consume?: (secret: string) => void): Promise<ApiKeyOutcome> {
    guard(signal)
    if (command === 'check') {
      if (!uncertain) throw new ApiError(409, 'There is no unconfirmed API key operation to check.')
      return reconcile(uncertain, signal)
    }
    if (uncertain) throw new ApiError(409, 'Check API key metadata before another operation.')
    if (command.kind !== 'revoke' && !consume) throw new ApiError(400, 'A one-time secret dialog is required.')
    let submitted = false
    try {
      const key = command.kind === 'create' ? undefined : await read(command.id, signal)
      if (command.kind !== 'create' && !key) throw new ApiError(404, 'This API key is not in the selected environment.')
      if (command.kind !== 'revoke') await requireActiveScope(signal)
      if (command.kind === 'rotate' && (key!.status !== 'ACTIVE' || (key!.expires_at !== null && Date.parse(key!.expires_at) <= Date.now()))) throw new ApiError(409, 'Only an active, unexpired API key can be rotated.')
      const reveal = (secret: string) => { guard(signal); consume!(secret) }
      guard(signal); submitted = true
      if (command.kind === 'create') await api.create(scope, command.expires_at ? { expires_at: command.expires_at } : {}, reveal, signal)
      else if (command.kind === 'rotate') await api.rotate(scope, key!, reveal, signal)
      else if (key!.status !== 'REVOKED') await api.revoke(scope, command.id, signal)
      guard(signal); await retirePages(signal)
      return { kind: 'confirmed', reconciled: true }
    } catch (error) {
      guard(signal)
      const safe = normalizeApiError(error)
      if (safe.status === 403) { redact(); throw safe }
      if (submitted && safe.status === 401) { uncertain = command; await renew(); guard(signal); return reconcile(command, signal) }
      if (!submitted || [400, 404, 409].includes(safe.status)) throw safe
      return reconcile(command, signal)
    }
  }
  return {
    execute(command: ApiKeyCommand | 'check', consume?: (secret: string) => void) {
      if (pending) return pending
      const owned = new AbortController(); controller = owned
      const work = run(command, owned.signal, consume).finally(() => { if (pending === work) { pending = null; controller = null } })
      pending = work; return work
    },
    cancel() { controller?.abort() }, redact,
    get needsReconciliation() { return uncertain !== null },
  }
}
export function useApiKeyMutations(scope: ApiKeyScope, onReveal: (secret: string) => void, onOutcome: (outcome: ApiKeyOutcome) => void, clearSecret: () => void) {
  const client = useQueryClient(), generation = useStore(authStore, state => state.generation), user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER', applicationId = scope.applicationId, environmentId = scope.environmentId
  const callbacks = useRef({ onReveal, onOutcome, clearSecret }), input = useRef<ApiKeyCommand | 'check' | null>(null), pending = useRef<Promise<void> | null>(null)
  useEffect(() => { callbacks.current = { onReveal, onOutcome, clearSecret } }, [onReveal, onOutcome, clearSecret])
  const action = useMemo(() => createApiKeyCommands({ client, session: { generation, userId, role }, scope: { applicationId, environmentId } }), [client, generation, userId, role, applicationId, environmentId])
  useEffect(() => {
    const unsubscribe = authStore.subscribe(state => { if (state.phase !== 'authenticated' || state.generation !== generation) { action.cancel(); callbacks.current.clearSecret() } })
    return () => { unsubscribe(); action.cancel(); callbacks.current.clearSecret(); callbacks.current = { onReveal: () => {}, onOutcome: () => {}, clearSecret: () => {} }; input.current = null }
  }, [action, generation])
  const mutation = useMutation({ mutationFn: async () => {
    const command = input.current; input.current = null
    if (!command) throw new ApiError(400, 'Choose an API key operation.')
    const outcome = await action.execute(command, secret => callbacks.current.onReveal(secret))
    guardEngineerAccess({ generation, userId, role }, new AbortController().signal)
    callbacks.current.onOutcome(outcome)
  }, retry: false, gcTime: 0 })
  function execute(command: ApiKeyCommand | 'check') {
    if (pending.current) return pending.current
    input.current = command
    const work = mutation.mutateAsync().finally(() => { input.current = null; pending.current = null })
    pending.current = work; return work
  }
  return { execute, busy: mutation.isPending, error: mutation.error ? normalizeApiError(mutation.error) : null,
    reset: mutation.reset, needsReconciliation: action.needsReconciliation, redact: action.redact }
}
