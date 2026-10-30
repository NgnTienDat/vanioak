export function formatCount(value: number) { return new Intl.NumberFormat('en-US').format(value) }
export function formatTimestamp(value: string, detail = false) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return 'Not recorded'
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: '2-digit', month: 'short',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', ...(detail ? { year: 'numeric', second: '2-digit' } as const : {}) }).format(date)
}
export function relativeTime(value: string, now = Date.now()) {
  const elapsed = now - Date.parse(value)
  if (!Number.isFinite(elapsed)) return 'Not recorded'
  const minutes = Math.floor(Math.abs(elapsed) / 60000)
  if (!minutes) return 'just now'
  const amount = minutes < 60 ? `${minutes}m` : minutes < 1440 ? `${Math.floor(minutes / 60)}h` : `${Math.floor(minutes / 1440)}d`
  return elapsed < 0 ? `in ${amount}` : `${amount} ago`
}
export function lifecycleLabel(type: string) {
  return ({ OPENED: 'Incident opened', COUNT_UPDATED: 'Occurrence count updated', RESOLVED: 'Incident resolved' } as Record<string, string>)[type] ?? type
}
export function severityGroups(values: { total: number; errors: number; critical: number }) {
  return [{ label: 'Other', value: values.total - values.errors - values.critical, color: '#3cbe99' },
    { label: 'ERROR', value: values.errors, color: '#ed7f8a' }, { label: 'CRITICAL', value: values.critical, color: '#ff616e' }]
}
export function plainRowClick(event: { button: number; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean }) {
  return event.button === 0 && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey
}
