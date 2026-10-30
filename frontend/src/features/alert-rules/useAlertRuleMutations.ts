import { useEffect, useMemo } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import type { QueryClient } from '@tanstack/react-query'
import { authStore } from '../../store/authStore.ts'
import type { AuthStore } from '../../store/authStore.ts'
import { applicationApi } from '../../api/application.api.ts'
import { alertRuleApi } from '../../api/alert-rule.api.ts'
import { clients } from '../../api/client.ts'
import { ApiError, malformed, normalizeApiError } from '../../lib/apiError.ts'
import { guardEngineerAccess } from '../application-settings/useAssignedEngineers.ts'
import type { Scope } from '../applications/application.keys.ts'
import { alertRuleKeys } from './alertRule.keys.ts'
import type { AlertRule, AlertRuleCommand, CreateAlertRule } from './alertRule.types.ts'
export type AlertRuleOutcome = { kind: 'confirmed'; rule: AlertRule } | { kind: 'uncertain'; reconciled: boolean }
export function createAlertRuleCommands({ client, session, applicationId, store = authStore, api = alertRuleApi, applications = applicationApi, renew = clients.refresh }: {
  client: QueryClient; session: Scope; applicationId: string; store?: AuthStore; api?: typeof alertRuleApi
  applications?: Pick<typeof applicationApi, 'list'>; renew?: () => Promise<void>
}) {
  let pending: Promise<AlertRuleOutcome> | null = null, controller: AbortController | null = null, uncertain: AlertRuleCommand | null = null
  const root = alertRuleKeys.root(session), guard = (signal: AbortSignal) => guardEngineerAccess(session, signal, store)
  function redact() {
    const state = store.getState()
    if (state.phase !== 'authenticated' || state.generation !== session.generation || state.user?.id !== session.userId) return
    const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[1] === session.generation && query.queryKey[2] === session.userId && query.queryKey[3] === session.role
      && (['applications', 'alert-rules'].includes(String(query.queryKey[0])) || (query.queryKey[4] === applicationId && ['api-keys', 'assigned-engineers', 'logs', 'incidents', 'analytics', 'overview'].includes(String(query.queryKey[0]))))
    void client.cancelQueries({ predicate }); client.removeQueries({ predicate })
  }
  async function retire(signal: AbortSignal) {
    await client.cancelQueries({ queryKey: root }); guard(signal)
    await client.invalidateQueries({ queryKey: root, refetchType: 'none' }); guard(signal)
    client.removeQueries({ queryKey: root })
  }
  async function read(id: string | undefined, signal: AbortSignal, created?: CreateAlertRule): Promise<AlertRule | undefined> {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal); const page = await api.list({ cursor, limit: 50 }, signal); guard(signal)
      const rule = page.items.find(row => created ? row.environment_id === created.environment_id && row.level === created.level : row.id === id)
      if (rule) return rule
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    return undefined
  }
  async function requireScope(environmentId: string, active: boolean, signal: AbortSignal) {
    let cursor: string | null = null
    const seen = new Set<string>()
    do {
      guard(signal); const page = await applications.list({ cursor, limit: 50 }, signal); guard(signal)
      const app = page.items.find(item => item.id === applicationId), env = app?.environments.find(item => item.id === environmentId)
      if (app) {
        if (!env) throw new ApiError(403, 'This rule is outside the selected application.')
        if (active && (app.status !== 'ACTIVE' || env.status !== 'ACTIVE')) throw new ApiError(409, 'An active application and environment are required to create or enable a rule.')
        return
      }
      cursor = page.next_cursor
      if (cursor !== null) { if (seen.has(cursor)) throw malformed(); seen.add(cursor) }
    } while (cursor !== null)
    throw new ApiError(404, 'This application is no longer available.')
  }
  async function reconcile(command: AlertRuleCommand, signal: AbortSignal): Promise<AlertRuleOutcome> {
    uncertain = command
    try {
      await retire(signal); await read(command.kind === 'create' ? undefined : command.id, signal, command.kind === 'create' ? command.values : undefined)
      uncertain = null
      return { kind: 'uncertain', reconciled: true }
    } catch (error) {
      guard(signal)
      if (normalizeApiError(error).status === 403) { redact(); throw normalizeApiError(error) }
      return { kind: 'uncertain', reconciled: false }
    }
  }
  async function run(command: AlertRuleCommand | 'check', signal: AbortSignal): Promise<AlertRuleOutcome> {
    guard(signal)
    if (command === 'check') {
      if (!uncertain) throw new ApiError(409, 'There is no unconfirmed rule change to check.')
      return reconcile(uncertain, signal)
    }
    if (uncertain) throw new ApiError(409, 'Check rules before another change.')
    let submitted = false
    try {
      const rule = command.kind === 'create' ? undefined : await read(command.id, signal)
      if (command.kind !== 'create' && !rule) throw new ApiError(404, 'This rule is no longer listed.')
      await requireScope(command.kind === 'create' ? command.values.environment_id : rule!.environment_id,
        command.kind === 'create' || (command.kind === 'edit' && command.values.enabled === true), signal)
      guard(signal); submitted = true
      const result = command.kind === 'create' ? await api.create(command.values, signal)
        : command.kind === 'edit' ? await api.edit(rule!, command.values, signal) : await api.disable(rule!, signal)
      guard(signal); await retire(signal)
      return { kind: 'confirmed', rule: result }
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
    execute(command: AlertRuleCommand | 'check') {
      if (pending) return pending
      const owned = new AbortController(); controller = owned
      const work = run(command, owned.signal).finally(() => { if (pending === work) { pending = null; controller = null } })
      pending = work; return work
    },
    cancel() { controller?.abort() }, redact,
    get needsReconciliation() { return uncertain !== null },
  }
}
export function useAlertRuleMutations(applicationId: string) {
  const client = useQueryClient(), generation = useStore(authStore, state => state.generation), user = useStore(authStore, state => state.user)
  const userId = user?.id ?? '', role = user?.role ?? 'ENGINEER'
  const action = useMemo(() => createAlertRuleCommands({ client, session: { generation, userId, role }, applicationId }), [client, generation, userId, role, applicationId])
  useEffect(() => {
    const unsubscribe = authStore.subscribe(state => { if (state.phase !== 'authenticated' || state.generation !== generation) action.cancel() })
    return () => { unsubscribe(); action.cancel() }
  }, [action, generation])
  const mutation = useMutation({ mutationFn: (command: AlertRuleCommand | 'check') => action.execute(command), retry: false, gcTime: 0 })
  return { execute: (command: AlertRuleCommand | 'check') => mutation.mutateAsync(command), busy: mutation.isPending,
    error: mutation.error ? normalizeApiError(mutation.error) : null, reset: mutation.reset, needsReconciliation: action.needsReconciliation, redact: action.redact }
}
