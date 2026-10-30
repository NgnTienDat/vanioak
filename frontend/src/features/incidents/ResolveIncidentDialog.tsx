import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
export function ResolveIncidentDialog({ message, pending, blocked, notice, error, close, confirm, check }: {
  message: string; pending: boolean; blocked: boolean; notice?: string; error?: string
  close: () => void; confirm: () => void; check?: () => void
}) {
  return <Dialog title="Resolve this incident?" variant="modal" busy={pending} onClose={close} badge={<span className="tag green"><Icon name="circleCheck" size={13} />Manual resolution</span>}>
    <p>Mark <strong>{message}</strong> as resolved? Your account will be recorded as the resolver. Any resolution already committed is preserved.</p>
    {notice && <p role="status">{notice}</p>}{error && <p className="form-feedback" role="alert">{error}</p>}
    <div className="modal-actions"><Button disabled={pending} onClick={close}>{blocked ? 'Close' : 'Cancel'}</Button>
      {check && <Button disabled={pending} onClick={check}>Check current status</Button>}
      {!blocked && <Button className="btn-primary" disabled={pending} onClick={confirm}><Icon name="check" size={14} />{pending ? 'Checking / resolving…' : 'Confirm resolve'}</Button>}
    </div>
  </Dialog>
}
