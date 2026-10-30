# VaniOAK frontend design reference

Prepared 2026-10-29; documentation only. React F1 requires separate approval.

## Authority and source references

- [Engineer v1.0](prototypes/engineer-v1.0.html) is the approved visual/interaction baseline.
- [Admin v1.0](prototypes/admin-v1.0.html) is the current management design reference.
- Copies preserve root `engineer_v1.html` and `admin_v1.html` byte-for-byte; do not edit snapshots.
- Design references govern appearance, layout and demonstrated interactions, not API permissions or data.
- [OpenAPI](../contracts/openapi.yaml), [REST](../contracts/rest-api.md),
  [SSE](../contracts/sse-api.md) and [security](../quality/security.md) govern real behavior.
- Follow the existing [backend handoff](../operations/backend-mvp-integration.md) for proxy/auth integration.
- Mock fields, hard-coded metrics, credentials, hash URLs and local mutations are not implementation contracts.
- Conflicts below require an F1 mapping/scope decision; they do not authorize backend changes or redesign.

| Evidence in frozen HTML | Engineer lines | Admin lines |
|---|---|---|
| Root variables and shared CSS | 9–80 | 10–81; identical base CSS |
| Admin overrides/components/breakpoints | — | 84–153 |
| Login, entry and navigation | 187–196, 220–223 | 316–334, 364–366 |
| Workspace screens, inspector | 199–218 | 263–282 |
| Confirmation/forms and handlers | 219, 224–250 | 340–363, 368–418 |

Line numbers refer to the copied files; function/class names below are stable lookup anchors.

## Design principles

- Dark, dense observability workspace: quiet surfaces, thin borders, strong data hierarchy.
- Green emphasizes primary action, selection and healthy/resolved state; red marks open/danger.
- Text labels accompany severity/status colors; IDs, timestamps and payloads use monospace.
- Application context stays visible; drill from overview/list to detail without losing the workspace shell.
- Reuse the shared visual language for both roles; management affordances remain Admin-specific.
- Preserve observed values and role variants; no invented spacing scale, palette or typography reset.

## Design tokens — literal shared `:root` values

| Variable | Value |
|---|---|
| `--bg` | `#0d1015` |
| `--surface` | `#12161c` |
| `--surface2` | `#171c23` |
| `--surface3` | `#1d232b` |
| `--panel` | `#131820` |
| `--stroke` | `#29313b` |
| `--stroke2` | `#323a46` |
| `--text` | `#e9edf2` |
| `--muted` | `#939da9` |
| `--faint` | `#697482` |
| `--green` / `--green-dark` | `#36d6ad` / `#183a33` |
| `--red` / `--red-dark` | `#f57983` / `#3b2329` |
| `--orange` / `--blue` | `#ffb45f` / `#76a9ff` |
| `--shadow` | `0 20px 60px #0008` |
| `--radius` | `10px` |

These variables do not replace the many component literals; use the source selector for each variant.

| Observed role/selector | Exact value |
|---|---|
| Body font | `Inter,ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif`; `13px` |
| `.mono` | `ui-monospace,"SFMono-Regular",Consolas,monospace`; `11px`; tracking `-.2px` |
| `h1` / `h2` / `h3` | `27px/720`, `16px/690`, `13px/650` (size/weight) |
| `h1` / `h2` tracking | `-.8px` / `-.25px` |
| `.kpi-val` | `30px/725`, tracking `-1.1px`, line-height `1` |
| Login artwork / form heading | `49px`, line-height `1.1` / `27px` |
| `.btn` | radius `7px`; padding `9px 13px`; gap `8px`; min-height `35px`; weight `620` |
| `.btn-sm` | min-height `30px`; padding `6px 10px`; font `11px` |
| Primary button / hover | fill+border `#2ebd98` / `#58dcb7`; text `#06251c` |
| Danger button / hover | fill `#57292f` / `#70343c`; border `#87434c`; text `#ffc1c6` |
| `.field` / focus | height `36px`; radius `7px`; border `#363e48`; focus `#40b99c` + `0 0 0 3px #3ed5af12` |
| `.level.INFO/WARN/ERROR/CRITICAL` | `#6ddec0` / `#ffc078` / `#ff8b95` / `#ff616e` |
| Green tag (fill/border/text) | `#163b31` / `#236f57` / `#68e1b6` |
| Red tag (fill/border/text) | `#40242c` / `#6a313d` / `#ff939c` |
| Table `th` / `td` | `10px/720`, padding `13px 17px` / `12px`, padding `14px 17px` |
| Inline SVG icon | `17px` square; stroke `1.8`; no fill; round cap/join |

Inter is named but not bundled/downloaded by either HTML; fallback rendering is expected.
Weights such as 720/725 are literal source values, not a new standard weight scale.
Typical observed gaps are 9/10/12/14/15/18/20px; they are selector-specific, not universal tokens.
Panels use radius10px, KPI9px, tags5px and dialogs12px; preserve these differences.
Brand is the inline activity SVG/gradient mark plus text, not a reference to a root image asset.

## Layout and responsive rules

- Shared shell: `.app-shell` grid `244px minmax(0,1fr)`; sticky sidebar `100vh`, z-index11.
- Sidebar brand and workspace topbar are68px high; topbar padding `0 30px`, gap18px.
- Main: padding `32px 38px 64px`, max-width1660px, centered; workspace min-width0.
- KPI grid:4 equal columns, gap14px; overview split `1.68fr/.95fr` with290px minimum right column, gap18px.
- Detail grid: `1.55fr/.9fr`, right minimum320px; detail label/value columns160px/remaining.
- Engineer entry: application cards3 columns/gap15px; container max1110px, padding `60px 28px`.
- Login: `1.05fr/.95fr` artwork/form; form max400px, inputs43px, submit44px high.
- Tables scroll horizontally; cells stay nowrap; message cell min300px/max500px with ellipsis.
- Drawer: `min(540px,100vw)`, height100vh; Engineer modal `min(445px,calc(100vw - 24px))`.
- Overlay z-index60; toast z-index90, right/bottom22px; scrim `#020406b9`, blur2px.

| Inclusive max-width | Observed changes |
|---|---|
|1250px (shared base)|Main `27px 25px 55px`; KPI2 columns; split1 column; app cards2 columns.|
|780px (shared base)|Sidebar63px/icons only; main `23px 16px 40px`; topbar `0 16px`; detail/login1 column, artwork hidden; entry padding shrinks.|
|570px (shared base)|KPI stays2 columns, gap9px/padding15px/value24px; cards1 column; h1=23px; actions/footer wrap.|
|1200px (Admin extra)|Admin summary grid2 columns; settings details1 column.|
|780px (Admin extra)|Main `24px 16px`; topbar `0 14px`; settings padding16px; Admin badge hidden; picker labels hidden.|
|600px (Admin extra)|Admin summary stays2 columns/gap9px; forms1 column; settings padding12px; toolbar-right full width.|

Admin overrides win the cascade: its main top padding stays30px above780px, including below1250px.
The narrow-screen `.select-wrap:first-child` and Admin `:nth-child(2)` rules match actual DOM siblings;
do not infer that they reliably hide the environment selector (badges precede it).
No separate mobile drawer navigation is designed. Settings tabs scroll horizontally.

## Shared components and role variants

| Component | Source selectors / behavior |
|---|---|
| Workspace shell | `.sidebar`, `.nav-item`, `.topbar`, `.breadcrumb`; active green fill and3px left rail.|
| Header/context controls | `.page-heading`, `.select-wrap`; app context, environment and time selectors.|
| Actions/fields | `.btn` primary/danger/ghost/small/wide; disabled opacity `.44`, not-allowed cursor; `.field` search/select.|
| Containers/metrics | `.panel`, `.panel-head`, `.kpi-grid`, `.kpi`; thin borders, contained overflow.|
| Data table | `.table-scroll`, `.click-row`, `.mono`, `.table-msg`, `.table-footer`; row hover opens inspector/detail.|
| State indicators | `.tag`, `.level`, `.dot`, `.pulse`; OPEN red, RESOLVED green; live/paused label.|
| Filters/tabs | `.toolbar`, `.search-wrap`, `.tabs`, `.tab.active`; Admin adds `.segmented` and `.settings-tabs`.|
| Investigation | `.drawer` metadata/code panel/copy trace; `.detail-grid`, `.timeline-item` history.|
| Feedback | `.empty-state`, `.toast`, confirmation modal; Admin adds `.hint-panel` warning/one-time-secret dialog.|

Admin adds `.data-table` cell padding `15px 17px`, `.summary-card` radius9px/padding `18px 19px`,
`.settings-surface` padding24px, `.form-grid`2 columns/gap15px and `.secret-box`.
Its `.modal-admin` is `min(530px,calc(100vw - 28px))`, max-height `min(90vh,820px)`, scrollable, padding27px.
Admin nav padding is `9px 12px` (Engineer `11px 12px`); desktop main top padding30px.
Admin also overrides unscoped `.toolbar`, `.modal-actions` and `.table-footer`; keep these as role variants
when implementing shared components so Admin CSS cannot silently alter the approved Engineer screen.

## Screen inventory and navigation

These are prototype hash paths, not React routing or backend endpoint requirements.
Common workspace prefix is `#/applications/:appId`.

| Role | Screen/path | Actual content/actions |
|---|---|---|
|Both|`#/login`|Role-specific copy/artwork; prefilled demo credentials; local sign-in.|
|Engineer|`#/applications`|My applications: assigned-only mock cards, name search, select, sign out.|
|Admin|`#/applications`|Applications table, active/disabled filter, search, create/open/settings.|
|Admin|`#/users`|Users table, role filter/search, create/edit/status dialogs.|
|Both|`/overview`|KPI cards, sample activity chart, recent incidents, latest logs, simulate action.|
|Both|`/logs`|Search/level filters, reset,12-row local pages, live toggle, row inspector.|
|Both|`/incidents`|KPI cards, ALL/OPEN/RESOLVED tabs, title search, severity, detail navigation.|
|Both|`/incidents/:id`|Information, resolution audit, reverse history, related logs, resolve confirmation.|
|Both|`/analytics`|Sample hourly stacked chart, severity distribution and environment breakdown.|
|Admin|`/settings`|General, Environments, Engineers, API Keys, Alert Rules tabs (local tab state).|

Admin settings: app details/status; fixed DEV/TEST/STAGING; engineer assign/remove;
key create/rotate/revoke/reveal once; ERROR/CRITICAL rule create/edit/enable/disable.
Engineer has no management navigation. Admin keeps Management and Application workspace groups visible.
Both select app into DEV; Engineer additionally clears log query. Default time24h/live=true.
Environment options: DEV/TEST/STAGING/ALL; time options:1h/24h/7d; detail keeps Incidents active.
Prototype auth=false redirects to login; sign-in goes to applications. Invalid paths return there;
unknown app IDs silently fall back to first mock app. This fallback must not imply real authorization.

## Interaction conventions — observed

- Search updates immediately and restores caret/focus after rerender; no debounce value is designed.
- Changing environment/time/log level resets log page; reset clears log query/level/page only.
- Click log row opens right drawer; click incident opens detail; related logs seeds first3 title words.
- Scrim/close button/Escape dismiss overlays; Engineer Escape dismisses modal before drawer.
- Resolve is available only for OPEN, with Cancel/Confirm; mock confirms append MANUAL history locally.
- Copy trace/key uses Clipboard API and feedback; failures have a fallback toast.
- Engineer toast lasts2700ms; Admin2600ms. Preserve as observed variants until explicitly standardized.
- Button transition `.12s`; app-card hover `.15s`/translateY(-2px); drawer entrance `.2s`/translateX(25px).
- Live pulse repeats2s. Live toggle changes local presentation; it does not implement a stream or pause contract.
- Admin destructive lifecycle/key/access actions use confirmation; rule enable/disable is immediate local toggle.
- Key reveal is temporary dialog state, cleared by close/logout; never treat generated demo keys as real credentials.
- All demo data/actions are in memory and reset on reload; neither prototype performs backend requests.

## States and implementation boundaries

| State | Observed design / remaining requirement |
|---|---|
|Empty|Centered inbox icon/title/help for no applications/logs/incidents/users/keys/rules/assignable engineers.|
|Disabled|Button opacity/cursor; disabled entity tags and confirmation dialogs, not deletion of historical views.|
|Not found|Incident heading plus Back to incidents button.|
|Validation/failure|HTML required/type/min checks; Admin duplicate-name/email/rule and clipboard failures use toast.|
|Loading/submitting|No asynchronous loading/skeleton/pending-request state is implemented; F1 must handle it without inventing metrics or double-submit success.|
|API error/unavailable|No real network error/retry view is designed; choose within existing visual primitives during F1 review.|
|401/403|Only local unauthenticated redirect is modeled; expired session and authorized-role/foreign-scope denial need contract-based handling, not empty mock data or fallback app access.|

No focus trap/return-focus, complete keyboard row/tab navigation, screen-reader live feedback or reduced-motion
alternative is implemented. Existing dialog roles/labels are a starting point, not accessibility verification.
Loading/error/unauthorized styling and narrow-screen filter reachability are F1 decisions, not new approved designs here.

## Inconsistencies and API mapping gates

1. Both login forms say Email/type=email; backend login accepts username. Do not enforce email-only identities.
2. Admin user editor creates/edits ADMIN, full name/email and role without password; actual user REST manages
   ENGINEER username/password/status only. Admin account management/last-active are not promised APIs.
3. Admin app Owner selector and synthetic ID are mock fields; application write contracts do not accept owner.
4. Admin environment toggles have no independent environment REST operation in the current contract.
   Assignment modal also offers disabled engineers and says enabled environments only; follow security policy instead.
5. Rule form accepts cooldown0 while contract minimum is1; edit also exposes immutable environment/level.
6. Search mock matches message/trace/host together; real log filters are distinct and have no host search parameter.
   Incident title search is local-only; its list contract has no text-search parameter. Plan these mappings before wiring.
7. Local numbered12-row pagination/total counts must not be assumed to exist in cursor APIs.
8. Log service/span/request fields and raw payload keys are illustrative; map optional metadata honestly.
   Incident GET has no top-level source/actor; use documented resolution response/history metadata, including legacy nulls.
9. Both dashboards/charts use fixed metrics, percentages and a hard-coded healthy badge; hourly API fields do not
   directly provide WARN/INFO distribution or service-health verdict. Do not present sample values as live facts.
10. Incident lists ignore global time range in both prototypes; analytics stays24 sample hours despite time selector.
    Admin reuses “assigned application” incident subtitle despite organization-wide role; copy needs F1 reconciliation.
11. Shared severity text colors differ from chart literals (CRITICAL distribution purple); toast timing and Admin
    CSS overrides/breakpoints differ as recorded above. No palette/timing redesign is authorized by this extraction.

Use real Bearer-capable SSE and REST reconciliation through the linked handoff; hash navigation and local
demo authentication are not security. Preserve backend authorization, secret handling and existing wire contracts.
Telegram, physical TTL verification, backend/native recovery and any React implementation remain outside this task.
