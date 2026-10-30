import { useState } from 'react'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { useApplications } from './useApplications.ts'
import { useLogout } from '../auth/useLogout.ts'
import { workspacePath } from '../../app/routes.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Brand } from '../../components/ui/Brand.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { AdminApplicationsPage } from './AdminApplicationsPage.tsx'
export function ApplicationsPage() {
  const role = useStore(authStore, s => s.user?.role)
  return role === 'ADMIN' ? <AdminApplicationsPage /> : <EngineerApplicationsPage />
}
function EngineerApplicationsPage() {
  const user = useStore(authStore, s => s.user)!
  const { logout, busy } = useLogout()
  const [query, setQuery] = useState('')
  const view = useApplications()
  const rows = view.items.filter(app => app.name.toLowerCase().includes(query.toLowerCase()))
  const content = <><div className="eyebrow">Your workspace</div><h1>My applications</h1>
    <div className="apps-intro"><p className="subtitle">Choose an application to explore its logs, incidents and analytics.<br />Only applications assigned to your account are shown.</p><div className="search-wrap"><Icon name="search" /><input className="field" aria-label="Search loaded applications" placeholder="Search loaded applications..." value={query} onChange={e => setQuery(e.target.value)} /></div></div>
    {view.error && <PageState title="Applications unavailable" description={view.error.message} error retry={() => view.retry()} />}
    {view.loading && !view.items.length ? <PageState title="Loading applications..." busy /> : !view.error && !rows.length ? <PageState title="No applications found" description={query ? 'Search covers loaded items only. Clear the search or load more.' : 'No applications are currently assigned to your account.'} /> : <div className="app-cards">{rows.map(app => <a className="app-card" key={app.id} href={workspacePath(app.id)}><div className="app-card-top"><span className="app-icon"><Icon name="layers" size={21} /></span><span className={`tag ${app.status === 'ACTIVE' ? 'green' : 'muted'}`}>{app.status}</span></div><div className="app-card-name">{app.name}</div><div className="app-card-description">{app.description ?? 'No description provided'}</div><div className="app-card-footer"><span><Icon name="server" size={13} /> {app.environments.map(e => e.name).join(' \u00b7 ')}</span><span className="inline-link">Open <Icon name="arrowRight" size={14} /></span></div></a>)}</div>}
    <div className="apps-pagination"><span>{view.items.length} applications loaded &middot; Search covers loaded items</span>{view.cursor && <Button disabled={view.loading} onClick={() => view.more()}>{view.loading ? 'Loading...' : 'Load more'}</Button>}</div>
    <div className="apps-info"><Icon name="shield" /> Application access is managed by your administrator.</div></>
  return <div className="apps-page"><header className="apps-top"><Brand /><div className="apps-account"><span className="avatar" title={user.username}>{user.username.slice(0,2).toUpperCase()}</span><Button className="btn-sm btn-ghost" onClick={() => void logout()} disabled={busy}><Icon name="logout" size={14} /> Sign out</Button></div></header><main className="apps-container" id="main-content" tabIndex={-1}>{content}</main></div>
}
