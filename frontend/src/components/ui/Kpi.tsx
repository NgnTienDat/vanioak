import type { ReactNode } from 'react'
import { Icon } from './Icon.tsx'
import { formatCount } from '../../lib/presentation.ts'
export function Kpi({ title, value, icon, note, accent }: { title: string; value: ReactNode; icon: string; note: string; accent?: 'open' }) {
  return <div className="kpi"><div className="kpi-title">{title}<Icon name={icon} /></div><div className={`kpi-val ${accent === 'open' ? 'kpi-open' : ''}`}>{typeof value === 'number' ? formatCount(value) : value}</div><div className="kpi-sub">{note}</div></div>
}
