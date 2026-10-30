import type { Application } from '../applications/application.types.ts'
import { Icon } from '../../components/ui/Icon.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

export function EnvironmentsTab({ application, onOpenKeys }: { application: Application; onOpenKeys: (environmentId: string) => void }) {
  return <><div className="settings-lead"><div><h2>Environments</h2><p>Environments attached to this application. Configuration is read-only.</p></div><span className="tag muted">{application.environments.length} environments</span></div>
    <div className="panel">{!application.environments.length ? <PageState title="No environments available" /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">Actual application environments</caption><thead><tr><th>Environment</th><th>Environment ID</th><th>Status</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{application.environments.map(env => <tr key={env.id}><td><div className="entity-cell"><span className={`round-icon ${env.name === 'TEST' ? 'blue' : env.name === 'STAGING' ? 'orange' : ''}`}><Icon name="server" size={17} /></span><strong>{env.name}</strong></div></td><td className="mono">{env.id}</td><td><span className={`tag ${env.status === 'ACTIVE' ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{env.status}</span></td><td><div className="inline-actions"><Button className="btn-sm btn-ghost" onClick={() => onOpenKeys(env.id)} aria-label={`API Keys for ${env.name}`}>API Keys <Icon name="arrowRight" size={13} /></Button></div></td></tr>)}</tbody></table></div>}</div>
    <div className="hint-panel settings-hint"><Icon name="info" size={16} /><span>Environment status is shown from the server. Environment changes are not supported here. Manage credentials in API Keys.</span></div>
  </>
}
