# VaniOAK Frontend F1

One React/TypeScript/Vite app. [DESIGN](../docs/frontend/DESIGN.md) and the frozen
[Engineer](../docs/frontend/prototypes/engineer-v1.0.html)/
[Admin](../docs/frontend/prototypes/admin-v1.0.html) references own visuals.
[OpenAPI](../docs/contracts/openapi.yaml), REST/SSE/security and the
[backend handoff](../docs/operations/backend-mvp-integration.md) own runtime behavior.

## Architecture

Page -> feature hook -> domain API -> shared Axios transport -> backend.

| Location | Responsibility |
|---|---|
|src/api/|Bare auth/protected Axios clients, domain HTTP + envelope/DTO validation|
|src/features/|Login RHF/Zod, auth mutations, scoped application query hooks/types/keys|
|src/store/authStore.ts|Memory-only Zustand auth/session transitions|
|src/lib/|Safe errors and QueryClient/cache lifecycle|
|src/app/|Hash route decisions and stable provider/bootstrap wiring|
|src/components/|Existing UI/layout, no HTTP calls|

React Query owns server pages; Zustand never duplicates application lists. Keys
include actor, role, session generation, filters/cursor/limit, never credentials.
Logout/session changes cancel/remove sensitive queries and mutations; generations
also reject late results. In-flight auth writes may already have reached the server;
cache cancellation is not server rollback. Their results cannot restore an old session.

## Development

The ignored local frontend/.env.development.local contains non-secret settings:

```dotenv
BACKEND_PROXY_TARGET=http://localhost:8080
VITE_API_BASE_URL=/api/v1
```

With an approved development backend already available, from the repository root:

```powershell
npm --prefix frontend run dev
```

No manual PowerShell variable is needed with that local file. Existing process env
can override it. The URL is a requested dev setting, not proof backend is running
or permission to start one. .env.example documents settings; local files are ignored.
Never put tokens/passwords/API keys in VITE_* or source. API base is constrained to
same-origin /api/v1; Vite proxies to the server-only target with path/Bearer pass-through.
No fixture fallback or CORS workaround. Production hosting needs its own proxy.

## Sessions and UI

Login uses username/password, ADMIN/ENGINEER only. Refresh sends JSON refresh_token,
rotates both tokens atomically and preserves current login user (response has no user).
No cookie refresh, /me, JWT role decoding, local/sessionStorage or reload restoration.
Reload requires login and does not revoke existing server refresh sessions.

Single-flight renewal, one eligible GET replay maximum, no mutation retries or403
refresh. Old generation/revision responses cannot overwrite a new account/session.
Logout blocks new work, waits for renewal and uses the latest pair. Local clearing
always happens; unconfirmed server revocation is reported. Password form state is
reset; auth mutations have void variables/results and immediate GC, no devtools.

Engineer gets assigned application cards; Admin a read-only table. Cursor pages and
disabled scopes are preserved. Search filters loaded names only; no fabricated total.
App deep links resolve through authorized pages without fallback. Workspace shell
has no fake metrics, working F2/F3 screens, CRUD or actual SSE stream.

## Verification

```powershell
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run lint
```

Node25 runner executes pure TypeScript, fake Axios adapters and real QueryClient/
QueryObserver, not DOM/network. TypeScript6.0.3 is pinned for the existing ESLint
compatibility. Regression mapping preserves all18 original F1 scenarios and adds
transport/cache/schema/env cases. Browser/layout/hook DOM checks remain separate.

The opt-in smoke script remains behind F1_SMOKE_APPROVED=true plus process-only
F1_SMOKE_ORIGIN/F1_SMOKE_USERNAME/F1_SMOKE_PASSWORD. Normal login -> list -> refresh
-> list -> logout, current-pair cleanup in finally, safe status-only output. Unknown
refresh outcome never replays the consumed token. Smoke authorization is separate;
this refactor does not start services, provision accounts or run live requests.

## F2.1 Logs Explorer

Open an authorized application, then Logs Explorer. Environment selection uses real
IDs; All environments omits the filter. The 1h/24h/7d selector captures an exact UTC
window. Message contains, exact Trace ID and Level are submitted with Apply; Reset
clears those three filters. Refresh advances the window, retaining applied filters.
Load more repeats the frozen window/filters and opaque cursor; no total or numbered
pages are invented. Changes to scope/window/filters start a new chain.

GET /api/v1/logs uses the shared protected Axios client. The domain API decodes actual
LogRecord fields, then Query owns the pages. Inspect opens a keyboard-accessible
native dialog drawer showing that REST record and metadata; no log-by-ID endpoint.
No host search or simulated stream is provided; F2.2 adds the actual SSE transport
described below. Disabled scopes retain reads.
Overview/Analytics now use actual aggregates in F2.4; management Settings remains unavailable.

## F2.2 Realtime SSE

The authorized workspace opens one same-origin Fetch stream at
GET /api/v1/realtime/stream?application_id=... with the current Bearer header.
ALL omits environment_id; no level filter, cookie/query token or Last-Event-ID replay.
The shared auth refresh operation handles rotation; tokens stay in memory.

Live streaming/Connecting/Reconnecting/Paused/Disconnected/Forbidden/Unavailable
reflect actual transport state. Use the Live button to Pause/Resume, or explicit
Retry after failure/exhaustion. HTTP403 stops and removes scoped cached records;
no refresh loop. Paused preference survives environment switches within a workspace.
One reader is retired before replacement; unmount/logout cancels timers and work.

Retry delays are 1/2/4/8/16 seconds (five reconnects per outage). The budget resets
only after 30 seconds still open with at least one complete SSE block. A 25-second
connection/renewal deadline and 45-second complete-block idle watchdog retire stalled
connections; these are not a total lifetime limit on an active stream. The backend's
normal heartbeat is 15 seconds. Invalid UTF8/framing/payload/content type or pending
block over 1 MiB closes the stream and reports out-of-sync; unknown events are ignored.

Events coalesce log reconciliation at most once per second; a pending REST read may
settle first. Query refetches actual records; incomplete SSE payloads are not rows.
Live advances the UTC snapshot/head revision and resets cursor history. Pause before
Load More. Reconnect/resume reconciles via REST, not replay; gap-free delivery and
immediate ClickHouse visibility are not guaranteed. Old unobserved live snapshots
are garbage-collected. Incident invalidation and 30-second Analytics invalidation
use scoped Query caches. F2.3 supplies Incident UI; F2.4 supplies Analytics/Overview.

Offline tests use controlled Fetch streams, real QueryClient and injected clocks;
they do not establish browser/DOM, proxy buffering, backend readiness or live SSE
acceptance. A production/reverse proxy must pass Authorization/stream bytes without
buffering and permit long-lived reads. Backend Realtime readiness, migrations and
any recovery-marker/consumer activation require separate operator approval.

## F2.3 Incidents and manual resolution

Open Incidents in an authorized workspace. Status/severity/app/env/last_seen_at UTC
range map to GET /api/v1/incidents with cursor Load More. Search and KPI cards cover
loaded records under current filters only; no total-count/text-search endpoint.
The Incident range is frozen separately from rolling log snapshots. Incident events
and reconnect reset its latest head; Pause retains the snapshot for historical pages.

Detail GET /api/v1/incidents/{id} validates the route application/environment and is
independent of list time bounds. History is newest-first, newest100 only. Resolution
source/actor comes from RESOLVED history metadata; legacy missing data is Unknown/
Not recorded. Actor is a user UUID, not an invented display name. Related logs opens
an editable message draft, actual env and selected bounded log preset; press Apply.
It does not promise exact fingerprint/receipt association or historical log retention.

An OPEN Incident may be explicitly resolved by ADMIN/assigned ENGINEER, even in
disabled app/env scopes. Confirmation sends PATCH /incidents/{id}/status with only
status=RESOLVED. Server remains permission authority. Fresh GET precedes the write;
double-confirm shares one operation, no optimistic status/history/audit insertion.
PATCH response audit is shown immediately and real detail/list are invalidated.
SSE echoes invalidate, not append a timeline or create another connection.

PATCH is never automatically replayed after401/network failure. A401 may renew the
shared session for GET reconciliation, then requires explicit confirmation. An
uncertain result is reported honestly, even if GET finds RESOLVED (another actor or
automatic resolution may have won). If status cannot be checked, Check current
status is GET-only and confirmation remains blocked. Unmount/logout cancellation
discards stale responses but does not prove an already-sent write rolled back.

**V3 has not been verified deployed on a live database for this feature.** Live
manual-resolution smoke needs separate endpoint/accounts/schema/V3/data-ownership
approval. Do not run Flyway, activate consumers or recover markers from frontend.

## F2.4 Analytics and Overview

GET /api/v1/analytics/health uses the existing protected Axios client with actual
application_id/environment_id, required from/to (maximum7 days), bucket=hour.
ALL omits environment_id. Query keys include session/app/env/window/reconciliation
revision. Successful snapshots can be shared between Analytics and Overview; no
additional store, endpoint or chart dependency.

Metrics are real total/ERROR/CRITICAL sums. Error rate is the weighted ratio
sum(ERROR+CRITICAL)/sum(total), shown as a percentage; no-data rate is N/A. Invalid/
partial DTOs, foreign scope, duplicate buckets, inconsistent ratios and unsafe
numeric sums fail closed. A missing section is unavailable, not a fabricated zero.
No service-health badge, INFO/WARN distribution, previous-period delta or AI insight.

Hourly SVG/chart table uses UTC, flags partial boundary hours and derives zero gaps
only from a successful aggregate response (backend groups only matching rows).
Environment breakdown uses returned environment IDs with authorized metadata labels;
no invented absent environments. Empty dataset shows No matching logs.

Analytics has its own frozen UI window so per-log events cannot restart its requests.
The existing workspace SSE coalesces analytics reconciliation at30 seconds, deferring
while an aggregate is in flight. Reconnect/manual Refresh may reconcile sooner.
No stream payload increments a metric or fills a REST row; no second stream.

Overview reuses Analytics, Log and Incident hooks/keys: latest6 logs and recent4
Incidents from bounded first pages (limit50), with explicitly loaded-only Incident
counts. No exhaustive history scan. Sections show their own snapshot bounds and
independent loading/empty/error/denied state; successful sections survive partial
outage. Log inspection reuses the actual REST-record drawer.

F1–F2.4 offline checks do not certify browser rendering, live REST/SSE delivery or
backend readiness. Native/V3/deployment, live smoke and physical TTL acceptance are
separate approvals. Existing Vite chunk warning is retained, not suppressed.

## F3.1 Admin Applications

ADMIN Applications supports create, name/description edit and separate enable/disable
confirmation through POST /api/v1/applications and PATCH /api/v1/applications/{id}.
Disabled applications require explicit reactivation before detail editing. Summary
cards and name search cover loaded results only; status filtering is server-side,
pagination uses the actual cursor and Open reuses the existing workspace.

Writes are not optimistically applied or automatically replayed. Fresh authorized
listing checks the target before PATCH. Lost/invalid write responses trigger GET-only
reconciliation; an unconfirmed result is not success, and a failed check blocks
further submissions until Check server state succeeds. Cancellation cannot undo a
committed server write. Completed writes retire old list/selection snapshots.

Application Settings is implemented in F3.2 below; Users Management is covered in F3.4.
Live REST/browser checks require separately safe conditions;
offline checks do not prove server availability or permissions. No native consumers/
resolver, migrations, recovery fences, backend provisioning or Telegram activation.

## F3.2 Application Settings

ADMIN route `#/applications/:id/settings` resolves the actual application through
the existing paged listing; missing/denied IDs never select another application.
Management pages do not mount WorkspaceProvider or open SSE. View workspace reuses
the existing shared screens and their single-stream lifecycle.

General reuses F3.1 edit/status dialogs, RHF/Zod validation, dirty-field PATCH and
uncertain-write reconciliation. Disabled applications require reactivation before
detail edits. Environments shows actual UUID/name/status as a read-only table;
there are no environment toggles or fabricated key counts.

Engineers uses GET /applications/{id}/engineers, limit50/cursor. Active and disabled
assigned accounts remain visible, including for disabled applications. Counts say
loaded until cursor exhaustion. Grant selects from ACTIVE ENGINEER directory pages
at GET /users and checks the candidate through GET /users/{id}. It checks current
membership through fresh roster pages before writing; absence from a partial page
does not mean unassigned. Assignment applies across all environments.

POST/DELETE /applications/{id}/engineers/{userId} require confirmation and share a
pending command lock. No optimistic membership or automatic write replay. Confirmed
changes retire old roster snapshots and fetch the authoritative first page. Lost
responses remain unconfirmed even if GET finds the desired state; a failed GET
blocks another change until Check current assignments succeeds. Cancellation does
not roll back an already-sent write. Generation/phase checks reject late responses;
403 hides and clears scoped sensitive data.

The five-tab Admin composition follows the frozen prototype with scoped styles,
keyboard tab navigation and native dialogs. F3.3 enables API Keys/Alert Rules below;
Users Management remains F3.4. Browser layout/focus and deployed roster
REST integration are NOT VERIFIED by offline Node tests.

## F3.3 API Keys and Alert Rules

API Keys selects one actual application environment (DEV by default when present),
including navigation from the read-only Environments table. GET/POST
`/applications/{appId}/environments/{envId}/api-keys`, POST `/api-keys/{id}/rotate`
and DELETE `/api-keys/{id}` use the existing protected client and real metadata.
Pagination keeps cursor/limit50 scoped to that environment. Create accepts optional
future UTC `expires_at`; rotate keeps expiry and revokes the old credential. Active
app/env and an active unexpired key are checked before rotation. Disabled scopes
retain metadata reads and revocation; cached ingestion checks may take the server
cache interval to reflect changes.

Create/rotate reveal plaintext once in component-local dialog state, via a direct
consumer callback. Metadata promises, Query/mutation state and variables never
contain the secret; the issuing mutation uses void variables/results. Close,
unmount, tab/app/env/session retirement clear local/callback references and cancel
pending work. Late results cannot reveal after retirement. Copy is explicit; closing
does not erase the system clipboard. JavaScript does not guarantee physical memory
zeroization. Lost issue/rotation replies may leave an unrecoverable secret: inspect
metadata, never automatically issue or rotate again.

Alert Rules uses global GET `/alert-rules` with cursor/limit only. UI filters loaded
rows by the selected app's actual environment UUIDs and optional local env selection.
Load More remains available across globally paged nonmatches. Empty loaded results
are distinguished from exhausted traversal, without a total-count endpoint.

POST/PATCH/DELETE `/alert-rules[/{id}]` create/edit/disable actual rules. The JSON count
field is `threshold`; all four numeric fields are positive int32, including cooldown.
Environment/level are immutable after creation; edits send only dirty mutable fields.
DELETE retains a disabled rule. Create/enable require active scope; numeric edits
or disabling use the supported backend behavior. Management does not start detection,
consumers or resolvers. Duplicate400 and denied/unavailable states remain safe errors.

Both features use generation/actor/role-scoped keys, pending command locks, no
optimistic success or write replay. Confirmed writes retire affected pages and
refetch the head. Uncertain writes use GET reconciliation without claiming commit;
failed checks block another operation. Existing shared refresh may be used for GET
after a write401, never to replay that write. Forbidden data is hidden/cleared.

F3.3 made no backend/proxy/env/dependency changes or live calls; Users follows in F3.4. Node
tests and source checks do not certify rendered focus/layout or deployed integration.


## F3.4 Users Management (offline)

Admin navigation opens #/users without mounting a workspace SSE stream. It lists
ENGINEER accounts with supported ACTIVE/DISABLED status filtering, cursor Load More,
loaded-only username search and summary counts. Avatar/UUID/username/role/status use
real DTO fields; no email, assignment totals, last-active or ADMIN editor is invented.

Create submits username/password only. Editing starts with a fresh exact user GET;
PATCH omits unchanged username and blank replacement password, preserving password
characters exactly. Separate confirmations soft-disable through DELETE or enable
with PATCH status ACTIVE. Account status does not alter application membership.

Passwords stay in short-lived form/request memory, never Query/mutation data or
variables. Inputs and command references clear after settlement, close and retirement.
Writes do not retry or optimistically update. Uncertain replies reconcile current
username/status through GET; that cannot prove commit or confirm a password change.
A failed check blocks another write. User directory/detail/list and embedded roster
identity snapshots are retired after confirmed writes; forbidden/session guards apply.

Applications, all five Settings tabs and shared observability routes are retained.
Browser visual/focus/mobile and live REST acceptance remain separately unverified;
this implementation does not start services, deploy schemas or mutate live records.


## Admin v1.0 presentation corrections (offline)

Admin toolbar cascade, shell subtitle/icons, status dots/environment indicators,
General card typography/span, dialog headers and mobile wrapping follow the frozen
reference. Open/Settings remain direct. Application Edit/Status use a native popover
with keyboard arrows/Home/End, Tab/Escape dismissal and trigger focus restoration;
opening it never issues a command. Existing dialogs/guards own all writes.

Keys/Rules retain primary fields and use native expandable details for technical
metadata; plaintext credentials never enter those details. Rule Enabled is a native
select explicitly mapped to boolean. Rotate confirmation has danger tone.

Confirmed Admin outcomes use a 2600ms success toast; uncertain/error/lost-response
notices and explicit reconciliation remain visible. Key-issued success appears in
the native secret dialog's top layer; copy feedback replaces it, and closing cannot
re-show the same notification. Secret lifecycle/password logic is unchanged.
Engineer toast defaults and existing workspace functionality remain unchanged.

Native popover/dialog focus, pointer/keyboard timing and rendered layouts at desktop,
1200/780/600/390px remain NOT VERIFIED without browser tooling. No live requests,
server startup or infrastructure action is part of this offline presentation batch.
