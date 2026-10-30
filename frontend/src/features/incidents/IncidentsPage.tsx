import { useState } from 'react'
import { useWorkspace } from '../workspace/workspaceScope.ts'
import { useIncidents } from './useIncidents.ts'
import type { IncidentLevel, IncidentStatus } from './incident.types.ts'
import { IncidentTable } from './IncidentTable.tsx'
import { LiveStatus } from '../realtime/LiveStatus.tsx'
import { Kpi } from '../../components/ui/Kpi.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { CursorFooter } from '../../components/ui/CursorFooter.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

export function IncidentsPage() {
  const { application, scope, realtime, denied } = useWorkspace()!
  if (denied) return <><div className="page-heading"><h1>Incidents</h1><LiveStatus realtime={realtime} /></div><PageState title="Incident access denied" description="Scoped data was cleared. Retry the stream after access is restored." error /></>
  return <IncidentList key={`${application.id}:${scope.environmentId}:${scope.preset}`} />
}
function IncidentList() {
  const { application, scope, realtime, incidentReconciliation, incidentWindow: range, refreshIncidents } = useWorkspace()!
  const [status, setStatus] = useState<IncidentStatus | undefined>()
  const [level, setLevel] = useState<IncidentLevel | undefined>()
  const [search, setSearch] = useState('')
  const view = useIncidents({ application_id: application.id, environment_id: scope.environmentId ?? undefined, from: range.from, to: range.to, status, level, limit: 50 }, incidentReconciliation)
  const rows = view.items.filter(incident => incident.message.toLowerCase().includes(search.toLowerCase()))
  const counts = [
    { title: 'Open incidents (loaded)', value: view.items.filter(row => row.status === 'OPEN').length, icon: 'alert', note: 'Needs investigation', accent: 'open' as const },
    { title: 'Resolved incidents (loaded)', value: view.items.filter(row => row.status === 'RESOLVED').length, icon: 'circleCheck', note: 'Automatically or manually', accent: undefined },
    { title: 'Error incidents (loaded)', value: view.items.filter(row => row.level === 'ERROR').length, icon: 'logs', note: 'In current filtered results', accent: undefined },
    { title: 'Critical incidents (loaded)', value: view.items.filter(row => row.level === 'CRITICAL').length, icon: 'bolt', note: 'Higher priority', accent: undefined },
  ]
  return <>
    <div className="page-heading"><div><div className="eyebrow">Issue management</div><h1>Incidents</h1><p className="subtitle">Review, investigate and resolve Incidents within your authorized application.</p></div>
      <div className="heading-actions"><LiveStatus realtime={realtime} /><Button disabled={view.loading} onClick={refreshIncidents}><Icon name="refresh" size={15} />Refresh</Button></div>
    </div>
    <div className="kpi-grid">{counts.map(card => <Kpi key={card.title} {...card} value={view.error ? 'Unavailable' : view.initialLoading ? 'Loading...' : card.value} />)}</div>
    <div className="panel"><div className="tabs" aria-label="Incident status">{([undefined,'OPEN','RESOLVED'] as const).map(value => <button key={value ?? 'all'} className={`tab ${status === value ? 'active' : ''}`} aria-pressed={status === value} onClick={() => setStatus(value)}>{value === 'OPEN' ? 'Open' : value === 'RESOLVED' ? 'Resolved' : 'All incidents'}</button>)}</div>
      <div className="toolbar"><label className="search-wrap"><span className="sr-only">Search loaded messages</span><Icon name="search" size={15} /><input className="field" value={search} onChange={event => setSearch(event.target.value)} placeholder="Search loaded incidents…" /></label>
        <label>Severity <select className="field" value={level ?? 'ALL'} onChange={event => setLevel(event.target.value === 'ALL' ? undefined : event.target.value as IncidentLevel)}><option value="ALL">All levels</option><option>ERROR</option><option>CRITICAL</option></select></label>
        <Button onClick={() => { setSearch(''); setStatus(undefined); setLevel(undefined) }}>Reset filters</Button>
      </div>
      <p className="tiny-note log-window">Search and counts cover loaded results only. Pause live updates to browse older pages.</p><details className="snapshot-details"><summary>Last-seen window (UTC)</summary><p>{range.from} to {range.to}</p></details>
      {view.error && <PageState title={view.error.status === 403 ? 'Incident access denied' : 'Incidents unavailable'} description={view.error.message} error retry={view.error.status === 403 ? undefined : view.retry} />}
      {view.loading && <p className="tiny-note log-window" role="status" aria-busy="true">Loading Incidents…</p>}
      {!!rows.length && <IncidentTable items={rows} />}
      {!view.loading && !view.error && !rows.length && <PageState title="No Incidents found" description="Try another status, severity, loaded-message search, environment or time range." />}
      <CursorFooter count={view.items.length} noun="incidents" busy={view.loading} next={view.next} more={view.more} />
    </div>
  </>
}
