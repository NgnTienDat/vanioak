import { useEffect, useRef } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { createUserSchema, editUserSchema, userEditFields } from './userSchema.ts'
import { useUser } from './useUsers.ts'
import type { CreateUser, Engineer, UserCommand } from './user.types.ts'
import type { ApiError } from '../../lib/apiError.ts'
import { normalizeApiError } from '../../lib/apiError.ts'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Field } from '../../components/ui/Field.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { PageState } from '../../components/ui/PageState.tsx'

export type UserDialogProps = { id?: string; busy: boolean; blocked: boolean; error: ApiError | null; notice: string
  onClose: () => void; onCheck: () => void; onDenied: () => void; onSubmit: (command: UserCommand) => Promise<void> }
function UserForm({ user, ...props }: UserDialogProps & { user?: Engineer }) {
  const { register, handleSubmit, resetField, formState: { errors, isSubmitting, dirtyFields } } = useForm<CreateUser>({
    resolver: zodResolver(user ? editUserSchema : createUserSchema), defaultValues: { username: user?.username ?? '', password: '' },
  })
  const passwordInput = useRef<HTMLInputElement | null>(null)
  const password = register('password')
  useEffect(() => () => { resetField('password', { defaultValue: '' }); if (passwordInput.current) passwordInput.current.value = '' }, [resetField])
  const busy = props.busy || isSubmitting
  async function submit(values: CreateUser) {
    const command: UserCommand = user ? { kind: 'edit', id: user.id, values: userEditFields(user.username, values) } : { kind: 'create', values: { ...values } }
    values.password = ''
    try {
      await props.onSubmit(command)
    } finally { resetField('password', { defaultValue: '' }); if (passwordInput.current) passwordInput.current.value = '' }
  }
  return <form className="modal-form" onSubmit={handleSubmit(submit)} aria-busy={busy}>
    <p>{user ? 'Update this Engineer account. Leave the password blank to keep it unchanged.' : 'Create an active Engineer account. Application access is assigned separately.'}</p>
    <div className="form-grid"><div className="span-all"><Field label="Username" maxLength={100} autoFocus autoComplete="off" {...register('username')} error={errors.username?.message} disabled={busy || props.blocked} /></div>
      <div className="span-all"><Field label={user ? 'Replacement password (optional)' : 'Password'} type="password" autoComplete="new-password" {...password} ref={element => { password.ref(element); passwordInput.current = element }} error={errors.password?.message} disabled={busy || props.blocked} /></div></div>
    {props.error && <p role="alert" className="form-feedback">{props.error.message}</p>}
    {props.notice && <p role="status" className="small-note">{props.notice}</p>}
    {props.blocked && <Button onClick={props.onCheck} disabled={busy}>Check current account state</Button>}
    <div className="modal-actions"><Button onClick={props.onClose} disabled={busy}>Cancel</Button><Button type="submit" className="btn-primary" disabled={busy || props.blocked || (!!user && !dirtyFields.username && !dirtyFields.password)}>{busy ? 'Saving...' : user ? 'Save changes' : 'Create user'} <Icon name="check" size={14} /></Button></div>
  </form>
}
function EditUserForm(props: UserDialogProps & { id: string }) {
  const view = useUser(props.id)
  const denied = view.error && normalizeApiError(view.error).status === 403
  const onDenied = props.onDenied
  useEffect(() => { if (denied) onDenied() }, [denied, onDenied])
  if (view.error) return <PageState title="Engineer unavailable" description={normalizeApiError(view.error).message} error retry={() => { void view.refetch() }} />
  if (!view.data || view.isFetching) return <PageState title="Loading Engineer..." busy />
  return <UserForm {...props} user={view.data} />
}
export function UserFormDialog(props: UserDialogProps) {
  return <Dialog title={props.id ? 'Edit Engineer' : 'Create user'} variant="admin" busy={props.busy} onClose={props.onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    {props.id ? <EditUserForm {...props} id={props.id} /> : <UserForm {...props} />}
  </Dialog>
}
