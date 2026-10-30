import { useApplication } from '../applications/useApplications.ts'
import { WorkspaceLayout } from '../../components/layout/WorkspaceLayout.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { WorkspaceProvider } from './WorkspaceContext.tsx'
import { useWorkspace } from './workspaceScope.ts'
import { LogsPage } from '../logs/LogsPage.tsx'
import { IncidentsPage } from '../incidents/IncidentsPage.tsx'
import { IncidentDetailPage } from '../incidents/IncidentDetailPage.tsx'
import { OverviewPage } from '../overview/OverviewPage.tsx'
import { AnalyticsPage } from '../analytics/AnalyticsPage.tsx'

function WorkspaceContent({ page, incidentId }: { page: 'overview' | 'logs' | 'incidents' | 'analytics'; incidentId?: string }) {
  const { application, scope } = useWorkspace()!
  return <WorkspaceLayout application={application} page={page} detail={!!incidentId}>
    {page === 'logs' ? <LogsPage /> : page === 'incidents' ? incidentId ? <IncidentDetailPage key={`${incidentId}:${scope.environmentId}`} id={incidentId} /> : <IncidentsPage /> : page === 'analytics' ? <AnalyticsPage /> : <OverviewPage />}
  </WorkspaceLayout>
}
export function WorkspacePage({ id, page, incidentId }: { id: string; page: 'overview' | 'logs' | 'incidents' | 'analytics'; incidentId?: string }) {
  const query = useApplication(id)
  const application = query.data
  if (!application) return <WorkspaceLayout><PageState title={query.error ? 'Application unavailable' : 'Opening application…'} description={query.error?.message} busy={!query.error} error={!!query.error} retry={query.error ? () => { void query.refetch() } : undefined} /></WorkspaceLayout>
  return <WorkspaceProvider key={`${application.id}:${application.environments.map(env => env.id).join(',')}`} application={application}><WorkspaceContent page={page} incidentId={incidentId} /></WorkspaceProvider>
}
