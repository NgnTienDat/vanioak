import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useEffect, useRef, useState } from 'react'
import type { Application, AssignedEngineer } from '../applications/application.types.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { ApiError } from '../../lib/apiError.ts'
import { useAssignedEngineers } from './useAssignedEngineers.ts'
import { useEngineerAccess } from './useEngineerAccess.ts'
import type { EngineerCommand, EngineerOutcome } from './useEngineerAccess.ts'
import { EngineerAccessDialog } from './EngineerAccessDialog.tsx'

function Roster({ id, locked, onDenied, onRemove }: { id: string; locked: boolean; onDenied: () => void; onRemove: (user: AssignedEngineer) => void }) {
  const view = useAssignedEngineers(id)
  useEffect(() => { if (view.error?.status === 403) onDenied() }, [view.error?.status, onDenied])
  return <div className="panel" aria-busy={view.loading}>
    {view.error ? <PageState title={view.error.status === 403 ? 'Assigned Engineers access denied' : 'Assigned Engineers unavailable'} description={view.error.message} error retry={view.retry} /> : view.initialLoading ? <PageState title="Loading assigned Engineers..." busy /> : !view.items.length ? <PageState title={view.complete ? 'No assigned Engineers' : 'No Engineers in loaded results'} description="Assign an Engineer to grant application visibility." /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">Assigned Engineers, including disabled accounts</caption><thead><tr><th>Engineer</th><th>Account status</th><th>Application access</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{view.items.map(user => <tr key={user.id}><td><div className="entity-cell"><span className="user-avatar">{user.username.slice(0, 2).toUpperCase()}</span><span><strong>{user.username}</strong><small className="mono">{user.id}</small></span></div></td><td><span className={`tag ${user.status === 'ACTIVE' ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{user.status}</span></td><td className="muted">All environments{user.status === 'DISABLED' && <div className="small-note">Account disabled; assignment retained</div>}</td><td><div className="inline-actions"><Button className="btn-sm btn-ghost" disabled={locked} onClick={() => onRemove(user)}>Remove access <span className="sr-only">for {user.username}</span></Button></div></td></tr>)}</tbody></table></div>}
    <div className="table-footer"><span>{view.error ? 'Roster unavailable' : view.initialLoading ? 'Loading...' : `${view.items.length} ${view.complete ? 'assigned Engineers' : 'assigned Engineers loaded'}`}</span><div className="inline-actions">{view.cursor && <Button className="btn-sm" onClick={view.more} disabled={locked || view.loading}>{view.loading ? 'Loading...' : 'Load more'}</Button>}</div></div>
  </div>
}
export function EngineersTab({ application, onLock, onDenied }: { application: Application; onLock: (locked: boolean) => void; onDenied: () => void }) {
  const action = useEngineerAccess(application.id)
  const [epoch, setEpoch] = useState(0), [modal, setModal] = useState<{ kind: 'grant' | 'remove'; userId?: string; username?: string } | null>(null)
  const [notice, setNotice] = useState(''), [success, setSuccess] = useState('')
  const heading = useRef<HTMLHeadingElement>(null), restoreHeading = useRef(false)
  const locked = action.busy || action.needsReconciliation
  function denied() { action.redact(); setModal(null); onDenied() }
  useEffect(() => { onLock(locked); return () => onLock(false) }, [locked, onLock])
  useEffect(() => { if (!modal && restoreHeading.current) { restoreHeading.current = false; heading.current?.focus() } }, [modal])
  function show(outcome: EngineerOutcome) {
    setEpoch(value => value + 1)
    const feedback = adminFeedback(outcome.kind === 'confirmed', 'Current assignment confirmed.', outcome.kind === 'uncertain' ? outcome.reconciled ? `The write outcome is unconfirmed. Current server assignment: ${outcome.assigned ? 'assigned' : 'not assigned'}. Review before another change.` : 'The change may have committed. Current assignments could not be checked. Check current assignments before another change.' : '')
    setNotice(feedback.notice); setSuccess(feedback.success)
    if (outcome.kind === 'confirmed') { restoreHeading.current = true; setModal(null) }
  }
  async function submit(command?: EngineerCommand) {
    try { show(command ? await action.execute(command) : await action.check()) }
    catch (error) { if (error instanceof ApiError && error.status === 403) denied() }
  }
  function open(kind: 'grant' | 'remove', user?: AssignedEngineer) {
    action.reset(); setNotice(''); setSuccess(''); setModal({ kind, userId: user?.id, username: user?.username })
  }
  return <><div className="settings-lead"><div><h2 ref={heading} tabIndex={-1}>Assigned Engineers</h2><p>Assignment grants application access across all environments.</p></div><Button className="btn-primary" disabled={locked || application.status !== 'ACTIVE'} title={application.status !== 'ACTIVE' ? 'Enable this application before granting access' : undefined} onClick={() => open('grant')}><Icon name="arrowRight" size={14} /> Assign Engineer</Button></div>
    {success && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {(notice || action.needsReconciliation) && <div className="admin-write-notice" role="status">{notice || 'The write outcome is unconfirmed. Check current assignments before another change.'}{action.needsReconciliation && <Button className="btn-sm" onClick={() => { void submit() }} disabled={action.busy}>Check current assignments</Button>}</div>}
    <Roster key={epoch} id={application.id} locked={locked} onDenied={denied} onRemove={user => open('remove', user)} />
    {application.status === 'DISABLED' && <p className="small-note">Enable this application to grant access. Existing assignments can still be inspected and removed.</p>}
    <div className="hint-panel settings-hint"><Icon name="shield" size={16} /><span>Assignment covers all environments. Disabled Engineers remain listed, but cannot sign in. Administrators retain organization-wide access. Loaded roster pages are not a complete total until pagination finishes.</span></div>
    {action.error && !modal && <p role="alert" className="form-feedback">{action.error.message}</p>}
    {modal && <EngineerAccessDialog {...modal} applicationName={application.name} busy={action.busy} blocked={action.needsReconciliation} error={action.error} notice={notice} onDenied={denied} onCheck={() => { void submit() }} onSubmit={submit} onClose={() => { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } }} />}
  </>
}
