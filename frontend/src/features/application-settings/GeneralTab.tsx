import type { Application } from '../applications/application.types.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'

export function GeneralTab({ application, locked, onEdit, onStatus }: { application: Application; locked: boolean; onEdit: () => void; onStatus: () => void }) {
  return <><div className="settings-lead"><div><h2>General settings</h2><p>Identity and status of this application.</p></div><Button disabled={locked || application.status === 'DISABLED'} onClick={onEdit} title={application.status === 'DISABLED' ? 'Enable this application before editing' : undefined}><Icon name="settings" size={14} /> Edit details</Button></div>
    <div className="settings-details"><div className="field-card"><small>Application name</small><strong className="mono">{application.name}</strong></div><div className="field-card"><small>Application ID</small><strong className="mono">{application.id}</strong></div><div className="field-card"><small>Current status</small><span className={`tag ${application.status === 'ACTIVE' ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{application.status}</span></div><div className="field-card"><small>Description</small><strong>{application.description || 'No description provided'}</strong></div><div className="field-card settings-environments"><small>Environments</small><strong>{application.environments.map(env => env.name).join(' / ') || 'None'}</strong></div></div>
    <div className="subhead">Application lifecycle</div><div className="hint-panel warning"><Icon name="info" size={17} /><span>Disabling an application affects ingestion eligibility. Historical logs and incidents remain available to authorized users. Cached credentials may take the server cache interval to reflect this change.</span></div>
    <Button className={`settings-lifecycle ${application.status === 'ACTIVE' ? 'btn-danger' : 'btn-primary'}`} onClick={onStatus} disabled={locked}>{application.status === 'ACTIVE' ? 'Disable application' : 'Enable application'}</Button>
  </>
}
