import type { IncidentStatus } from '../../features/incidents/incident.types.ts'
import { Icon } from './Icon.tsx'
export function StatusBadge({ status }: { status: IncidentStatus }) {
  return <span className={`tag ${status === 'OPEN' ? 'red' : 'green'}`}><Icon name={status === 'OPEN' ? 'alert' : 'circleCheck'} size={12} />{status}</span>
}
