import { formatTimestamp, relativeTime } from '../../lib/presentation.ts'
export function Timestamp({ value, relative = false, detail = false }: { value: string; relative?: boolean; detail?: boolean }) {
  return <time dateTime={value} title={`${value} — UTC display`} aria-label={`${formatTimestamp(value, true)} UTC`}>{relative ? relativeTime(value) : formatTimestamp(value, detail)}</time>
}
