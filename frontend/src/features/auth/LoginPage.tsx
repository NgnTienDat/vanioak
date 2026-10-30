import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { loginSchema } from './loginSchema.ts'
import type { LoginValues } from './loginSchema.ts'
import { useLogin } from './useLogin.ts'
import { Field } from '../../components/ui/Field.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Brand } from '../../components/ui/Brand.tsx'
import { Icon } from '../../components/ui/Icon.tsx'

export function LoginPage() {
  const { login, busy, notice } = useLogin()
  const { register, handleSubmit, resetField, formState: { errors, isSubmitting } } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { username: '', password: '' } })
  async function submit(values: LoginValues) {
    try { await login(values) } catch { /* Safe error remains in auth state. */ }
    finally { resetField('password') }
  }
  return <div className="login-page"><div className="login-art"><Brand />
    <div className="login-art-content"><div className="eyebrow">Observability, without the noise</div><h1>Every signal.<br /><span className="accent">One clear view.</span></h1><p>Find what matters in your application logs, track incidents and understand your systems in real time.</p><div className="login-visual" aria-hidden="true"><div className="signal-heading"><span><Icon name="terminal" size={14} /> Application signals</span><span>Signal preview</span></div>{[0,1,2,3,4].map(i => <div className="minibar" key={i} />)}</div></div>
    <span className="login-art-footer">VaniOAK Engineer Workspace</span></div>
    <div className="login-form-wrap"><form className="login-form" onSubmit={handleSubmit(submit)} aria-busy={busy || isSubmitting}><div className="eyebrow">Welcome back</div><h2>Sign in to VaniOAK</h2><p>Use your account to access authorized applications.</p>
      <Field label="Username" autoComplete="username" required maxLength={100} {...register('username')} error={errors.username?.message} disabled={busy || isSubmitting} />
      <Field label="Password" type="password" autoComplete="current-password" required {...register('password')} error={errors.password?.message} disabled={busy || isSubmitting} />
      {notice && <p className="form-feedback" role="alert">{notice}</p>}
      <Button className="btn-primary btn-wide" type="submit" disabled={busy || isSubmitting}>{busy ? 'Signing in...' : 'Sign in'} <Icon name="arrowRight" /></Button>
      <div className="form-hint"><strong><Icon name="info" size={14} /> Secure workspace access</strong><div>Access follows your role and application assignments. Reloading requires signing in again.</div></div><div className="login-version">Engineer experience &middot; Version 1.0</div>
    </form></div>
  </div>
}
