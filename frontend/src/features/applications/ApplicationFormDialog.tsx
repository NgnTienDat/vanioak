import { useId } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { applicationSchema } from './applicationSchema.ts'
import { useApplication } from './useApplications.ts'
import type { Application, ApplicationCommand, CreateApplication, EditApplication } from './application.types.ts'
import type { ApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Field } from '../../components/ui/Field.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

type Props = { id?: string; busy: boolean; blocked: boolean; error: ApiError | null; notice: string
  onClose: () => void; onCheck: () => void; onSubmit: (command: ApplicationCommand) => Promise<void> }

function ApplicationForm({ application, ...props }: Props & { application?: Application }) {
  const descriptionId = useId()
  const { register, handleSubmit, formState: { errors, dirtyFields, isSubmitting } } = useForm<CreateApplication>({
    resolver: zodResolver(applicationSchema), defaultValues: { name: application?.name ?? '', description: application?.description ?? '' },
  })
  const busy = props.busy || isSubmitting
  const disabled = application?.status === 'DISABLED'
  async function submit(values: CreateApplication) {
    if (application) {
      const fields: EditApplication = {}
      if (dirtyFields.name) fields.name = values.name
      if (dirtyFields.description) fields.description = values.description
      await props.onSubmit({ kind: 'edit', id: application.id, values: fields })
    } else await props.onSubmit({ kind: 'create', values })
  }
  return <form className="modal-form" onSubmit={handleSubmit(submit)} aria-busy={busy}>
    <p>Applications organize log streams, access and alerting configuration.</p>
    <div className="form-grid"><div className="span-all"><Field label="Application name" maxLength={100} autoFocus autoComplete="off" {...register('name')} error={errors.name?.message} disabled={busy || disabled} /></div>
      <div className="span-all"><label className="form-label" htmlFor={descriptionId}>Description <span className="small-note">(optional)</span></label><textarea id={descriptionId} placeholder="What does this application do?" {...register('description')} disabled={busy || disabled} /></div></div>
    {disabled && <p role="status">Enable this application before editing its details.</p>}
    {props.error && <p className="form-feedback" role="alert">{props.error.message}</p>}
    {props.notice && <p className="small-note" role="status">{props.notice}</p>}
    {props.blocked && <Button onClick={props.onCheck} disabled={busy}>Check server state</Button>}
    <div className="modal-actions"><Button onClick={props.onClose} disabled={busy}>Cancel</Button><Button type="submit" className="btn-primary" disabled={busy || props.blocked || disabled || (!!application && !dirtyFields.name && !dirtyFields.description)}>{busy ? 'Saving...' : application ? 'Save changes' : 'Create application'} <Icon name="check" size={14} /></Button></div>
  </form>
}

function EditApplicationForm(props: Props & { id: string }) {
  const view = useApplication(props.id)
  if (view.error) return <PageState title="Application unavailable" description={view.error.message} error retry={() => { void view.refetch() }} />
  if (!view.data) return <PageState title="Loading application..." busy />
  return <ApplicationForm {...props} application={view.data} />
}
export function ApplicationFormDialog(props: Props) {
  return <Dialog title={props.id ? 'Edit application' : 'Create application'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {props.id ? <EditApplicationForm {...props} id={props.id} /> : <ApplicationForm {...props} />}
  </Dialog>
}
