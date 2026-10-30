import { useEffect, useId } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import type { ApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'
import { useUsers } from '../users/useUsers.ts'
import type { EngineerCommand } from './useEngineerAccess.ts'

type Props = { kind: 'grant' | 'remove'; applicationName: string; userId?: string; username?: string
  busy: boolean; blocked: boolean; error: ApiError | null; notice: string; onClose: () => void; onCheck: () => void
  onSubmit: (command: EngineerCommand) => Promise<void>; onDenied: () => void }
const selectionSchema = z.object({ userId: z.uuid('Select an Engineer.') }).strict()

function DirectoryForm(props: Props) {
  const { onDenied } = props
  const view = useUsers('ACTIVE'), inputId = useId()
  const { register, handleSubmit, control, formState: { errors, isSubmitting } } = useForm<{ userId: string }>({ resolver: zodResolver(selectionSchema), defaultValues: { userId: '' } })
  const userId = useWatch({ control, name: 'userId' })
  const selected = view.items.find(user => user.id === userId)
  useEffect(() => { if (view.error?.status === 403) onDenied() }, [view.error?.status, onDenied])
  const busy = props.busy || isSubmitting
  return <form className="modal-form" onSubmit={handleSubmit(async values => { if (selected) await props.onSubmit({ kind: 'grant', userId: values.userId }) })} aria-busy={busy}>
    <p>Grant an active Engineer access to <strong>{props.applicationName}</strong> across all its environments.</p>
    {view.error ? <PageState title={view.error.status === 403 ? 'Engineer directory access denied' : 'Engineer directory unavailable'} description={view.error.message} error retry={view.retry} /> : view.initialLoading ? <PageState title="Loading Engineers..." busy /> : <>
      {!view.items.length ? <PageState title="No active Engineers found" description="There are no active accounts in this directory response." /> : <><label className="form-label" htmlFor={inputId}>Engineer</label><select className="field" id={inputId} {...register('userId')} disabled={busy || props.blocked} aria-invalid={!!errors.userId} aria-describedby={errors.userId ? `${inputId}-error` : `${inputId}-hint`}><option value="">Choose an Engineer</option>{view.items.map(user => <option value={user.id} key={user.id}>{user.username} - {user.id}</option>)}</select>
        {errors.userId && <p className="form-feedback" id={`${inputId}-error`} role="alert">{errors.userId.message}</p>}<p className="small-note" id={`${inputId}-hint`}>Showing {view.items.length} loaded active Engineers. Current assignment is checked before granting access.</p></>}
      {view.cursor && <Button className="btn-sm" onClick={view.more} disabled={view.loading || busy}>{view.loading ? 'Loading...' : 'Load more Engineers'}</Button>}
    </>}
    <Feedback {...props} /><div className="modal-actions"><Button onClick={props.onClose} disabled={busy}>Cancel</Button><Button type="submit" className="btn-primary" disabled={busy || props.blocked || !!view.error || !selected}><Icon name="check" size={14} />{busy ? 'Saving...' : 'Confirm grant access'}</Button></div>
  </form>
}
function Feedback(props: Props) {
  return <>{props.error && <p className="form-feedback" role="alert">{props.error.message}</p>}{props.notice && <p className="small-note" role="status">{props.notice}</p>}{props.blocked && <Button disabled={props.busy} onClick={props.onCheck}>Check current assignments</Button>}</>
}
export function EngineerAccessDialog(props: Props) {
  return <Dialog title={props.kind === 'grant' ? 'Assign Engineer' : 'Remove Engineer access?'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {props.kind === 'grant' ? <DirectoryForm {...props} /> : <><p>Remove <strong>{props.username ?? props.userId}</strong> from <strong>{props.applicationName}</strong>? This removes assignment-based access across every environment. The account and historical observability data are retained.</p><Feedback {...props} /><div className="modal-actions"><Button onClick={props.onClose} disabled={props.busy}>Cancel</Button><Button className="btn-danger" disabled={props.busy || props.blocked || !props.userId} onClick={() => { if (props.userId) void props.onSubmit({ kind: 'remove', userId: props.userId }) }}>{props.busy ? 'Saving...' : 'Remove access'}</Button></div></>}
  </Dialog>
}
