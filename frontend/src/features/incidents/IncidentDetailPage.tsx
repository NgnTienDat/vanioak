import { useState } from 'react'
import type { ReactNode } from 'react'
import { Icon } from '../../components/ui/Icon.tsx'
import { Kpi } from '../../components/ui/Kpi.tsx'
import { Timestamp } from '../../components/ui/Timestamp.tsx'
import { lifecycleLabel } from '../../lib/presentation.ts'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { useWorkspace } from '../workspace/workspaceScope.ts'
import { useIncident, resolutionAudit } from './useIncident.ts'
import { useResolveIncident, canResolveIncident } from './useResolveIncident.ts'
import { ResolveIncidentDialog } from './ResolveIncidentDialog.tsx'
import { StatusBadge } from '../../components/ui/StatusBadge.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { LiveStatus } from '../realtime/LiveStatus.tsx'

export function IncidentDetailPage({ id }: { id: string }) {
  const workspace = useWorkspace()!
  const role = useStore(authStore, state => state.user?.role ?? null)
  const scope = { applicationId: workspace.application.id, environmentId: workspace.scope.environmentId }
  const query = useIncident(scope, id)
  const action = useResolveIncident(scope, id)
  const [confirming, setConfirming] = useState(false)
  const back = <a className="btn btn-ghost" href={`#/applications/${workspace.application.id}/incidents`}><Icon name="left" size={16} /> Back to incidents</a>
  const error = action.error?.status === 403 ? action.error : query.error
  if (workspace.denied || error || !query.data) return <>{back}<LiveStatus realtime={workspace.realtime} /><PageState
    title={workspace.denied || error?.status === 403 ? 'Incident access denied' : error?.status === 404 ? 'Incident not found' : error ? 'Incident unavailable' : 'Loading Incident…'}
    description={workspace.denied ? 'Scoped data was cleared.' : error?.message} error={workspace.denied || !!error} busy={!workspace.denied && !error}
    retry={!workspace.denied && error && error.status !== 403 && error.status !== 404 ? () => { void query.refetch() } : undefined} /></>
  const incident = query.data
  const patch = action.data?.kind === 'resolved' ? action.data.resolution : null
  const status = patch?.status ?? incident.status
  const historyAudit = resolutionAudit(incident)
  const source = patch ? patch.resolution_source : historyAudit.source
  const actor = patch ? patch.resolved_by_user_id : historyAudit.actor
  const resolvedAt = patch ? patch.resolved_at : incident.resolved_at
  const canResolve = status === 'OPEN' && canResolveIncident(incident, role, scope)
  const notice = action.data?.kind === 'resolved' ? 'Incident resolved. Resolution details are shown below.'
    : action.data?.kind === 'already-resolved' ? 'This incident was already resolved. No change was submitted.'
      : action.data?.kind === 'uncertain' ? action.data.notice : undefined
  const resolve = () => { void action.resolve().then(result => { if (result.kind !== 'uncertain') setConfirming(false) }).catch(() => {}) }
  const check = () => { void action.reconcile().catch(() => {}) }
  const rows: [string, ReactNode][] = [['Application',incident.application.name],['Environment',incident.environment.name],['Severity',incident.level],['Fingerprint',incident.fingerprint],
    ['First seen',<Timestamp value={incident.first_seen_at} detail />],['Last seen',<Timestamp value={incident.last_seen_at} detail />],['Resolved at',resolvedAt ? <Timestamp value={resolvedAt} detail /> : 'Not recorded'],['Resolution source',source ?? 'Unknown / legacy'],['Resolved by (user ID)',actor ?? 'Not recorded']]

  return <>
    <div className="incident-back">{back}</div><div className="page-heading"><div><div className="eyebrow">Incident investigation</div><h1 className="incident-heading">{incident.message}</h1>
      <div className="incident-badges"><StatusBadge status={status} /><span className={`tag ${incident.level === 'CRITICAL' ? 'red' : 'orange'}`}>{incident.level}</span><span className="tag muted">{incident.environment.name}</span><span className="mono dim">{incident.fingerprint}</span></div></div>
      <div className="heading-actions"><LiveStatus realtime={workspace.realtime} />{canResolve && !action.needsReconciliation && <Button className="btn-primary" disabled={action.isPending || query.isFetching} onClick={() => setConfirming(true)}><Icon name="circleCheck" /> Resolve incident</Button>}
        {action.needsReconciliation && <Button disabled={action.isPending} onClick={check}>Check current status</Button>}</div>
    </div>
    {notice && <p className="tiny-note" role="status">{notice}</p>}{action.error && <p className="form-feedback" role="alert">{action.error.message}</p>}
    <div className="kpi-grid"><Kpi title="Current status" value={status} icon="circleCheck" note="Incident lifecycle" /><Kpi title="Occurrences" value={incident.error_count} icon="logs" note="Matching log events" /><Kpi title="First seen" value={<Timestamp value={incident.first_seen_at} />} icon="clock" note="Initial detection &middot; UTC" /><Kpi title="Last seen" value={<Timestamp value={incident.last_seen_at} />} icon="clock" note="Latest matching event &middot; UTC" /></div>
    <div className="detail-grid"><div className="panel"><div className="panel-head"><div><h2>Incident information</h2><p>Diagnostic details for investigation</p></div></div>
      {rows.map(([label,value]) => <div className="detail-row" key={label}><div className="detail-label">{label}</div><div className={`detail-val ${label === 'Fingerprint' || label === 'Resolved by (user ID)' ? 'mono' : ''}`}>{value}</div></div>)}
      <div className="incident-help"><h3>Investigate related logs</h3><p className="tiny-note">Prepare a suggested message search for this environment. Review the filters before applying.</p>
        <Button onClick={() => { workspace.prepareLogSearch(incident.environment.id, incident.message); window.location.hash = `#/applications/${workspace.application.id}/logs` }}><Icon name="search" size={15} /> Explore logs <Icon name="arrowRight" size={14} /></Button></div>
    </div><div className="panel"><div className="panel-head"><div><h2>Activity timeline</h2><p>Newest 100 events, newest first.</p></div></div>
      <div className="incident-timeline">{incident.events.length ? incident.events.map(event => <div className="timeline-item" key={event.id}><div className="timeline-event">{lifecycleLabel(event.event_type)}</div><div className="timeline-time"><Timestamp value={event.event_at} detail /> &middot; <Timestamp value={event.event_at} relative /></div>
        {event.error_count !== null && event.error_count !== undefined && <div className="timeline-note">Count: {event.error_count}</div>}{event.message && <div className="timeline-note">{event.message}</div>}
        {event.event_type === 'RESOLVED' && <div className="timeline-note">Source: {resolutionAudit({ ...incident, events: [event] }).source ?? 'Unknown / legacy'} · Actor ID: {resolutionAudit({ ...incident, events: [event] }).actor ?? 'Not recorded'}</div>}
      </div>) : <PageState title="No recent lifecycle events" />}</div>
      <div className="incident-permissions"><span className="tag muted"><Icon name="shield" size={13} />{role === 'ADMIN' ? 'Administrator' : 'Engineer'} permissions</span><p className="tiny-note">You can resolve incidents in applications you are authorized to access. All times are UTC.</p></div>
    </div></div>
    {confirming && <ResolveIncidentDialog message={incident.message} pending={action.isPending} blocked={!canResolve || query.isFetching || action.needsReconciliation}
      notice={notice} error={action.error?.message} close={() => setConfirming(false)} confirm={resolve} check={action.needsReconciliation ? check : undefined} />}
  </>
}
