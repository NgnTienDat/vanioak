import { useEffect } from 'react'
import { Icon } from './Icon.tsx'
export function Toast({ message, dismiss, duration = 2700, success = false }: { message: string; dismiss: () => void; duration?: number; success?: boolean }) {
  useEffect(() => { const timer = setTimeout(dismiss, duration); return () => clearTimeout(timer) }, [dismiss, duration])
  return <div className="toast" role="status" aria-live="polite"><Icon name={success ? 'circleCheck' : 'info'} size={success ? 17 : 15} /><span>{message}</span></div>
}
