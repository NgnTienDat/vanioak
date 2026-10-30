import type { Query, QueryClient } from '@tanstack/react-query'
import type { Scope } from '../applications/application.keys.ts'
import { realtimeClock } from './realtime.types.ts'
import type { RealtimeClock, RealtimeEvent, StreamScope } from './realtime.types.ts'

export function createRealtimeCache(client: QueryClient, session: Scope, scope: StreamScope, options: {
  current: () => boolean; reconcileLogs: () => void; denied: (value: boolean) => void; reconcileIncidents?: () => void; reconcileAnalytics?: () => void; clock?: RealtimeClock
}) {
  const clock = options.clock ?? realtimeClock
  let logTimer: unknown, analyticsTimer: unknown, disposed = false, logs = false, incidents = false
  function matches(query: Query, domains: string[], allEnvironments = false) {
    const key = query.queryKey
    return domains.includes(String(key[0])) && key[1] === session.generation && key[2] === session.userId && key[3] === session.role
      && key[4] === scope.applicationId && (allEnvironments || scope.environmentId === null || key[5] === null || key[5] === scope.environmentId)
  }
  const current = () => !disposed && options.current()
  function stale(domains: string[]) {
    void client.invalidateQueries({ predicate: query => matches(query, domains), refetchType: 'none' })
  }
  function flush() {
    logTimer = undefined
    if (!current()) return
    // Do not starve a slow REST request by moving its window every second.
    if ((logs && client.isFetching({ predicate: query => matches(query, ['logs']) }))
      || (incidents && client.isFetching({ predicate: query => matches(query, ['incidents']) }))) { logTimer = clock.set(flush, 1000); return }
    if (logs) { stale(['logs']); options.reconcileLogs() }
    if (incidents) reconcileIncidents()
    logs = false; incidents = false
  }
  function suspend() {
    if (logTimer !== undefined) clock.clear(logTimer)
    if (analyticsTimer !== undefined) clock.clear(analyticsTimer)
    logTimer = undefined; analyticsTimer = undefined; logs = false; incidents = false
  }
  function reconcileIncidents() {
    if (!options.reconcileIncidents) { void client.invalidateQueries({ predicate: query => matches(query, ['incidents']) }); return }
    void client.cancelQueries({ predicate: query => matches(query, ['incidents']) })
    stale(['incidents']); options.reconcileIncidents()
    void client.invalidateQueries({ predicate: query => matches(query, ['incidents']) && query.queryKey[6] === 'detail' })
  }
  function reconcileAnalytics() {
    if (!options.reconcileAnalytics) { void client.invalidateQueries({ predicate: query => matches(query, ['analytics']) }); return }
    void client.cancelQueries({ predicate: query => matches(query, ['analytics']) })
    stale(['analytics']); options.reconcileAnalytics()
  }
  function flushAnalytics() {
    analyticsTimer = undefined
    if (!current()) return
    if (client.isFetching({ predicate: query => matches(query, ['analytics']) })) { analyticsTimer = clock.set(flushAnalytics, 1000); return }
    reconcileAnalytics()
  }
  return {
    event(event: RealtimeEvent) {
      if (!current()) return
      if (event.type === 'log') {
        logs = true
        if (analyticsTimer === undefined) analyticsTimer = clock.set(flushAnalytics, 30000)
      } else incidents = true
      if (logTimer === undefined) logTimer = clock.set(flush, 1000)
    },
    reconcile(authorized = false) {
      if (!current()) return
      if (authorized) options.denied(false)
      void client.cancelQueries({ predicate: query => matches(query, ['logs']) })
      stale(['logs']); options.reconcileLogs()
      reconcileIncidents()
      reconcileAnalytics()
    },
    forbidden() {
      suspend()
      if (!current()) return
      options.denied(true)
      const predicate = (query: Query) => matches(query, ['logs', 'incidents', 'analytics'], true)
      void client.cancelQueries({ predicate })
      client.removeQueries({ predicate })
    },
    suspend,
    dispose() { suspend(); disposed = true },
  }
}
