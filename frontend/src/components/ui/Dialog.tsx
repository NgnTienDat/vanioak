import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'
import { Button } from './Button.tsx'
import { Icon } from './Icon.tsx'

export function Dialog({ title, onClose, children, variant = 'drawer', busy = false, badge }: { title: string; onClose: () => void; children: ReactNode; variant?: 'drawer' | 'modal' | 'admin'; busy?: boolean; badge?: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current!
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    dialog.showModal()
    document.body.style.overflow = 'hidden'
    return () => {
      dialog.close()
      document.body.style.overflow = overflow
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus()
    }
  }, [])
  const close = () => { if (!busy) onClose() }
  return <dialog ref={ref} className={variant === 'drawer' ? 'drawer-layer' : 'modal-layer'} aria-labelledby={titleId} aria-busy={busy} onCancel={event => { event.preventDefault(); close() }}>
    <button className={variant === 'drawer' ? 'drawer-scrim' : 'modal-scrim'} aria-label={`Close ${title}`} onClick={close} disabled={busy} tabIndex={-1} />
    <section className={variant === 'drawer' ? 'drawer' : variant === 'admin' ? 'modal-admin' : 'modal'}><header className={variant === 'drawer' ? 'drawer-header' : variant === 'admin' ? 'admin-modal-heading' : 'modal-heading'}><div>{badge}{variant === 'drawer' && <div className="eyebrow">Event inspector</div>}<h2 id={titleId}>{title}</h2></div>
      <Button className="btn-sm" onClick={close} disabled={busy} aria-label={`Close ${title}`}><Icon name="close" size={16} /></Button>
    </header><div className={variant === 'drawer' ? 'drawer-body' : undefined}>{children}</div></section>
  </dialog>
}
