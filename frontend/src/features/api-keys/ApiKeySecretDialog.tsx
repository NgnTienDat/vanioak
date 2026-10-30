import { useState } from 'react'
import { Dialog } from '../../components/ui/Dialog.tsx'
import { Button } from '../../components/ui/Button.tsx'
import { Icon } from '../../components/ui/Icon.tsx'
import { Toast } from '../../components/ui/Toast.tsx'
export function ApiKeySecretDialog({ secret, busy, onClose, success = '', dismissSuccess }: { secret: string; busy: boolean; onClose: () => void; success?: string; dismissSuccess: () => void }) {
  const [feedback, setFeedback] = useState('')
  async function copy() {
    dismissSuccess()
    try { await navigator.clipboard.writeText(secret); setFeedback('API key copied.') }
    catch { setFeedback('Unable to copy. Select the key and copy it manually.') }
  }
  return <Dialog title="API key generated" variant="admin" busy={busy} onClose={onClose} badge={<div className="eyebrow">VaniOAK &middot; Admin</div>}>
    <p>Copy this secret now. It will not be shown again after closing this dialog.</p><div className="tag orange"><Icon name="lock" size={13} /> One-time secret</div>
    <div className="secret-box" aria-label="One-time API key">{secret}</div>
    <div className="hint-panel warning"><Icon name="info" size={16} /><span>The key is held only in this view. Closing clears it from the view; a copy already in your system clipboard remains until replaced.</span></div>
    <div className="modal-actions"><Button onClick={() => { void copy() }}><Icon name="copy" size={15} /> Copy key</Button><Button className="btn-primary" disabled={busy} onClick={onClose}>Done</Button></div>
    {feedback && feedback !== 'API key copied.' ? <p className="form-feedback" role="alert">{feedback}</p> : feedback ? <Toast message={feedback} success duration={2600} dismiss={() => setFeedback('')} /> : success && <Toast message={success} success duration={2600} dismiss={dismissSuccess} />}
  </Dialog>
}
