import { useApplication } from './useApplications.ts'
import type { ApplicationCommand } from './application.types.ts'
import type { ApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

export function ApplicationStatusDialog({ id, busy, blocked, error, notice, onClose, onCheck, onSubmit }: {
  id: string; busy: boolean; blocked: boolean; error: ApiError | null; notice: string
  onClose: () => void; onCheck: () => void; onSubmit: (command: ApplicationCommand) => Promise<void>
}) {
  const view = useApplication(id)
  const application = view.data, disabling = application?.status === 'ACTIVE'
  return <Dialog title={application ? `${disabling ? 'Disable' : 'Enable'} application?` : 'Application status'} variant="admin" busy={busy} onClose={onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {view.error ? <PageState title="Application unavailable" description={view.error.message} error retry={() => { void view.refetch() }} /> : !application ? <PageState title="Loading application..." busy /> : <>
      <p>{disabling ? <>Disable ingestion eligibility for <strong>{application.name}</strong>? Existing logs and incidents remain available to authorized users. Cached credentials may take the server cache interval to reflect this change.</> : <>Enable <strong>{application.name}</strong> again? Its existing environment statuses remain unchanged.</>}</p>
      {error && <p className="form-feedback" role="alert">{error.message}</p>}{notice && <p className="small-note" role="status">{notice}</p>}
      {blocked && <Button onClick={onCheck} disabled={busy}>Check server state</Button>}
      <div className="modal-actions"><Button onClick={onClose} disabled={busy}>Cancel</Button><Button className={disabling ? 'btn-danger' : 'btn-primary'} disabled={busy || blocked || view.isFetching} onClick={() => { void onSubmit({ kind: 'status', id, status: disabling ? 'DISABLED' : 'ACTIVE' }) }}>{busy ? 'Saving...' : disabling ? 'Disable application' : 'Enable application'}</Button></div>
    </>}
  </Dialog>
}
