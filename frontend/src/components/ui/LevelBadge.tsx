import type { LogLevel } from '../../features/logs/log.types.ts'
export function LevelBadge({ level }: { level: LogLevel }) {
  return <span className={`level ${level}`}>{level}</span>
}
