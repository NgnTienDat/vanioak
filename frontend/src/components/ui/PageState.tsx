import { Button } from './Button.tsx'
import { Icon } from './Icon.tsx'
export function PageState({ title, description, busy = false, error = false, retry }: {
  title: string; description?: string; busy?: boolean; error?: boolean; retry?: () => void
}) {
  return <div className="empty-state" role={error ? 'alert' : 'status'} aria-busy={busy}>
    <div className="empty-icon"><Icon name={error ? 'alert' : 'inbox'} size={24} /></div>
    <strong>{title}</strong>{description && <p>{description}</p>}
    {retry && <Button onClick={retry}>Try again</Button>}
  </div>
}
