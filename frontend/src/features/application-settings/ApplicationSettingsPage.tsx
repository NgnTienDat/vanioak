import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useEffect, useRef, useState } from 'react'
import { useStore } from 'zustand'
import { authStore } from '../../store/authStore.ts'
import { ApiError } from '../../lib/apiError.ts'
import { WorkspaceLayout } from '../../components/layout/WorkspaceLayout.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { workspacePath } from '../../app/routes.ts'
import { useApplication } from '../applications/useApplications.ts'
import { useApplicationMutations } from '../applications/useApplicationMutations.ts'
import type { ApplicationCommand } from '../applications/application.types.ts'
import { ApplicationFormDialog } from '../applications/ApplicationFormDialog.tsx'
import { ApplicationStatusDialog } from '../applications/ApplicationStatusDialog.tsx'
import { GeneralTab } from './GeneralTab.tsx'
import { EnvironmentsTab } from './EnvironmentsTab.tsx'
import { EngineersTab } from './EngineersTab.tsx'
import { ApiKeysTab } from '../api-keys/ApiKeysTab.tsx'
import { AlertRulesTab } from '../alert-rules/AlertRulesTab.tsx'

type Tab = 'general' | 'environments' | 'engineers' | 'keys' | 'rules'
const tabs = [['general', 'General'], ['environments', 'Environments'], ['engineers', 'Engineers'], ['keys', 'API Keys'], ['rules', 'Alert Rules']] as const
export function ApplicationSettingsPage({ id }: { id: string }) {
  const user = useStore(authStore, state => state.user)
  return user?.role === 'ADMIN' ? <SettingsContent id={id} /> : <PageState title="Administrator access required" error />
}
function SettingsContent({ id }: { id: string }) {
  const view = useApplication(id), action = useApplicationMutations()
  const [tab, setTab] = useState<Tab>('general'), [modal, setModal] = useState<'edit' | 'status' | null>(null)
  const [notice, setNotice] = useState(''), [success, setSuccess] = useState(''), [denied, setDenied] = useState(false), [managementLocked, setManagementLocked] = useState(false)
  const [keyEnvironmentId, setKeyEnvironmentId] = useState<string | undefined>()
  const heading = useRef<HTMLHeadingElement>(null), focusHeading = useRef(false)
  function accessDenied() {
    setDenied(true); setModal(null)
  }
  useEffect(() => { if (!modal && focusHeading.current) { focusHeading.current = false; heading.current?.focus() } }, [modal])
  async function save(command?: ApplicationCommand) {
    try {
      const outcome = command ? await action.execute(command) : await action.check()
      const feedback = adminFeedback(outcome.kind === 'confirmed', 'Application saved.', outcome.kind === 'uncertain' ? outcome.notice : '')
      setNotice(feedback.notice); setSuccess(feedback.success)
      if (outcome.kind === 'confirmed') { focusHeading.current = true; setModal(null) }
      await view.refetch()
    } catch (error) { if (error instanceof ApiError && error.status === 403) accessDenied() }
  }
  function open(kind: 'edit' | 'status') { action.reset(); setNotice(''); setSuccess(''); setModal(kind) }
  const locked = action.busy || action.needsReconciliation || managementLocked
  const props = { id, busy: action.busy, blocked: action.needsReconciliation, error: action.error, notice,
    onSubmit: save, onCheck: () => { void save() }, onClose: () => { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } } }
  const application = view.data
  return <WorkspaceLayout application={denied ? undefined : application} page="settings"><div className="admin-settings">
    <div className="page-heading"><div><div className="eyebrow">Application / Configuration</div><h1 ref={heading} tabIndex={-1}>Application Settings</h1><p className="subtitle">Manage access and configuration{application && !denied ? ` for ${application.name}` : ''}.</p></div>{application && !denied && <a className="btn" href={workspacePath(id)}><Icon name="grid" size={14} /> View workspace</a>}</div>
    {success && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {denied || view.error ? <PageState title={denied || view.error?.status === 403 ? 'Application access denied' : 'Application unavailable'} description={view.error?.message} error retry={() => { setDenied(false); action.reset(); void view.refetch() }} /> : !application ? <PageState title="Loading application..." busy /> : <>
      {notice && <div className="admin-write-notice" role="status">{notice}{action.needsReconciliation && <Button className="btn-sm" onClick={() => { void save() }} disabled={action.busy}>Check server state</Button>}</div>}
      <div className="settings-tabs" role="tablist" aria-label="Application settings" onKeyDown={event => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || locked) return
        event.preventDefault()
        const index = tabs.findIndex(([value]) => value === tab)
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
        setTab(tabs[next][0]); document.getElementById(`settings-tab-${tabs[next][0]}`)?.focus()
      }}>{tabs.map(([value, label]) => <button type="button" role="tab" id={`settings-tab-${value}`} aria-controls={`settings-panel-${value}`} aria-selected={tab === value} tabIndex={tab === value ? 0 : -1} className={tab === value ? 'selected' : ''} disabled={locked} key={value} onClick={() => setTab(value)}>{label}</button>)}</div>
      <section className="settings-surface" role="tabpanel" id={`settings-panel-${tab}`} aria-labelledby={`settings-tab-${tab}`} tabIndex={0}>
        {tab === 'general' && <GeneralTab application={application} locked={locked || view.isFetching} onEdit={() => open('edit')} onStatus={() => open('status')} />}
        {tab === 'environments' && <EnvironmentsTab application={application} onOpenKeys={environmentId => { setKeyEnvironmentId(environmentId); setTab('keys') }} />}
        {tab === 'engineers' && <EngineersTab application={application} onLock={setManagementLocked} onDenied={accessDenied} />}
        {tab === 'keys' && <ApiKeysTab application={application} initialEnvironmentId={keyEnvironmentId} onLock={setManagementLocked} onDenied={accessDenied} />}
        {tab === 'rules' && <AlertRulesTab application={application} onLock={setManagementLocked} onDenied={accessDenied} />}
      </section>
    </>}
    {!denied && modal === 'edit' && <ApplicationFormDialog {...props} />}{!denied && modal === 'status' && <ApplicationStatusDialog {...props} />}
  </div></WorkspaceLayout>
}
