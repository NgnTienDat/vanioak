import type { ReactNode } from 'react'
const paths: Record<string, ReactNode> = {
  dots: <><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></>,
  lock: <><rect x="4" y="11" width="16" height="11" rx="2"/><path d="M8 11V7a4 4 0 1 1 8 0v4"/></>,
  circleCheck: <><circle cx="12" cy="12" r="9"/><path d="m8 12 2.5 2.5L16 9"/></>,
  bolt: <path d="m13 2-9 11h7l-1 9 10-12h-7V2Z"/>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4M17 3v4M3 10h18"/></>,
  copy: <><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></>,
  refresh: <><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.5 9A7 7 0 0 1 18 7l2 5M4 12l2 5a7 7 0 0 0 12.5-2"/></>,
  left: <path d="m15 18-6-6 6-6"/>,
  right: <path d="m9 18 6-6-6-6"/>,
  check: <path d="m5 12 4 4L19 6"/>,
  clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/></>,
  close: <path d="M5 5l14 14M19 5 5 19"/>,
  activity: <><path d="M3 12h4l3-8 4 16 3-8h4"/></>,
  layers: <><path d="m12 2 9 5-9 5-9-5 9-5Zm-9 10 9 5 9-5M3 17l9 5 9-5"/></>,
  grid: <><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
  shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/></>,
  logs: <><path d="M4 5h16M4 10h16M4 15h11M4 20h7"/></>,
  alert: <><path d="M10.3 3.7 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4m0 4h.01"/></>,
  chart: <><path d="M3 3v18h18M6 15l5-5 4 3 5-7"/></>,
  settings: <><circle cx="12" cy="12" r="3"/><path d="m4 12 1-2-1-2 3-3 2 1 2-1h2l2 1 2-1 3 3-1 2 1 2v2l-1 2 1 2-3 3-2-1-2 1h-2l-2-1-2 1-3-3 1-2-1-2Z"/></>,
  info: <><circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/></>,
  logout: <><path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h7a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-7"/></>,
  server: <><rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6h.01M7 17h.01"/></>,
  arrowRight: <><path d="M4 12h16m-6-6 6 6-6 6"/></>,
  chevron: <><path d="m6 9 6 6 6-6"/></>,
  search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
  inbox: <><path d="M4 4h16l2 11v5H2v-5L4 4Z"/><path d="M2 15h6l2 3h4l2-3h6"/></>,
  terminal: <><rect x="3" y="4" width="18" height="16" rx="2"/><path d="m7 9 3 3-3 3m6 0h4"/></>,
}
export function Icon({ name, size }: { name: string; size?: number }) {
  return <svg className="icon" style={size === undefined ? undefined : { width: size, height: size }} viewBox="0 0 24 24" aria-hidden="true">{paths[name] ?? paths.grid}</svg>
}
