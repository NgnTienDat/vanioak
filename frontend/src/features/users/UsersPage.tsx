import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import type { Status } from '../../types/api.types.ts'
import { ApiError } from '../../lib/apiError.ts'
import { formatCount } from '../../lib/presentation.ts'
import { WorkspaceLayout } from '../../components/layout/WorkspaceLayout.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { searchUsers, useUsers } from './useUsers.ts'
import { useUserMutations } from './useUserMutations.ts'
import type { UserOutcome } from './useUserMutations.ts'
import type { UserCommand } from './user.types.ts'
import { UserFormDialog } from './UserFormDialog.tsx'
import { UserStatusDialog } from './UserStatusDialog.tsx'

type Modal = { kind: 'create' } | { kind: 'edit' | 'status'; id: string }
function UserList({ filter, onFilter, onOpen, locked, onDenied }: { filter?: Status; onFilter: (status?: Status) => void; onOpen: (modal: Modal) => void; locked: boolean; onDenied: () => void }) {
  const view = useUsers(filter), [search, setSearch] = useState(''), rows = searchUsers(view.items, search)
  const forbidden = view.error?.status === 403
  useEffect(() => { if (forbidden) onDenied() }, [forbidden, onDenied])
  const summaries = [['Engineers loaded', view.items.length], ['Active loaded', view.items.filter(user => user.status === 'ACTIVE').length], ['Disabled loaded', view.items.filter(user => user.status === 'DISABLED').length]] as const
  return <><div className="admin-grid">{summaries.map(([label, value]) => <div className="summary-card" key={label}><span className="summary-label">{label}</span><div className="summary-val">{view.error ? 'Unavailable' : view.initialLoading ? 'Loading...' : formatCount(value)}</div><span className="small-note">Loaded users for the selected status</span></div>)}</div>
    <div className="panel"><div className="panel-head"><div><h2>Team members</h2><p>Manage Engineer accounts and account status</p></div><span className="tag muted">{view.error ? 'Unavailable' : view.initialLoading ? 'Loading...' : `${view.items.length} loaded`}</span></div>
      <div className="admin-filter-bar"><div className="toolbar"><div className="segmented" aria-label="User status">{([undefined, 'ACTIVE', 'DISABLED'] as const).map(status => <button type="button" key={status ?? 'all'} className={filter === status ? 'current' : ''} aria-pressed={filter === status} disabled={locked} onClick={() => onFilter(status)}>{status === 'ACTIVE' ? 'Active' : status === 'DISABLED' ? 'Disabled' : 'All'}</button>)}</div><div className="toolbar-right"><div className="search-wrap"><Icon name="search" size={15} /><input className="field" aria-label="Search loaded usernames" placeholder="Search loaded usernames..." value={search} onChange={event => setSearch(event.target.value)} /></div></div></div></div>
      {view.error ? <PageState title={forbidden ? 'Users access denied' : 'Users unavailable'} description={view.error.message} error retry={view.retry} /> : view.initialLoading ? <PageState title="Loading users..." busy /> : !rows.length ? <PageState title="No matching Engineers" description={view.cursor ? 'No matches in loaded results. Clear the search or load more.' : search ? 'Try a different username.' : 'Create an Engineer or choose another status.'} /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">Engineer accounts; search and counts apply to loaded results</caption><thead><tr><th>User</th><th>Role</th><th>Status</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{rows.map(user => <tr key={user.id}><td><div className="entity-cell"><span className="user-avatar">{user.username.slice(0, 2).toUpperCase()}</span><span><strong>{user.username}</strong><small className="mono">{user.id}</small></span></div></td><td><span className="tag muted"><Icon name="layers" size={12} /> ENGINEER</span></td><td><span className={`tag ${user.status === 'ACTIVE' ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{user.status}</span></td><td><div className="inline-actions"><Button className="btn-sm" disabled={locked} onClick={() => onOpen({ kind: 'edit', id: user.id })} aria-label={`Edit ${user.username}`}>Edit</Button><Button className="btn-sm btn-ghost" disabled={locked} title={`${user.status === 'ACTIVE' ? 'Disable' : 'Enable'} account`} aria-label={`${user.status === 'ACTIVE' ? 'Disable' : 'Enable'} ${user.username}`} onClick={() => onOpen({ kind: 'status', id: user.id })}><Icon name="settings" size={14} /></Button></div></td></tr>)}</tbody></table></div>}
      <div className="table-footer"><span>{view.error ? 'User list unavailable' : `${rows.length} shown / ${view.items.length} loaded`} &middot; Search covers loaded usernames</span>{view.cursor && <Button className="btn-sm" disabled={view.loading || locked} onClick={view.more}>{view.loading ? 'Loading...' : 'Load more'}</Button>}</div>
    </div><div className="hint-panel"><Icon name="info" size={16} /><span>Engineer access is scoped per application. Assign or remove access from the Engineers tab in Application Settings. Account status does not change assignments.</span></div></>
}
export function UsersPage() {
  const user = useStore(authStore, state => state.user), generation = useStore(authStore, state => state.generation)
  return user?.role === 'ADMIN' ? <UsersContent key={`${generation}:${user.id}`} /> : <PageState title="Administrator access required" error />
}
function UsersContent() {
  const [filter, setFilter] = useState<Status | undefined>(), [epoch, setEpoch] = useState(0), [modal, setModal] = useState<Modal | null>(null), [notice, setNotice] = useState(''), [success, setSuccess] = useState(''), [denied, setDenied] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null), returnToHeading = useRef(false)
  useEffect(() => { if (!modal && returnToHeading.current) { returnToHeading.current = false; heading.current?.focus() } }, [modal])
  function show(outcome: UserOutcome) {
    const feedback = adminFeedback(outcome.kind === 'confirmed', outcome.kind === 'confirmed' ? `Account saved: ${outcome.user.username} (${outcome.user.status}).` : '', outcome.kind === 'uncertain' ? outcome.notice : '')
    setNotice(feedback.notice); setSuccess(feedback.success)
    if (outcome.kind === 'confirmed') { returnToHeading.current = true; setModal(null) }
    setEpoch(value => value + 1)
  }
  const action = useUserMutations(show), redact = action.redact
  const onDenied = useCallback(() => { redact(); setDenied(true); returnToHeading.current = true; setModal(null) }, [redact])
  async function submit(command: UserCommand | 'check') {
    try { await action.execute(command) } catch (error) { if (error instanceof ApiError && error.status === 403) onDenied() }
  }
  function open(next: Modal) { action.reset(); setNotice(''); setSuccess(''); setModal(next) }
  function close() { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } }
  const props = { busy: action.busy, blocked: action.needsReconciliation, error: action.error, notice, onClose: close, onDenied, onCheck: () => { void submit('check') }, onSubmit: submit }
  return <WorkspaceLayout page="users"><div className="admin-users"><div className="page-heading"><div><div className="eyebrow">Organization / Access control</div><h1 tabIndex={-1} ref={heading}>Users Management</h1><p className="subtitle">Manage Engineer accounts across VaniOAK.</p></div><div className="heading-actions"><Button className="btn-primary" disabled={denied || action.busy || action.needsReconciliation} onClick={() => open({ kind: 'create' })}><Icon name="arrowRight" size={15} /> Create user</Button></div></div>
    {success && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {notice && <div className="admin-write-notice" role="status">{notice}{action.needsReconciliation && <Button className="btn-sm" disabled={action.busy} onClick={() => { void submit('check') }}>Check current account state</Button>}</div>}
    {denied ? <PageState title="Users access denied" description="Administrator permission is required." error retry={() => { action.reset(); setDenied(false); setEpoch(value => value + 1) }} /> : <UserList key={`${epoch}:${filter ?? 'all'}`} filter={filter} onFilter={setFilter} onOpen={open} locked={action.busy || action.needsReconciliation} onDenied={onDenied} />}
    {modal?.kind === 'create' && <UserFormDialog {...props} />}{modal?.kind === 'edit' && <UserFormDialog {...props} id={modal.id} />}{modal?.kind === 'status' && <UserStatusDialog {...props} id={modal.id} />}
  </div></WorkspaceLayout>
}
