import { useId } from 'react'
import type { ComponentPropsWithRef } from 'react'
import { Icon } from './Icon.tsx'
export function Field({ label, error, icon, ...props }: ComponentPropsWithRef<'input'> & { label: string; error?: string; icon?: string }) {
  const id = useId()
  const input = <input id={id} className="field" aria-invalid={!!error} aria-describedby={error ? `${id}-error` : undefined} {...props} />
  return <><label className="form-label" htmlFor={id}>{label}</label>{icon ? <div className="field-with-icon"><Icon name={icon} size={16} />{input}</div> : input}{error && <p id={`${id}-error`} className="form-feedback" role="alert">{error}</p>}</>
}
