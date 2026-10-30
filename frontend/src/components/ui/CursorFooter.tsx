import { Button } from './Button.tsx'
export function CursorFooter({ count, next, busy, more, noun = 'logs' }: { count: number; next: string | null; busy: boolean; more: () => void; noun?: string }) {
  return <div className="table-footer" aria-live="polite"><span>{count} {noun} loaded</span>
    {next && <Button disabled={busy} onClick={more}>{busy ? 'Loading…' : 'Load more'}</Button>}
  </div>
}
