import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useCallback, useEffect, useRef, useState } from 'react'
import { authStore } from '../../store/authStore.ts'
import type { Application } from '../applications/application.types.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { Timestamp } from '../../components/ui/Timestamp.tsx'
import { ApiError } from '../../lib/apiError.ts'
import type { ApiKeyCommand, ApiKeyScope } from './apiKey.types.ts'
import { useApiKeys } from './useApiKeys.ts'
import { useApiKeyMutations } from './useApiKeyMutations.ts'
import type { ApiKeyOutcome } from './useApiKeyMutations.ts'
import { ApiKeyDialog } from './ApiKeyDialog.tsx'
import type { KeyModal } from './ApiKeyDialog.tsx'
import { ApiKeySecretDialog } from './ApiKeySecretDialog.tsx'

function KeyList({ scope, active, locked, onOpen, onDenied }: { scope: ApiKeyScope; active: boolean; locked: boolean; onOpen: (modal: KeyModal) => void; onDenied: () => void }) {
  const view = useApiKeys(scope)
  useEffect(() => { if (view.error?.status === 403) onDenied() }, [view.error?.status, onDenied])
  return <div className="panel" aria-busy={view.loading}>{view.error ? <PageState title={view.error.status === 403 ? 'API Keys access denied' : 'API Keys unavailable'} description={view.error.message} error retry={view.retry} /> : view.initialLoading ? <PageState title="Loading API Keys..." busy /> : !view.items.length ? <PageState title="No API keys in loaded results" description={view.cursor ? 'Load more to continue checking this environment.' : 'Generate an API key to send logs from this environment.'} /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">API key metadata for the selected environment</caption><thead><tr><th>Credential prefix</th><th>Status</th><th>Created (UTC)</th><th>Expires (UTC)</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{view.items.map(key => <tr key={key.id}><td><strong className="mono">{key.key_prefix}&hellip;</strong><details className="admin-metadata"><summary aria-label={`Technical metadata for credential ${key.key_prefix}`}>Details</summary><dl><dt>Key ID</dt><dd className="mono">{key.id}</dd><dt>Environment ID</dt><dd className="mono">{key.environment_id}</dd><dt>Revoked (UTC)</dt><dd>{key.revoked_at ? <Timestamp value={key.revoked_at} /> : 'Not recorded'}</dd></dl></details></td><td><span className={`tag ${key.status === 'ACTIVE' ? 'green' : key.status === 'EXPIRED' ? 'orange' : 'red'}`}><span className="dot" aria-hidden="true" />{key.status}</span></td><td><Timestamp value={key.created_at} /></td><td>{key.expires_at ? <Timestamp value={key.expires_at} /> : <span className="muted">No expiration</span>}</td><td><div className="inline-actions">{key.status === 'ACTIVE' && <Button className="btn-sm" disabled={locked || !active} onClick={() => onOpen({ kind: 'rotate', key })}><Icon name="refresh" size={14} /> Rotate</Button>}{key.status !== 'REVOKED' && <Button className="btn-sm btn-ghost" disabled={locked} onClick={() => onOpen({ kind: 'revoke', key })}>Revoke</Button>}</div></td></tr>)}</tbody></table></div>}
    <div className="table-footer"><span>{view.error ? 'Metadata unavailable' : view.initialLoading ? 'Loading...' : `${view.items.length} keys loaded`}</span>{view.cursor && <Button className="btn-sm" disabled={view.loading || locked} onClick={view.more}>{view.loading ? 'Loading...' : 'Load more'}</Button>}</div></div>
}
function EnvironmentKeys({ application, environmentId, onLock, onDenied }: { application: Application; environmentId: string; onLock: (locked: boolean) => void; onDenied: () => void }) {
  const scope = { applicationId: application.id, environmentId }, environment = application.environments.find(env => env.id === environmentId)!
  const active = application.status === 'ACTIVE' && environment.status === 'ACTIVE'
  const [modal, setModal] = useState<KeyModal | null>(null), [secret, setSecret] = useState<string | null>(null), [notice, setNotice] = useState(''), [success, setSuccess] = useState(''), [epoch, setEpoch] = useState(0)
  const heading = useRef<HTMLHeadingElement>(null), restoreHeading = useRef(false)
  function outcome(result: ApiKeyOutcome) {
    setEpoch(value => value + 1)
    const feedback = adminFeedback(result.kind === 'confirmed', 'API key metadata updated.', result.kind === 'uncertain' ? result.reconciled ? 'The write outcome remains unconfirmed. Metadata was checked. A key may have been issued or rotated; its secret cannot be recovered. Review the metadata before another operation.' : 'The operation may have committed, but metadata could not be checked. A secret may be unrecoverable. Check metadata before another operation.' : '')
    setNotice(feedback.notice); setSuccess(feedback.success)
    if (result.kind === 'confirmed') { setModal(null); restoreHeading.current = true }
    else { setSecret(null) }
  }
  const action = useApiKeyMutations(scope, value => { setModal(null); setSecret(value) }, outcome, () => setSecret(null))
  const locked = action.busy || action.needsReconciliation || secret !== null
  useEffect(() => { onLock(locked); return () => onLock(false) }, [locked, onLock])
  useEffect(() => { if (!modal && secret === null && restoreHeading.current) { restoreHeading.current = false; heading.current?.focus() } }, [modal, secret])
  function denied() { setSecret(null); setModal(null); action.redact(); onDenied() }
  async function submit(command: ApiKeyCommand | 'check') {
    try { await action.execute(command) } catch (error) { if (error instanceof ApiError && error.status === 403) denied() }
  }
  function open(next: KeyModal) { action.reset(); setNotice(''); setSuccess(''); setModal(next) }
  return <><div className="settings-lead"><div><h2 ref={heading} tabIndex={-1}>API Keys</h2><p>Ingestion credentials for {environment.name}.</p></div><Button className="btn-primary" disabled={locked || !active} onClick={() => open({ kind: 'create' })}><Icon name="lock" size={14} /> Generate API key</Button></div>
    <div className="hint-panel warning keys-warning"><Icon name="shield" size={16} /><span>The full API key is visible <strong>only once</strong> after creation or rotation. Existing secrets cannot be revealed again.</span></div>
    {success && secret === null && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {(notice || action.needsReconciliation) && <div className="admin-write-notice" role="status">{notice || 'The write outcome is unconfirmed. Check metadata before another operation.'}{action.needsReconciliation && <Button className="btn-sm" disabled={action.busy} onClick={() => { void submit('check') }}>Check API key metadata</Button>}</div>}
    {!active && <p className="small-note">Create and rotate require an active application and environment. Metadata reads and revocation remain available.</p>}
    <KeyList key={epoch} scope={scope} active={active} locked={locked} onOpen={open} onDenied={denied} />
    {action.error && !modal && <p className="form-feedback" role="alert">{action.error.message}</p>}
    {modal && <ApiKeyDialog modal={modal} environment={environment.name} busy={action.busy} blocked={action.needsReconciliation} error={action.error} notice={notice} onSubmit={submit} onCheck={() => { void submit('check') }} onClose={() => { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } }} />}
    {secret !== null && <ApiKeySecretDialog secret={secret} success={success} dismissSuccess={() => setSuccess('')} busy={action.busy} onClose={() => { setSecret(null); setSuccess(''); restoreHeading.current = true }} />}
  </>
}
export function ApiKeysTab({ application, initialEnvironmentId, onLock, onDenied }: { application: Application; initialEnvironmentId?: string; onLock: (locked: boolean) => void; onDenied: () => void }) {
  const [environmentId, setEnvironmentId] = useState(initialEnvironmentId ?? application.environments.find(env => env.name === 'DEV')?.id ?? application.environments[0]?.id ?? '')
  const [locked, setLocked] = useState(false)
  const lock = useCallback((value: boolean) => { setLocked(value); onLock(value) }, [onLock])
  const generation = authStore.getState().generation
  const environment = application.environments.find(env => env.id === environmentId)
  return <><div className="settings-environment"><label htmlFor="api-key-environment">Environment</label><select id="api-key-environment" className="field" value={environmentId} disabled={locked} onChange={event => setEnvironmentId(event.target.value)}>{application.environments.map(env => <option value={env.id} key={env.id}>{env.name}{env.status === 'DISABLED' ? ' - Disabled' : ''}</option>)}</select></div>
    {environment ? <EnvironmentKeys key={`${generation}:${application.id}:${environmentId}`} application={application} environmentId={environmentId} onLock={lock} onDenied={onDenied} /> : <PageState title="Selected environment unavailable" description="Choose an environment attached to this application." />}
  </>
}
