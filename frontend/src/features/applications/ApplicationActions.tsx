import { useEffect, useId, useRef, useState } from 'react'
import type { Application } from './application.types.ts'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { applicationMenuKey } from '../../lib/adminPresentation.ts'

export function ApplicationActions({ application, locked, onEdit, onStatus }: {
  application: Application; locked: boolean; onEdit: () => void; onStatus: () => void
}) {
  const id = useId(), trigger = useRef<HTMLButtonElement>(null), menu = useRef<HTMLDivElement>(null)
  const edge = useRef<'first' | 'last'>('first')
  const [open, setOpen] = useState(false), [position, setPosition] = useState({ top: 0, left: 0 })
  useEffect(() => {
    const hide = (event: Event) => { if (!(event.target instanceof Node && menu.current?.contains(event.target))) menu.current?.hidePopover() }
    window.addEventListener('resize', hide); window.addEventListener('scroll', hide, true)
    return () => { window.removeEventListener('resize', hide); window.removeEventListener('scroll', hide, true) }
  }, [])
  function items() { return [...menu.current!.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')] }
  function close() { menu.current?.hidePopover(); trigger.current?.focus() }
  function place(button: HTMLButtonElement) {
    const rect = button.getBoundingClientRect()
    setPosition({ left: Math.max(8, Math.min(rect.right - 210, window.innerWidth - 218)), top: Math.max(8, Math.min(rect.bottom + 6, window.innerHeight - 108)) })
  }
  return <><button type="button" ref={trigger} className="btn btn-sm btn-ghost" popoverTarget={id} disabled={locked}
    aria-label={`More actions for ${application.name}`} aria-haspopup="menu" aria-controls={id} aria-expanded={open}
    onClick={event => { edge.current = 'first'; place(event.currentTarget) }} onKeyDown={event => {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
      event.preventDefault(); edge.current = event.key === 'ArrowUp' ? 'last' : 'first'; place(event.currentTarget); menu.current?.showPopover()
      const actions = items(); (event.key === 'ArrowUp' ? actions.at(-1) : actions[0])?.focus()
    }}><Icon name="dots" size={17} /></button>
    <div ref={menu} id={id} popover="auto" role="menu" aria-label={`Actions for ${application.name}`} className="admin-application-menu" style={position}
      onToggle={event => { const expanded = event.newState === 'open'; setOpen(expanded); if (expanded) { const actions = items(); (edge.current === 'last' ? actions.at(-1) : actions[0])?.focus() } }}
      onKeyDown={event => {
        const actions = items(), result = applicationMenuKey(event.key, actions.indexOf(document.activeElement as HTMLButtonElement), actions.length)
        if (result === null) return
        if (event.key !== 'Tab') event.preventDefault()
        if (result === 'close') close(); else actions[result]?.focus()
      }}>
      <Button role="menuitem" tabIndex={-1} className="btn-ghost" disabled={locked || application.status === 'DISABLED'}
        title={application.status === 'DISABLED' ? 'Enable this application before editing' : 'Edit application details'} aria-label={`Edit ${application.name}`}
        onClick={() => { close(); onEdit() }}>Edit application</Button>
      <Button role="menuitem" tabIndex={-1} className="btn-ghost" disabled={locked} aria-label={`${application.status === 'ACTIVE' ? 'Disable' : 'Enable'} ${application.name}`}
        onClick={() => { close(); onStatus() }}>{application.status === 'ACTIVE' ? 'Disable' : 'Enable'} application</Button>
    </div></>
}
