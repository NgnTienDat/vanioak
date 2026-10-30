export type IncidentStatus = 'OPEN' | 'RESOLVED'
export type IncidentLevel = 'ERROR' | 'CRITICAL'
export type IncidentScope = { applicationId: string; environmentId: string | null }
export type Incident = {
  id: string; application: { id: string; name: string }; environment: { id: string; name: string }
  level: IncidentLevel; status: IncidentStatus; fingerprint: string; message: string; error_count: number
  first_seen_at: string; last_seen_at: string; resolved_at?: string | null
}
export type IncidentEvent = {
  id: string; event_type: string; event_at: string; error_count?: number | null; message?: string | null
  metadata?: Record<string, unknown> | null
}
export type IncidentDetail = Incident & { events: IncidentEvent[] }
export type IncidentPage = { items: Incident[]; next_cursor: string | null }
export type IncidentParams = {
  application_id: string; environment_id?: string; status?: IncidentStatus; level?: IncidentLevel
  from?: string; to?: string; cursor?: string | null; limit?: number
}
export type IncidentResolution = {
  id: string; status: 'RESOLVED'; resolved_at: string | null
  resolution_source: 'MANUAL' | 'AUTOMATIC' | null; resolved_by_user_id: string | null
}
