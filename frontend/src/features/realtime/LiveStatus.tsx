import type { RealtimeView } from './realtime.types.ts'
import { Button } from '../../components/ui/Button.tsx'

const labels = { connecting: 'Connecting', live: 'Live streaming', reconnecting: 'Reconnecting', paused: 'Live paused',
  disconnected: 'Disconnected', forbidden: 'Forbidden', unavailable: 'Unavailable' }
export function LiveStatus({ realtime }: { realtime: RealtimeView }) {
  return <div className="realtime-controls">
    <Button className={realtime.phase === 'live' ? 'btn-primary' : ''} aria-pressed={realtime.enabled}
      aria-label={realtime.enabled ? 'Pause live updates' : 'Resume live updates'} onClick={realtime.toggle}>
      <span className={`dot ${realtime.phase === 'live' ? 'pulse' : ''}`} />{labels[realtime.phase]}
    </Button>
    {!realtime.enabled && realtime.phase !== 'paused' && <Button onClick={realtime.retry}>Retry</Button>}
    <span className={`tiny-note realtime-notice ${realtime.phase === 'live' ? 'sr-only' : ''}`} role="status" aria-live="polite">{realtime.notice}</span>
  </div>
}
