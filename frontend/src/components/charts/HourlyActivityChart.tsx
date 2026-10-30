import { useId } from 'react'
import type { AnalyticsMetrics } from '../../features/analytics/analytics.types.ts'
import { PageState } from '../ui/PageState.tsx'
import { formatCount, severityGroups } from '../../lib/presentation.ts'

export function HourlyActivityChart({ metrics, kind = 'total' }: { metrics: AnalyticsMetrics; kind?: 'total' | 'stacked' }) {
  const id = useId()
  if (!metrics.hasData) return <PageState title="No matching logs" description="No hourly activity was returned for this reporting window." />
  const maximum = Math.max(1, ...metrics.hours.map(hour => hour.total))
  const x = (index: number) => metrics.hours.length === 1 ? 360 : 40 + index / (metrics.hours.length - 1) * 640
  const y = (value: number) => 165 - value / maximum * 145
  const groups = severityGroups(metrics.totals)
  const ticks = [...new Set([0, Math.round(maximum / 3), Math.round(maximum * 2 / 3), maximum])]
  const positions = [...new Set([0,1,2,3,4].map(value => Math.round(value * (metrics.hours.length - 1) / 4)))]
  const line = metrics.hours.map((hour,index) => `${index ? 'L' : 'M'} ${x(index)} ${y(hour.total)}`).join(' ')
  const last = metrics.hours.length - 1
  return <div className={`chart-body ${kind === 'stacked' ? 'stacked-chart' : ''}`}>
    <div className="chart-legend">{kind === 'total' ? <span><span className="legend-marker series-total" />Total log events</span> : groups.map(group => <span key={group.label}><span className="legend-marker" style={{ background: group.color }} />{group.label}</span>)}</div>
    {kind === 'total' ? <svg className="chart-svg" viewBox="0 0 700 195" preserveAspectRatio="none" role="img" aria-labelledby={`${id}-title ${id}-description`}>
      <title id={`${id}-title`}>Hourly log volume, UTC</title><desc id={`${id}-description`}>Total log counts. Exact values and partial boundary hours are available below.</desc>
      <defs><linearGradient id={`${id}-fill`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#38d5a9" stopOpacity=".24" /><stop offset="100%" stopColor="#38d5a9" stopOpacity="0" /></linearGradient></defs>
      {ticks.map(value => <g key={value}><line x1="40" x2="680" y1={y(value)} y2={y(value)} className="chart-grid" /><text x="0" y={y(value) + 3} className="chart-label">{formatCount(value)}</text></g>)}
      <path d={`${line} L ${x(last)} 165 L ${x(0)} 165 Z`} fill={`url(#${id}-fill)`} />
      <path d={line} fill="none" stroke="#42d8ac" strokeWidth="2.6" strokeLinecap="round" />
      <circle cx={x(last)} cy={y(metrics.hours[last].total)} r="4" fill="#52efbd" stroke="#123c30" strokeWidth="4" />
      {positions.map(index => <text key={index} x={x(index)} y="190" textAnchor={index === 0 ? 'start' : index === metrics.hours.length - 1 ? 'end' : 'middle'} className="chart-label">{metrics.hours[index].timestamp.slice(5,16).replace('T',' ')}</text>)}
    </svg> : <><div className="bar-grid" style={metrics.hours.length > 24 ? { gap: `${100 / metrics.hours.length}px` } : undefined} role="img" aria-label="Hourly log volume, UTC. Other, ERROR and CRITICAL; exact values below.">
      {metrics.hours.map(hour => <div className="bar-stack" key={hour.timestamp} title={`${hour.timestamp}: ${severityGroups(hour).map(group => `${group.label} ${formatCount(group.value)}`).join(', ')}${hour.partial ? ' (partial hour)' : ''}`}>
        {severityGroups(hour).reverse().map(group => group.value > 0 && <div className="barpart" key={group.label} style={{ background: group.color, height: `${group.value / maximum * 100}%` }} />)}
      </div>)}
    </div><div className="bar-labels">{positions.map(index => <span key={index}>{metrics.hours[index].timestamp.slice(5,16).replace('T',' ')}</span>)}</div></>}
    <p className="tiny-note">UTC hours; boundary hours may be partial.{kind === 'stacked' && ' Other includes all levels except ERROR and CRITICAL.'}</p>
    <details><summary>Hourly values (UTC)</summary><div className="table-scroll"><table><caption className="sr-only">Hourly aggregate values</caption><thead><tr><th scope="col">Hour (UTC)</th><th scope="col">Total</th><th scope="col">Other</th><th scope="col">ERROR</th><th scope="col">CRITICAL</th><th scope="col">Partial window</th></tr></thead>
      <tbody>{metrics.hours.map(hour => <tr key={hour.timestamp}><td className="mono">{hour.timestamp}</td><td>{formatCount(hour.total)}</td><td>{formatCount(hour.total - hour.errors - hour.critical)}</td><td>{formatCount(hour.errors)}</td><td>{formatCount(hour.critical)}</td><td>{hour.partial ? 'Yes' : 'No'}</td></tr>)}</tbody></table></div></details>
  </div>
}
