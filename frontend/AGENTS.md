# Frontend working instructions

Follow the repository-root [AGENTS.md](../AGENTS.md) and applicable `.agents/rules/`.

## Read before frontend work

- [Design reference](../docs/frontend/DESIGN.md): extracted values, screen inventory,
  demonstrated interactions, missing states and API mapping gates.
- [Engineer v1.0](../docs/frontend/prototypes/engineer-v1.0.html): approved visual
  and interaction baseline. Preserve it; do not redesign or edit the snapshot.
- [Admin v1.0](../docs/frontend/prototypes/admin-v1.0.html): current management
  reference built on the same base CSS; retain its documented role variants.
- [OpenAPI](../docs/contracts/openapi.yaml), [REST guide](../docs/contracts/rest-api.md)
  and [SSE contract](../docs/contracts/sse-api.md): authoritative API behavior.
- [Security](../docs/quality/security.md), [MVP scope](../docs/product/mvp-scope.md)
  and [backend integration handoff](../docs/operations/backend-mvp-integration.md).

## Implementation boundaries

- DESIGN/prototypes own the visual reference; backend contracts own fields,
  permissions, pagination and streaming. Resolve documented conflicts before wiring.
- Extract/reuse actual CSS values and existing frontend conventions. Do not invent
  tokens, add a component framework/dependency or globally apply Admin overrides.
- Mock authentication, metrics, local mutations, IDs, key generation and sample
  fields are demonstration code, not production implementation requirements.
- Follow the same-origin proxy/Bearer-capable SSE approach in the handoff. The
  backend enforces access; frontend role visibility is not an authorization boundary.
- Never log credentials/tokens or persist one-time API-key secrets. Treat server
  text as text; do not carry template-string innerHTML rendering into React.
- Plan loading, empty, failure, denied and accessible keyboard/focus behavior;
  unmodeled states in DESIGN are gaps, not permission to fabricate data or redesign.
- Preserve existing user work and historical evidence. Backend/schema/API changes,
  native tests, infrastructure and recovery actions require their own approval.
- F1 and F2.1–F2.4 Engineer workspace are approved for offline implementation.
  F3.1 Admin Applications create/edit/status is approved for offline implementation.
  F3.2 General/Environments/accurate assigned-Engineer roster is approved offline.
  F3.3 API Keys/Alert Rules is approved for offline implementation.
  F3.4 Users Management is approved for offline implementation.
  Real-backend smoke and backend/native/Telegram activation require separate approval; do not stage/commit.

## Approved F1 architecture

Follow Page -> feature hook -> domain API -> shared Axios client. See
[architecture reference](../frontend-private-architecture/SKILL.md) and its two
references for organization, adapted to actual VaniOAK contracts.

- Domain API modules own HTTP/envelope/runtime decoding; pages never call fetch/
  Axios or inspect AxiosResponse. Envelope is success/message/data, not CRM code.
- React Query owns application server data and page cache; keys include generation,
  actor/role, status/cursor/limit. Cancel and clear sensitive cache on session change.
- Zustand holds memory-only auth, no persist/devtools or backend application lists.
- RHF/Zod owns login form validation; no passwords/tokens in query/mutation data,
  variables, logs, raw errors or persistent storage. Auth mutations use void variables.
- Preserve separate auth transport, single-flight rotation, bounded GET replay,
  generation/revision checks and logout synchronization. No cookie restore or /me.
- Preserve existing visual CSS/hash routing behavior and frozen prototypes; F2.1
  adds only approved Logs routes/styles. Approved
  dependencies are Axios, TanStack Query, Zustand, RHF, Zod and hookform resolvers;
  TypeScript6.0.3 stays pinned. New additions/upgrades require their own approval.

## F2.1 Logs Explorer

- Workspace context owns only UI environment/preset/frozen UTC bounds. React Query
  owns logs; Zustand stays auth-only. API modules own DTO/envelope validation.
- Repeat exact filters/bounds across opaque cursor pages; reset the chain on scope
  or applied-filter changes. ALL environments omits environment_id.
- Log drawer uses the selected REST record, with safe React text/metadata rendering.
  No detail endpoint, fake totals/fields, simulated Live state or SSE in F2.1.

## F2.2 Realtime SSE

- One workspace-owned Fetch stream, explicit authorized app/env, Bearer header only;
  no EventSource, Axios buffering, level filter, query token or cookie auth.
- Reuse clients.refresh, session generation/revision and logout safeguards. Abort
  readers/timers on scope/session change; await retirement before replacement.
- Bounded parser and coalesced REST reconciliation; never insert partial SSE logs
  into Query. Live head resets cursor chain, Pause enables historical Load More.
- Retry 1/2/4/8/16s, then explicit Retry. Stable budget reset requires 30s open with
  a complete block. No raw event/credential logging, replay guarantee or fake health.
- Live smoke remains unapproved.

## F2.3 Incidents

- Domain API validates actual Incident list/detail/PATCH DTOs. GET audit comes from
  history metadata; PATCH audit remains a separate authoritative result. No fake
  actor names, history append, counts endpoint or server text search.
- List windows are separate frozen UI snapshots, not rewritten by log events.
  Incident events/reconnect refresh head and detail through the existing stream.
- Resolve requires current owned detail, OPEN status, appropriate role and explicit
  confirmation. Backend owns authorization, including disabled-scope permissions.
- Never replay PATCH. Uncertain outcome requires GET reconciliation before another
  confirmation; failed status checks block resubmission. Cancel/generation-guard
  late mutation results; cancellation does not prove server rollback.
- V3 deployment/live mutation readiness is unverified and separately approved;
  frontend work must not provision it or start backend/infrastructure/recovery.

## F2.4 Analytics / Overview

- Actual analytics endpoint only, bucket=hour, bounded UTC ranges; ERROR and CRITICAL
  are separate counts. Weighted rate is sum(ERROR+CRITICAL)/sum(total), never an
  average of returned ratios. No service-health/INFO/WARN/AI/previous-period fiction.
- API validates complete DTOs/scope/UTC buckets and rejects uncertain/malformed
  responses. Empty successful aggregates have rate N/A; unavailable is not zero.
- Query owns aggregate-derived metrics; Overview reuses existing domain hooks/keys,
  tables and bounded loaded samples, not a second data store or total-count API.
- Analytics window changes only on explicit selection/refresh or bounded SSE
  reconciliation. Reuse one workspace stream; no local event-based counter math.
- SVG charts reuse prototype styles; zero gaps derive from a successful aggregate
  response and partial boundary hours are labeled. Browser/live checks remain separate.

## F3.1 Admin Applications

- Admin-only presentation reuses authorized Application listing, shared workspace,
  domain Axios API and RHF/Zod. No owner, assignment counts or fabricated totals.
- Detail edits omit unchanged fields; status confirmation sends only status.
  Disabled apps must be explicitly reactivated before editing name/description.
- No optimistic writes or automatic POST/PATCH replay. Uncertain outcomes use fresh
  paged GET reconciliation; failed checks block further writes. Generation/phase
  guards prevent late results from restoring sensitive caches after logout.
- Confirmed writes retire filter/cursor/selection snapshots; list starts at the head.
  Admin styles/dialog variant are scoped; Engineer presentation stays intact.
- Settings links now open the approved F3.2 route. Users Management is implemented in F3.4.

## F3.2 Application Settings

- ADMIN-only route uses the existing paged Application lookup without fallback or
  an invented detail API. Management screens do not mount WorkspaceProvider/SSE.
- General reuses F3.1 forms/mutations; Environments is actual read-only metadata.
  F3.3 enables API Keys/Alert Rules; no environment mutations/counts.
- Assigned roster uses GET /applications/{id}/engineers, including disabled members
  and disabled applications. Directory reads only ENGINEER accounts from /users.
  Cursor exhaustion is required before claiming absence or complete roster count.
- Grant/remove use existing POST/DELETE with confirmation, single pending command,
  no optimistic membership or write replay. Lost outcomes require fresh GET roster
  reconciliation, not command-history inference; failed checks block more writes.
- Query keys isolate generation/actor/role/app and cursor. Retire old roster pages
  after writes, remount at the head and refetch. Late/denied data must stay hidden.
- Browser and deployed roster API integration remain separate verification steps.

## F3.3 API Keys / Alert Rules

- Keys require an actual environment UUID, never ALL. Metadata only in Query;
  create/rotate deliver plaintext directly into a one-time component-local dialog.
  Mutation variables/results are void. Never persist/log/cache a full credential.
  Clear view/callback references on close, tab/app/env/session retirement and unmount;
  reject late reveal. Explicit clipboard copy cannot be undone by closing the dialog.
- Rotation requires an active unexpired key and active app/env, keeps expiry and
  returns a replacement ID. Disabled scopes still permit metadata reads/revocation.
- Rule GET is global cursor pagination. Derive selected app/env rows from actual
  environment IDs; retain Load More across nonmatching pages. Loaded empty differs
  from exhausted empty. Actual wire field is threshold, not threshold_count.
- Create/enable rules require active scope. PATCH only dirty mutable numeric fields
  and enabled; DELETE disables, never permanently removes. No runtime activation.
- No optimistic writes or automatic replay. Uncertain responses require supported
  GET reconciliation; failed checks block more writes. A matching state is not proof
  of a particular write. Lost issued secrets cannot be recovered from metadata.
- Preserve F1 session safeguards, F2/SSE/Resolve behavior and Engineer presentation.
  Browser/live verification remains separately approved.


## F3.4 Users Management

- ADMIN-only /users routing guards management fetching. Actual ENGINEER directory,
  status/cursor filters and loaded-only search/counts; no ADMIN account management.
- Fresh exact user GET before editing; PATCH only changed username/nonempty optional
  password. Blank edit password is omitted; never trim passwords. Disable is DELETE
  soft-disable; enable is status-only PATCH, separately confirmed.
- Passwords stay in transient form/request memory. Void mutation variables/results;
  clear form/command references on settle/retirement. No storage/logging/raw errors.
- No optimistic writes or write replay. Uncertain replies require GET reconciliation;
  GET cannot confirm a password update or prove a specific write committed. Failed
  checks block further changes, and shared renewal never replays a write.
- Retire session User pages/detail/directory and roster snapshots after writes;
  assignments remain intact. Preserve Engineer/F2, Keys/Rules and one-stream lifecycle.


## Approved Admin visual parity corrections

- Presentation follows the final frozen Admin CSS cascade with role-scoped selectors.
  Existing data/auth/query/mutation logic and Engineer appearance remain unchanged.
- Applications keeps Open/Settings direct; native popover holds existing Edit/Status
  confirmations. Keyboard navigation/dismissal returns to the trigger; no write on open.
- Native details exposes only non-secret metadata. Keys keep status/expiry visible.
  Enabled select maps explicitly to boolean without changing the rule schema.
- Confirmed Admin success uses a 2600ms success toast. Uncertain/error/reconciliation
  notices remain persistent. Engineer default remains 2700ms. Never toast credentials.
- Browser/native-popover/pixel and live acceptance require separate verification.
