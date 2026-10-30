import type { Incident } from './incident.types.ts'
import { StatusBadge } from '../../components/ui/StatusBadge.tsx'
import { LevelBadge } from '../../components/ui/LevelBadge.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { Timestamp } from '../../components/ui/Timestamp.tsx'
import { formatCount, plainRowClick } from '../../lib/presentation.ts'
export function IncidentTable({ items, compact = false }: { items: Incident[]; compact?: boolean }) {
  if (compact) return <>{items.map(incident => <a className="list-incident" key={incident.id} href={`#/applications/${incident.application.id}/incidents/${incident.id}`}>
    <span className="incident-mark"><Icon name="alert" size={16} /></span><span className="incident-copy"><span className="incident-title">{incident.message}</span>
      <span className="incident-desc"><StatusBadge status={incident.status} /><LevelBadge level={incident.level} /><Timestamp value={incident.last_seen_at} relative /><span>&times; {formatCount(incident.error_count)}</span></span></span><Icon name="right" size={15} />
  </a>)}</>
  return <div className="table-scroll"><table><caption className="sr-only">Loaded Incidents, last seen newest first. Times in UTC.</caption>
    <thead><tr><th scope="col">Incident</th><th scope="col">Status</th><th scope="col">Severity</th><th scope="col">Count</th><th scope="col">Environment</th><th scope="col">Last seen</th><th scope="col"><span className="sr-only">Details</span></th></tr></thead>
    <tbody>{items.map(incident => <tr className="click-row" key={incident.id} onClick={event => { if (plainRowClick(event) && !(event.target as Element).closest('a,button')) window.location.hash = `#/applications/${incident.application.id}/incidents/${incident.id}` }}><td><a className="incident-link strong" title={incident.message} href={`#/applications/${incident.application.id}/incidents/${incident.id}`}>{incident.message}</a><div className="mono dim incident-fingerprint">{incident.fingerprint}</div></td>
      <td><StatusBadge status={incident.status} /></td><td><LevelBadge level={incident.level} /></td><td className="strong">{formatCount(incident.error_count)}</td>
      <td><span className="tag muted">{incident.environment.name}</span></td><td className="muted"><Timestamp value={incident.last_seen_at} relative /></td><td><Icon name="right" size={14} /></td>
    </tr>)}</tbody></table></div>
}
