import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { apiKeyFormSchema } from './apiKeySchema.ts'
import type { ApiKey, ApiKeyCommand } from './apiKey.types.ts'
import type { ApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Field } from '../../components/ui/Field.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
export type KeyModal = { kind: 'create' } | { kind: 'rotate' | 'revoke'; key: ApiKey }
type Props = { modal: KeyModal; environment: string; busy: boolean; blocked: boolean; error: ApiError | null; notice: string
  onClose: () => void; onCheck: () => void; onSubmit: (command: ApiKeyCommand) => Promise<void> }
function Feedback(props: Props) {
  return <>{props.error && <p role="alert" className="form-feedback">{props.error.message}</p>}{props.notice && <p role="status" className="small-note">{props.notice}</p>}{props.blocked && <Button onClick={props.onCheck} disabled={props.busy}>Check API key metadata</Button>}</>
}
function CreateKeyForm(props: Props) {
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<{ expires_at: string }>({ resolver: zodResolver(apiKeyFormSchema), defaultValues: { expires_at: '' } })
  const busy = props.busy || isSubmitting
  return <form className="modal-form" onSubmit={handleSubmit(values => props.onSubmit({ kind: 'create', ...(values.expires_at ? { expires_at: values.expires_at } : {}) }))} aria-busy={busy}>
    <p>Generate an ingestion credential for <strong>{props.environment}</strong>. Copy the full key from the next dialog; it cannot be retrieved later.</p>
    <Field label="Expiration (UTC, optional)" placeholder="2099-01-01T00:00:00Z" autoComplete="off" {...register('expires_at')} error={errors.expires_at?.message} disabled={busy || props.blocked} />
    <Feedback {...props} /><div className="modal-actions"><Button onClick={props.onClose} disabled={busy}>Cancel</Button><Button type="submit" className="btn-primary" disabled={busy || props.blocked}><Icon name="lock" size={14} />{busy ? 'Generating...' : 'Generate API key'}</Button></div>
  </form>
}
export function ApiKeyDialog(props: Props) {
  const { modal } = props
  return <Dialog title={modal.kind === 'create' ? 'Generate API key' : modal.kind === 'rotate' ? 'Rotate API key?' : 'Revoke API key?'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {modal.kind === 'create' ? <CreateKeyForm {...props} /> : <><p>{modal.kind === 'rotate' ? <>Rotate <span className="mono">{modal.key.key_prefix}</span> in <strong>{props.environment}</strong>? The old key is revoked and the replacement keeps its expiration. The new secret is shown once.</> : <>Revoke <span className="mono">{modal.key.key_prefix}</span> in <strong>{props.environment}</strong>? This cannot be undone. Cached credential checks may take the server cache interval to reflect revocation.</>}</p><Feedback {...props} /><div className="modal-actions"><Button onClick={props.onClose} disabled={props.busy}>Cancel</Button><Button className="btn-danger" disabled={props.busy || props.blocked} onClick={() => { void props.onSubmit({ kind: modal.kind, id: modal.key.id }) }}>{props.busy ? 'Saving...' : modal.kind === 'revoke' ? 'Revoke key' : 'Rotate key'}</Button></div></>}
  </Dialog>
}
