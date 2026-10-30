import { useId } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { alertRuleSchema } from './alertRuleSchema.ts'
import type { AlertRule, AlertRuleCommand, CreateAlertRule, UpdateAlertRule } from './alertRule.types.ts'
import type { Application } from '../applications/application.types.ts'
import type { ApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Field } from '../../components/ui/Field.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { enabledFromSelect } from '../../lib/adminPresentation.ts'
export type RuleModal = { kind: 'create' } | { kind: 'edit' | 'enable' | 'disable'; rule: AlertRule }
type Props = { application: Application; modal: RuleModal; busy: boolean; blocked: boolean; error: ApiError | null; notice: string
  onClose: () => void; onCheck: () => void; onSubmit: (command: AlertRuleCommand) => Promise<void> }
const fields = [['threshold', 'Threshold count'], ['window_seconds', 'Window (seconds)'], ['cooldown_seconds', 'Cooldown (seconds)'], ['resolve_after_seconds', 'Auto-resolve after (seconds)']] as const
function Feedback(props: Props) { return <>{props.error && <p className="form-feedback" role="alert">{props.error.message}{props.error.status === 400 && ' Check the numeric fields and existing rules: only one rule per environment and level is allowed.'}</p>}{props.notice && <p className="small-note" role="status">{props.notice}</p>}{props.blocked && <Button disabled={props.busy} onClick={props.onCheck}>Check current rules</Button>}</> }
function RuleForm(props: Props) {
  const rule = props.modal.kind === 'create' ? undefined : props.modal.rule, id = useId()
  const activeEnvironments = props.application.status === 'ACTIVE' ? props.application.environments.filter(env => env.status === 'ACTIVE') : []
  const { register, handleSubmit, formState: { errors, dirtyFields, isSubmitting } } = useForm<CreateAlertRule>({ resolver: zodResolver(alertRuleSchema), defaultValues: {
    environment_id: rule?.environment_id ?? activeEnvironments.find(env => env.name === 'DEV')?.id ?? activeEnvironments[0]?.id ?? '', level: rule?.level ?? 'ERROR', threshold: rule?.threshold ?? 10, window_seconds: rule?.window_seconds ?? 60, cooldown_seconds: rule?.cooldown_seconds ?? 60, resolve_after_seconds: rule?.resolve_after_seconds ?? 300, enabled: rule?.enabled ?? true,
  } })
  const scopeActive = !!activeEnvironments.find(env => env.id === rule?.environment_id)
  const busy = props.busy || isSubmitting
  async function submit(values: CreateAlertRule) {
    if (rule) {
      const changes: UpdateAlertRule = {}
      for (const [name] of fields) if (dirtyFields[name]) changes[name] = values[name]
      if (dirtyFields.enabled) changes.enabled = values.enabled
      await props.onSubmit({ kind: 'edit', id: rule.id, values: changes })
    } else await props.onSubmit({ kind: 'create', values })
  }
  return <form className="modal-form" onSubmit={handleSubmit(submit)} aria-busy={busy}>
    <p>Configure ERROR or CRITICAL thresholds. Environment and level cannot change after creation.</p><div className="form-grid">
      {rule ? <><div><label className="form-label">Environment</label><span className="env-indicator"><span className="env-dot" aria-hidden="true" />{props.application.environments.find(env => env.id === rule.environment_id)?.name ?? rule.environment_id}</span></div><div><label className="form-label">Level</label><span className={`level ${rule.level}`}>{rule.level}</span></div></> : <>
        <div><label className="form-label" htmlFor={`${id}-env`}>Environment</label><select id={`${id}-env`} className="field" {...register('environment_id')} disabled={busy || props.blocked} aria-invalid={!!errors.environment_id} aria-describedby={errors.environment_id ? `${id}-env-error` : undefined}>{!activeEnvironments.length && <option value="">No active environments</option>}{activeEnvironments.map(env => <option value={env.id} key={env.id}>{env.name}</option>)}</select>{errors.environment_id && <p id={`${id}-env-error`} role="alert" className="form-feedback">Select an active environment.</p>}</div>
        <div><label className="form-label" htmlFor={`${id}-level`}>Level</label><select id={`${id}-level`} className="field" {...register('level')} disabled={busy || props.blocked}><option value="ERROR">ERROR</option><option value="CRITICAL">CRITICAL</option></select></div>
      </>}
      {fields.map(([name, label]) => <div key={name}><Field type="number" min={1} max={2147483647} step={1} label={label} {...register(name, { valueAsNumber: true })} error={errors[name]?.message} disabled={busy || props.blocked} /></div>)}
      {rule && !scopeActive ? <div className="span-all rule-enabled">Status: {rule.enabled ? 'Enabled' : 'Disabled'} (activation unavailable in this scope)</div> : <div><label className="form-label" htmlFor={`${id}-enabled`}>Enabled</label><select className="field" id={`${id}-enabled`} {...register('enabled', { setValueAs: enabledFromSelect })} disabled={busy || props.blocked}><option value="true">Enabled</option><option value="false">Disabled</option></select></div>}
    </div>{rule && !scopeActive && <p className="small-note">Enabling requires an active application and environment. Numeric edits or disabling remain available.</p>}<Feedback {...props} />
    <div className="modal-actions"><Button onClick={props.onClose} disabled={busy}>Cancel</Button><Button type="submit" className="btn-primary" disabled={busy || props.blocked || (!rule && !activeEnvironments.length) || (!!rule && !Object.keys(dirtyFields).length)}><Icon name="check" size={14} />{busy ? 'Saving...' : rule ? 'Save rule' : 'Create rule'}</Button></div>
  </form>
}
export function AlertRuleDialog(props: Props) {
  const { modal } = props
  return <Dialog title={modal.kind === 'create' ? 'Create alert rule' : modal.kind === 'edit' ? 'Edit alert rule' : modal.kind === 'enable' ? 'Enable alert rule?' : 'Disable alert rule?'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {modal.kind === 'create' || modal.kind === 'edit' ? <RuleForm {...props} /> : <><p>{modal.kind === 'disable' ? 'Disable' : 'Enable'} the <strong>{modal.rule.level}</strong> rule for <strong>{props.application.environments.find(env => env.id === modal.rule.environment_id)?.name ?? modal.rule.environment_id}</strong>? {modal.kind === 'disable' ? 'The rule is retained, not permanently deleted. Existing incidents are unchanged.' : 'The backend controls whether detection is running.'}</p><Feedback {...props} /><div className="modal-actions"><Button disabled={props.busy} onClick={props.onClose}>Cancel</Button><Button className={modal.kind === 'disable' ? 'btn-danger' : 'btn-primary'} disabled={props.busy || props.blocked} onClick={() => { void props.onSubmit(modal.kind === 'disable' ? { kind: 'disable', id: modal.rule.id } : { kind: 'edit', id: modal.rule.id, values: { enabled: true } }) }}>{props.busy ? 'Saving...' : modal.kind === 'disable' ? 'Disable rule' : 'Enable rule'}</Button></div></>}
  </Dialog>
}
