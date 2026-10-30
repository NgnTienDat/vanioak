export function adminFeedback(confirmed: boolean, success: string, notice = '') {
  return confirmed ? { success, notice: '' } : { success: '', notice }
}

export function enabledFromSelect(value: unknown): boolean {
  if (value === true || value === 'true') return true
  if (value === false || value === 'false') return false
  throw new Error('Select Enabled or Disabled.')
}

export function applicationMenuKey(key: string, current: number, count: number): number | 'close' | null {
  if (key === 'Escape' || key === 'Tab') return 'close'
  if (!count) return null
  if (key === 'Home') return 0
  if (key === 'End') return count - 1
  if (key === 'ArrowDown') return (current + 1 + count) % count
  if (key === 'ArrowUp') return (current - 1 + count) % count
  return null
}
