import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useEffect, useRef, useState } from 'react'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import type { Status } from '../../types/api.types.ts'
import { ApiError } from '../../lib/apiError.ts'
import { WorkspaceLayout } from '../../components/layout/WorkspaceLayout.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { formatCount } from '../../lib/presentation.ts'
import { workspacePath, settingsPath } from '../../app/routes.ts'
import { useApplications } from './useApplications.ts'
import { useApplicationMutations } from './useApplicationMutations.ts'
import type { ApplicationOutcome } from './useApplicationMutations.ts'
import type { ApplicationCommand } from './application.types.ts'
import { ApplicationFormDialog } from './ApplicationFormDialog.tsx'
import { ApplicationActions } from './ApplicationActions.tsx'
import { ApplicationStatusDialog } from './ApplicationStatusDialog.tsx'

type Modal = { kind: 'create' } | { kind: 'edit' | 'status'; id: string }

function ApplicationList({ filter, onFilter, onOpen, locked }: { filter?: Status; onFilter: (status?: Status) => void; onOpen: (modal: Modal) => void; locked: boolean }) {
  const view = useApplications(filter)
  const [search, setSearch] = useState('')
  const rows = view.items.filter(app => app.name.toLowerCase().includes(search.toLowerCase()))
  const initial = view.loading && !view.items.length
  const summaries = [['Applications loaded', view.items.length], ['Active loaded', view.items.filter(app => app.status === 'ACTIVE').length], ['Disabled loaded', view.items.filter(app => app.status === 'DISABLED').length]] as const
  return <><div className="admin-grid">{summaries.map(([label, value]) => <div className="summary-card" key={label}><span className="summary-label">{label}</span><div className="summary-val">{view.error ? 'Unavailable' : initial ? 'Loading...' : formatCount(value)}</div><span className="small-note">Loaded results for the selected status</span></div>)}</div>
    <div className="panel"><div className="panel-head"><div><h2>All applications</h2><p>Open a workspace or manage application details and status</p></div><span className="tag muted">{view.error ? 'Unavailable' : initial ? 'Loading...' : `${view.items.length} loaded`}</span></div>
      <div className="admin-filter-bar"><div className="toolbar"><div className="segmented" aria-label="Application status">{([undefined, 'ACTIVE', 'DISABLED'] as const).map(status => <button type="button" key={status ?? 'all'} className={filter === status ? 'current' : ''} aria-pressed={filter === status} onClick={() => onFilter(status)} disabled={locked}>{status === 'ACTIVE' ? 'Active' : status === 'DISABLED' ? 'Disabled' : 'All'}</button>)}</div>
        <div className="toolbar-right"><div className="search-wrap"><Icon name="search" size={15} /><input className="field" aria-label="Search loaded applications" placeholder="Search loaded applications..." value={search} onChange={event => setSearch(event.target.value)} /></div></div></div></div>
      {view.error ? <PageState title={view.error.status === 403 ? 'Applications access denied' : 'Applications unavailable'} description={view.error.message} error retry={view.retry} /> : initial ? <PageState title="Loading applications..." busy /> : !rows.length ? <PageState title="No matching applications" description={view.cursor ? 'No matches in loaded results. Clear the search or load more.' : search ? 'Try a different search.' : 'Create an application or choose another status.'} /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">Authorized applications; search and counts cover loaded results</caption><thead><tr><th>Application</th><th>Status</th><th>Environments</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{rows.map(app => <tr key={app.id}><td><div className="entity-cell"><span className="round-icon"><Icon name="server" size={18} /></span><span><strong>{app.name}</strong><small title={app.description ?? undefined}>{app.description ?? 'No description provided'}</small></span></div></td><td><span className={`tag ${app.status === 'ACTIVE' ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{app.status}</span></td><td><div className="admin-environments">{app.environments.map(env => <span className="tag muted" key={env.id}>{env.name}{env.status === 'DISABLED' ? ' - Disabled' : ''}</span>)}</div></td><td><div className="inline-actions"><a className="btn btn-sm" href={workspacePath(app.id)}>Open <span className="sr-only">{app.name} workspace</span><Icon name="arrowRight" size={13} /></a><a className="btn btn-sm btn-ghost" href={settingsPath(app.id)} aria-label={`Settings for ${app.name}`} title="Application Settings"><Icon name="settings" size={15} /></a><ApplicationActions application={app} locked={locked} onEdit={() => onOpen({ kind: 'edit', id: app.id })} onStatus={() => onOpen({ kind: 'status', id: app.id })} /></div></td></tr>)}</tbody></table></div>}
      <div className="table-footer"><span>{view.error ? 'Application list unavailable' : `${rows.length} shown / ${view.items.length} loaded`} &middot; Search covers loaded names</span>{view.cursor && <Button className="btn-sm" disabled={view.loading || locked} onClick={view.more}>{view.loading ? 'Loading...' : 'Load more'}</Button>}</div>
    </div></>
}

export function AdminApplicationsPage() {
  const user = useStore(authStore, state => state.user)
  const generation = useStore(authStore, state => state.generation)
  return user?.role === 'ADMIN' ? <AdminApplicationsContent key={`${generation}:${user.id}`} /> : <PageState title="Administrator access required" error />
}
function AdminApplicationsContent() {
  const action = useApplicationMutations()
  const [filter, setFilter] = useState<Status | undefined>()
  const [epoch, setEpoch] = useState(0)
  const [modal, setModal] = useState<Modal | null>(null)
  const [notice, setNotice] = useState(''), [success, setSuccess] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)
  const returnToHeading = useRef(false)
  useEffect(() => { if (!modal && returnToHeading.current) { returnToHeading.current = false; heading.current?.focus() } }, [modal])
  function show(result: ApplicationOutcome) {
    const feedback = adminFeedback(result.kind === 'confirmed', result.kind === 'confirmed' ? `Application saved: ${result.application.name} (${result.application.status}).` : '', result.kind === 'uncertain' ? result.notice : '')
    setNotice(feedback.notice); setSuccess(feedback.success)
    if (result.kind === 'confirmed') { returnToHeading.current = true; setModal(null) }
    setEpoch(value => value + 1)
  }
  async function submit(command: ApplicationCommand) {
    try { show(await action.execute(command)) } catch (error) { if (error instanceof ApiError && error.status === 403) { returnToHeading.current = true; setModal(null) } }
  }
  async function check() {
    try { show(await action.check()) } catch (error) { if (error instanceof ApiError && error.status === 403) { returnToHeading.current = true; setModal(null) } }
  }
  function open(next: Modal) { action.reset(); setNotice(''); setSuccess(''); setModal(next) }
  function close() { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } }
  const props = { busy: action.busy, blocked: action.needsReconciliation, error: action.error, notice,
    onClose: close, onCheck: () => { void check() }, onSubmit: submit }
  return <WorkspaceLayout><div className="admin-applications"><div className="page-heading"><div><div className="eyebrow">Organization / Applications</div><h1 ref={heading} tabIndex={-1}>Applications</h1><p className="subtitle">Create and manage the services monitored by your organization.</p></div><div className="heading-actions"><Button className="btn-primary" disabled={action.busy || action.needsReconciliation || action.error?.status === 403} onClick={() => open({ kind: 'create' })}><Icon name="arrowRight" size={15} /> New application</Button></div></div>
    {success && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {notice && <div className="admin-write-notice" role="status">{notice}{action.needsReconciliation && <Button className="btn-sm" onClick={() => { void check() }} disabled={action.busy}>Check server state</Button>}</div>}
    {action.error?.status === 403 ? <PageState title="Applications access denied" description={action.error.message} error retry={() => { action.reset(); setEpoch(value => value + 1) }} /> : <ApplicationList key={epoch} filter={filter} onFilter={setFilter} onOpen={open} locked={action.busy || action.needsReconciliation} />}
    {modal?.kind === 'create' && <ApplicationFormDialog {...props} />}{modal?.kind === 'edit' && <ApplicationFormDialog {...props} id={modal.id} />}{modal?.kind === 'status' && <ApplicationStatusDialog {...props} id={modal.id} />}
  </div></WorkspaceLayout>
}
