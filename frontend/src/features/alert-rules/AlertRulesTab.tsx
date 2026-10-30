import { Toast } from '../../components/ui/Toast.tsx'
import { adminFeedback } from '../../lib/adminPresentation.ts'
import { useEffect, useRef, useState } from 'react'
import type { Application } from '../applications/application.types.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { Timestamp } from '../../components/ui/Timestamp.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { ApiError } from '../../lib/apiError.ts'
import { useAlertRules } from './useAlertRules.ts'
import { useAlertRuleMutations } from './useAlertRuleMutations.ts'
import type { AlertRuleCommand, AlertRule } from './alertRule.types.ts'
import { AlertRuleDialog } from './AlertRuleDialog.tsx'
import type { RuleModal } from './AlertRuleDialog.tsx'

function RuleList({ application, environmentId, locked, onOpen, onDenied }: { application: Application; environmentId: string | null; locked: boolean; onOpen: (modal: RuleModal) => void; onDenied: () => void }) {
  const view = useAlertRules(application.environments.map(env => env.id), environmentId)
  useEffect(() => { if (view.error?.status === 403) onDenied() }, [view.error?.status, onDenied])
  const canEnable = (rule: AlertRule) => application.status === 'ACTIVE' && application.environments.some(env => env.id === rule.environment_id && env.status === 'ACTIVE')
  return <div className="panel" aria-busy={view.loading}>{view.error ? <PageState title={view.error.status === 403 ? 'Alert Rules access denied' : 'Alert Rules unavailable'} description={view.error.message} error retry={view.retry} /> : view.initialLoading ? <PageState title="Loading Alert Rules..." busy /> : !view.items.length ? <PageState title={view.complete ? 'No matching alert rules' : 'No matching rules in loaded results'} description={view.complete ? 'No rules matched this application and environment filter in the completed traversal.' : 'Continue loading the global rule pages to check for more matches.'} /> : <div className="table-scroll"><table className="data-table"><caption className="sr-only">Rules matching this application's actual environments; filter covers loaded global pages</caption><thead><tr><th>Level / scope</th><th>Trigger</th><th>Cooldown</th><th>Auto-resolve</th><th>Status</th><th className="admin-actions-heading">Actions</th></tr></thead><tbody>{view.items.map(rule => <tr key={rule.id}><td><div className="admin-environments rule-scope"><span className={`level ${rule.level}`}>{rule.level}</span><span className="env-indicator"><span className="env-dot" aria-hidden="true" />{application.environments.find(env => env.id === rule.environment_id)?.name}</span></div><details className="admin-metadata"><summary aria-label={`Technical metadata for ${rule.level} rule`}>Details</summary><dl><dt>Rule ID</dt><dd className="mono">{rule.id}</dd><dt>Environment ID</dt><dd className="mono">{rule.environment_id}</dd><dt>Created (UTC)</dt><dd><Timestamp value={rule.created_at} /></dd><dt>Updated (UTC)</dt><dd><Timestamp value={rule.updated_at} /></dd></dl></details></td><td><strong>{rule.threshold} events</strong><div className="small-note">in {rule.window_seconds} seconds</div></td><td>{rule.cooldown_seconds}s</td><td>{rule.resolve_after_seconds}s inactivity</td><td><span className={`tag ${rule.enabled ? 'green' : 'muted'}`}><span className="dot" aria-hidden="true" />{rule.enabled ? 'ENABLED' : 'DISABLED'}</span></td><td><div className="inline-actions"><Button className="btn-sm" disabled={locked} onClick={() => onOpen({ kind: 'edit', rule })}>Edit</Button><Button className="btn-sm btn-ghost" disabled={locked || (!rule.enabled && !canEnable(rule))} onClick={() => onOpen({ kind: rule.enabled ? 'disable' : 'enable', rule })}>{rule.enabled ? 'Disable' : 'Enable'}</Button></div></td></tr>)}</tbody></table></div>}
    <div className="table-footer"><span>{view.error ? 'Rules unavailable' : view.initialLoading ? 'Loading...' : `${view.items.length} matching rules loaded${view.complete ? ' - global cursor exhausted' : ''}`}</span>{view.cursor && <Button className="btn-sm" disabled={locked || view.loading} onClick={view.more}>{view.loading ? 'Loading...' : 'Load more global rules'}</Button>}</div></div>
}
export function AlertRulesTab({ application, onLock, onDenied }: { application: Application; onLock: (locked: boolean) => void; onDenied: () => void }) {
  const action = useAlertRuleMutations(application.id)
  const [environmentId, setEnvironmentId] = useState<string | null>(null), [epoch, setEpoch] = useState(0), [modal, setModal] = useState<RuleModal | null>(null), [notice, setNotice] = useState(''), [success, setSuccess] = useState('')
  const heading = useRef<HTMLHeadingElement>(null), restoreHeading = useRef(false)
  const locked = action.busy || action.needsReconciliation
  const canCreate = application.status === 'ACTIVE' && application.environments.some(env => env.status === 'ACTIVE')
  useEffect(() => { onLock(locked); return () => onLock(false) }, [locked, onLock])
  useEffect(() => { if (!modal && restoreHeading.current) { restoreHeading.current = false; heading.current?.focus() } }, [modal])
  function denied() { action.redact(); setModal(null); onDenied() }
  async function submit(command: AlertRuleCommand | 'check') {
    try {
      const result = await action.execute(command)
      setEpoch(value => value + 1)
      const feedback = adminFeedback(result.kind === 'confirmed', result.kind === 'confirmed' ? `Rule saved: ${result.rule.enabled ? 'enabled' : 'disabled'}.` : '', result.kind === 'uncertain' ? result.reconciled ? 'Current rules were checked. The earlier write outcome remains unconfirmed; review before another change.' : 'The write may have committed. Current rules could not be checked; check them before another change.' : '')
      setNotice(feedback.notice); setSuccess(feedback.success)
      if (result.kind === 'confirmed') { setModal(null); restoreHeading.current = true }
    } catch (error) { if (error instanceof ApiError && error.status === 403) denied() }
  }
  function open(next: RuleModal) { action.reset(); setNotice(''); setSuccess(''); setModal(next) }
  return <><div className="settings-lead"><div><h2 ref={heading} tabIndex={-1}>Alert Rules</h2><p>Configure threshold-based ERROR and CRITICAL incident detection.</p></div><Button className="btn-primary" disabled={locked || !canCreate} onClick={() => open({ kind: 'create' })}><Icon name="arrowRight" size={14} /> New alert rule</Button></div>
    <div className="settings-environment"><label htmlFor="rule-environment">Environment (loaded rules)</label><select id="rule-environment" className="field" value={environmentId ?? 'ALL'} disabled={locked} onChange={event => setEnvironmentId(event.target.value === 'ALL' ? null : event.target.value)}><option value="ALL">All environments</option>{application.environments.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}</select></div>
    {success && <Toast message={success} success duration={2600} dismiss={() => setSuccess('')} />}
    {(notice || action.needsReconciliation) && <div className="admin-write-notice" role="status">{notice || 'The write outcome is unconfirmed. Check current rules before another change.'}{action.needsReconciliation && <Button className="btn-sm" disabled={action.busy} onClick={() => { void submit('check') }}>Check current rules</Button>}</div>}
    <RuleList key={epoch} application={application} environmentId={environmentId} locked={locked} onOpen={open} onDenied={denied} />
    <div className="hint-panel settings-hint"><Icon name="info" size={16} /><span>Rules detect ERROR/CRITICAL thresholds. Load More follows the global cursor; the environment filter applies to loaded results. Disabling retains the rule. Runtime detection is controlled separately.</span></div>
    {action.error && !modal && <p className="form-feedback" role="alert">{action.error.message}</p>}
    {modal && <AlertRuleDialog modal={modal} application={application} busy={action.busy} blocked={action.needsReconciliation} error={action.error} notice={notice} onSubmit={submit} onCheck={() => { void submit('check') }} onClose={() => { if (!action.busy) { setModal(null); if (!action.needsReconciliation) action.reset() } }} />}
  </>
}
