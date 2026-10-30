import { useEffect } from 'react'
import { useUser } from './useUsers.ts'
import type { UserDialogProps } from './UserFormDialog.tsx'
import { normalizeApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

export function UserStatusDialog(props: UserDialogProps & { id: string }) {
  const view = useUser(props.id), user = view.data
  const denied = view.error && normalizeApiError(view.error).status === 403
  const onDenied = props.onDenied
  useEffect(() => { if (denied) onDenied() }, [denied, onDenied])
  const disable = user?.status === 'ACTIVE'
  return <Dialog title={user ? `${disable ? 'Disable' : 'Enable'} user account?` : 'Account status'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {view.error ? <PageState title="Engineer unavailable" description={normalizeApiError(view.error).message} error retry={() => { void view.refetch() }} /> : !user || view.isFetching ? <PageState title="Loading Engineer..." busy /> : <>
      <p>{user.username} will {disable ? 'lose' : 'regain'} access to VaniOAK. Disabling keeps the account and existing application assignments; it does not delete observability data.</p>
      {props.error && <p role="alert" className="form-feedback">{props.error.message}</p>}{props.notice && <p role="status">{props.notice}</p>}
      {props.blocked && <Button onClick={props.onCheck} disabled={props.busy}>Check current account state</Button>}
      <div className="modal-actions"><Button onClick={props.onClose} disabled={props.busy}>Cancel</Button><Button className={disable ? 'btn-danger' : 'btn-primary'} disabled={props.busy || props.blocked} onClick={() => { void props.onSubmit({ kind: 'status', id: user.id, status: disable ? 'DISABLED' : 'ACTIVE' }) }}>{props.busy ? 'Saving...' : `${disable ? 'Disable' : 'Enable'} user`}</Button></div>
    </>}
  </Dialog>
}
