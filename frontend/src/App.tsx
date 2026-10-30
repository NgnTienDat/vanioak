import { useEffect, useState } from 'react'
import { useStore } from 'zustand'
import { authStore } from './store/authStore.ts'
import { WorkspacePage } from './features/workspace/WorkspacePage.tsx'
import { LoginPage } from './features/auth/LoginPage.tsx'
import { ApplicationsPage } from './features/applications/ApplicationsPage.tsx'
import { ApplicationSettingsPage } from './features/application-settings/ApplicationSettingsPage.tsx'
import { UsersPage } from './features/users/UsersPage.tsx'
import { parseRoute, routeFor } from './app/routes.ts'
import { WorkspaceLayout } from './components/layout/WorkspaceLayout.tsx'
import { PageState } from './components/ui/PageState.tsx'

export default function App() {
  const user = useStore(authStore, s => s.user)
  const phase = useStore(authStore, s => s.phase)
  const generation = useStore(authStore, s => s.generation)
  const [hash,setHash] = useState(window.location.hash)
  useEffect(() => { document.title = 'VaniOAK' }, [])
  useEffect(() => { const update = () => setHash(window.location.hash); window.addEventListener('hashchange',update); return () => window.removeEventListener('hashchange',update) }, [])
  useEffect(() => { document.getElementById('main-content')?.focus() }, [hash,user])
  useEffect(() => {
    if (user && parseRoute(hash).page === 'login') window.location.replace('#/applications')
  }, [hash,user])
  const route = routeFor(hash,user?.role ?? null)
  if (!user) return <LoginPage />
  if (phase === 'logging-out') return <PageState title="Signing out" busy />
  if (route.page === 'workspace') return <WorkspacePage key={`${generation}:${user.id}:${route.id}`} id={route.id} page={route.view ?? 'overview'} incidentId={route.incidentId} />
  if (route.page === 'applications') return <ApplicationsPage key={user.id} />
  if (route.page === 'users') return <UsersPage key={`${generation}:${user.id}`} />
  if (route.page === 'settings') return <ApplicationSettingsPage key={`${generation}:${user.id}:${route.id}`} id={route.id} />
  if (route.page === 'forbidden') return <WorkspaceLayout><PageState title="Administrator access required" description="Your account cannot access this management page." error /></WorkspaceLayout>
  return <WorkspaceLayout><PageState title="Page not found" description="This page is unavailable. Use Applications to continue." /></WorkspaceLayout>
}
