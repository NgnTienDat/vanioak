# Engineer v1.0 HTML ↔ React — báo cáo đối chiếu chi tiết

Ngày: 2026-10-30. **REVIEW ONLY — chưa chỉnh UI.**

## Kết luận và giới hạn

React hiện giữ design tokens và nhiều CSS gốc, nhưng chưa phải bản chuyển giao diện
1:1 từ `engineer_v1.html`: khác cả cấu trúc component, mật độ nội dung, icon, định dạng
thời gian, card/list/chart và một số tương tác. Offline functional PASS trước đây
không đồng nghĩa visual parity PASS. Báo cáo này không mở lại vấn đề kết nối SSE
người dùng đã xử lý.

Đối chiếu source HTML/CSS/JS với TSX/CSS thực tế, không dựa riêng báo cáo cũ. Không
có browser tooling trong session; chưa có screenshots/computed styles/rendered
measurements. Các khác biệt DOM/values/logic dưới đây được xác nhận từ source;
mức độ ảnh hưởng pixel, overflow và keyboard execution vẫn cần browser checkpoint.
Không cài tooling, gọi backend, khởi chạy server hoặc tạo mock auth/data trong app.

`engineer_v1.html` và frozen `docs/frontend/prototypes/engineer-v1.0.html` hiện cùng
SHA256 `ef80f88b49fa3cc832c2e9de3124ad177aae936f8e173332ee2e9444b3a65e66`.
Đây là review working record để duyệt corrections, không thay thế DESIGN/API contracts.

## Cách đọc

- **V — Visual:** có thể đưa gần mẫu mà không thay API, auth hoặc dữ liệu thật.
- **C — Contract:** khác mẫu là cần thiết cho backend thật; không khôi phục demo logic.
- **Q — Quyết định:** có nhiều cách trình bày hợp lệ; cần chọn trước khi sửa.
- Ưu tiên **cao** = đổi diện mạo/bố cục chính; **vừa** = chi tiết component; **thấp** = copy/decor.
  Đây là mức ưu tiên parity, không phải kết luận lỗi bảo mật/functional severity.

## Các phần đang khớp — không nên redesign

Static CSS parsing bằng PostCSS đã cài sẵn xác nhận literal 18 `:root` tokens khớp
toàn bộ: palette, shadow, radius. Font stack Inter/system, base13px; shell244px và
mobile63px; topbar68px; main32px38px64px/max1660px và các main breakpoints khớp.
App card padding22px/min-height202px/gap13px/radius10px, chart height213px, detail
grid1.55fr/.9fr và basic table/button styles đều có source tương ứng mẫu.

Các correction đã duyệt trước vẫn có: mobile nav aria-label, corrected punctuation,
Incident initial Loading/empty/error distinction, final mobile KPI gap9px. Không
ghi lại chúng như findings chưa sửa. Account name/role hiện cũng đã display:block.

## 1. Shared shell, navigation, icons

Reference: `engineer_v1.html:182,186,194–197`; React:
`frontend/src/components/layout/WorkspaceLayout.tsx:17–29`,
`frontend/src/components/ui/Icon.tsx:1–24`, `StatusBadge.tsx:1–4`.

| ID / mức | HTML mẫu | React hiện tại | Hướng chỉnh nhỏ nhất sau duyệt |
|---|---|---|---|
|S01 · V vừa|`drawBrand()` thống nhất brandword/MVP; activity icon19px.|Workspace có MVP, Login/My Applications không có; activity mặc định17px. Shape/path activity và brandmark background vẫn giống, không phải logo mới.|Dùng đúng markup/size theo context mẫu; không đổi palette/logo asset.|
|S02 · V vừa|App-switch có name và dòng phụ “Application workspace”, wrapper2 dòng và chevron14px.|Chỉ còn app name trên1 dòng; chevron17px; không có subtitle.|Khôi phục wrapper/subtitle, đúng chevron size; giữ link chọn app và authorization thật.|
|S03 · V vừa|“All applications” nằm trong nhóm riêng có heading “Workspace”.|Nằm nối tiếp Analytics trong cùng Observability nav; thiếu divider/section spacing tương ứng.|Tách nhóm markup theo mẫu, giữ links/aria-label.|
|S04 · V vừa|Breadcrumb trên Incident Detail ghi “Incident Detail”.|Workspace nhận page=incidents nên breadcrumb vẫn “Incidents”.|Cho breadcrumb biết detail context, không thêm route/API mới.|
|S05 · V vừa|Icon có size theo vị trí; KPI title có icon; status OPEN/RESOLVED có alert/circleCheck12px.|Icon component cố định17px; KPI title không icon; StatusBadge chỉ text. Calendar của time picker đổi sang clock.|Trích đúng những SVG/size đang dùng trong mẫu: circleCheck/bolt/calendar/copy/refresh/left/right/check; không thêm icon framework hoặc cả bộ unused icons.|

Tên user/avatar initials thật thay Alex Morgan/AM và bỏ DEMO badge là **C**, phải giữ.
Footnote “Management follows in F3” là copy triển khai, không phải copy Engineer mẫu;
có thể thay bằng mô tả sản phẩm ngắn, không khôi phục thông báo simulated data.

## 2. Login

Reference `engineer_v1.html:188`; React `features/auth/LoginPage.tsx:17–25`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|L01 · V vừa + C|Artwork terminal có header flex: icon14px + “live application signals” bên trái, connected badge bên phải, margin-bottom14px; sau đó5 minibar.|Một dòng “Application observability”, icon17px; thiếu header flex/spacing; typography/color header kế thừa khác.|Khôi phục bố cục/header decorative theo mẫu, dùng neutral illustration label; KHÔNG hiển thị connected như trạng thái backend thật trước login.|
|L02 · V thấp + C|Có footer căn giữa “Engineer experience · Version1.0”; form hint có title mạnh và body riêng.|Không footer; hint một khối text +shield; artwork/footer/subtitle wording khác.|Giữ Username và thông tin session thật; có thể khôi phục title/body/footer geometry bằng copy không-demo.|

Không phục hồi email-only validation, password prefill hoặc demo credentials. Những
thay đổi này là đúng API/security, không phải lỗi fidelity.

## 3. My Applications

Reference `engineer_v1.html:192`; React `features/applications/ApplicationsPage.tsx:20–28`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|A01 · V vừa + C|Header có full drawBrand, avatar và Sign out; card icon21px, footer server13px và arrow14px.|Header chỉ brand/Sign out, thiếu avatar; card icon17px, footer thiếu server icon; arrow17px.|Khôi phục avatar từ actual username và icon sizes/decoration; không dùng AM cố định.|
|A02 · V vừa + C|Intro subtitle2 dòng; search flex0 0 260px; sau cards là access note.|Subtitle1 câu; search flex0 1 260px; thêm table-footer count/load-more giữa cards và note, tăng chiều cao trang.|Khôi phục nhịp2 dòng/search geometry; vẫn phải giữ loaded-only search/cursor affordance, có thể trình bày nhẹ hơn.|

Mock category badge (Backend/Worker...) và per-app icon type không có trong DTO.
React ACTIVE/DISABLED badge là **C** hợp lệ; không tạo application.type/owner để giống
mẫu. Generic icon có thể thống nhất theo thiết kế, không giả category. Empty/loading/
403/cursor là states thật bổ sung, không được xóa để chụp giống demo.

## 4. Overview — khác cấu trúc đáng kể

Reference `engineer_v1.html:197–203`; React `features/overview/OverviewPage.tsx:25–45`,
`features/analytics/AnalyticsPage.tsx:11–16`, `components/charts/HourlyActivityChart.tsx`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|O01 · V cao + C|Header có View logs; phía dưới là gradient intro-banner với title/copy, action Simulate.|Header LiveStatus+Refresh; intro-banner bị bỏ hoàn toàn, thay technical note.|Khôi phục View logs và banner bố cục/copy hữu ích; bỏ Simulate/service-health giả vẫn đúng. Vị trí Live/Refresh cần duyệt để không lấn header.|
|O02 · Q cao|Card4 là Open incidents; KPI title icon và value có formatted thousands, accent; subtext ngắn.|Dùng nguyên AnalyticsKpis nên card4 là Error rate, numbers raw; không icon/accent; lặp giải thích dài dưới cards.|Nếu chọn parity: card4 Open incidents **loaded sample** hoặc unavailable, không global total giả; giữ weighted rate ở Analytics. Formatting/icon không cần API mới.|
|O03 · V/Q cao|Một total-volume line có gradient area và endpoint dot;5 time labels,4 grid levels.|3 polylines Total/ERROR/CRITICAL, không gradient/dot;3 time labels và dynamic ticks; thêm explanatory text +details table.|Overview variant có thể vẽ total area từ data thật, giữ accessible values; dynamic axis theo window vẫn cần. Không dùng mảng sample/fixed24h.|
|O04 · V cao|Recent incidents là compact list4 items: alert tile, truncated title, status/severity/relative time/count và right chevron.|Reuse nguyên IncidentTable6 cột với fingerprint/full UTC time trong cột phải split-grid.|Dùng compact view từ cùng query/sample, không API/store mới. Đây là đổi component layout lớn nhất của Overview; table trong panel hẹp khác hẳn reference.|

Latest logs mẫu không có action column (`withActions=false`); React dùng cùng6-column
LogTable có Inspect button. Có thể giữ keyboard inspect bằng compact accessible action,
không cần làm panel Overview giống full Explorer. Không phục hồi health badge/deltas
hoặc simulated action. Sample6 logs/4 Incidents hiện đúng số lượng mẫu.

## 5. Logs Explorer và Log Detail Drawer

Reference `engineer_v1.html:203–205,217`; React `features/logs/LogsPage.tsx:38–54`,
`LogTable.tsx:7–15`, `LogDetailDrawer.tsx:10–29`, `components/ui/Dialog.tsx:25–31`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|G01 · C/Q vừa|Một search input có icon, level select, Reset filters; search áp dụng ngay.|Hai labeled inputs Message/Trace, Level, Apply/Reset; toolbar nhiều control và cao hơn.|Giữ supported filters và explicit Apply đã duyệt. Có thể nén spacing/layout nhưng không gộp thành giả host-search hoặc bỏ validation.|
|G02 · V vừa + C|Sau toolbar có status strip: dot, stream active/paused, matching-count và inspect hint.|Không status strip; thay bằng UTC-bound paragraph; Live state ở heading và thêm note Pause để browse history.|Khôi phục status-strip geometry với real status +loaded count, không “N matching total” hoặc simulated stream.|
|G03 · V cao + C|Time compact `30 Oct, 06:06`, cột cuối chevron, Overview không action column.|Time là full ISO có milliseconds/Z, action Inspect button; table rộng/dense khác. Service đổi Environment.|Format UTC compact +full value qua time/title/drawer; accessible icon action. Service→Environment giữ vì DTO không có guaranteed service.|
|G04 · V vừa + C|Drawer có relative age cạnh level/env, compact fields, copy icon; close btn-sm30px.|Không relative age; thêm nhiều UUID/received/processed rows; close dùng btn35px; copy không icon; JSON caption thêm REST record.|Khôi phục age/close size/copy icon. Thống nhất nhóm primary fields và technical IDs; giữ actual metadata/JSON, không bịa Span/Request/Service.|
|G05 · Q tương tác|Prototype giữ drawerId qua simulated render; chỉ navigation/close đổi nó.|Selection gắn identity gồm scope/window/reconciliation; khi SSE reconcile đổi range, drawer không còn visible.|Quyết định rõ: giữ behavior đã duyệt đóng theo snapshot, hay giữ frozen selected REST record khi Live update. Không sửa ngầm cache/session guards chỉ để giữ drawer; cần regression riêng nếu đổi.|

G05 trace: `LogsPage.tsx:32–35` → `WorkspaceContext.tsx:21` reconcileLogs →
`realtimeCache.ts` coalesced flush. Đây là conditional behavior source-confirmed
khi reconciliation xảy ra, chưa phải browser-reproduced bug.

Cursor/Load more thay Previous/Next +total là **C** bắt buộc; không quay về local
12-row pagination. REST no replay/stream statuses/async failures phải được giữ.

## 6. Incident List

Reference `engineer_v1.html:208`; React `features/incidents/IncidentsPage.tsx:23–42`,
`IncidentTable.tsx:6–11`, `StatusBadge.tsx`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|I01 · V vừa + C|“Open incidents/Resolved incidents/Error incidents/Critical incidents”, icons, Open value đỏ, subtext riêng.|“Open/Resolved/Error/Critical (loaded)”, không icons/accent; subtext giống nhau và dài.|Khôi phục hierarchy/icon/color/names; retain loaded-only qualifier và correct Loading/Unavailable, không full total giả.|
|I02 · V vừa + C|Tabs “All incidents / Open / Resolved”; search icon trong compact input, severity; không Reset riêng.|Tabs raw “ALL / OPEN / RESOLVED”; visible “Search loaded messages”, không search icon; thêm Reset.|Display labels có thể giống mẫu, wire enum không đổi. Loaded-only label vẫn rõ; có thể dùng helper text thay làm toolbar nặng.|
|I03 · V cao|Whole row clickable/hover, title ellipsis, fingerprint cách5px, relative last seen, last chevron.|Chỉ title anchor clickable; title wrap `.incident-link`, fingerprint sát hơn; full ISO last seen, không last column.|Khôi phục truncation/spacing/compact time/chevron và row interaction với keyboard link vẫn hoạt động; không bỏ accessible action.|

Engineer access badge ở header mẫu được thay bằng LiveStatus+Refresh. Đây là choice
vị trí controls, không yêu cầu API; cần thống nhất cùng O01/N01. Status/time filters
và cursor thật không được bỏ để khớp mock behavior bỏ qua global time range.

## 7. Incident Detail và Resolve Dialog

Reference `engineer_v1.html:214,219`; React `features/incidents/IncidentDetailPage.tsx:21–58`,
`ResolveIncidentDialog.tsx:7–17`, `components/ui/Dialog.tsx:25–31`, `styles/global.css`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|D01 · V vừa|Back có left icon; badge row margin-top14px/wrap, severity orange/red tag, fingerprint ở header; resolve có check icon.|Back text-only; dùng `.log-badges` margin-bottom17px/no wrap, severity chỉ colored text, fingerprint chỉ ở information rows; resolve text-only.|Dùng detail-specific badge geometry, actual fingerprint và icons; giữ permission/pending/uncertain guards.|
|D02 · V cao|First/Last seen KPI value compact formatted, dùng kpi-val30px và descriptive subtext/icon.|Full ISO và `.incident-time`12px để fit; tất cả detail KPI thiếu subtext/icon.|Format real UTC thành compact value và restore30px hierarchy; full instant ở detail/tooltip. Không đổi timestamps trong dữ liệu/query.|
|D03 · V vừa|Information title có subtitle; value chủ yếu proportional12px, fingerprint monospace; related-log heading/button có spacing/icons.|Title thiếu subtitle; mọi value áp dụng monospace (kể cả app/env), còn font-size theo later detail-val12px rule; related-log explanation dài, buttons thiếu icons.|Khôi phục typographic roles/subtitle/margins; UUID actor vẫn phải UUID, null audit vẫn honest.|
|D04 · V vừa + C|Timeline dùng “Incident opened / Occurrence count updated / Incident resolved”, formatted time+relative age, prose note; permissions badge/footer riêng.|Raw enum OPENED/COUNT_UPDATED/RESOLVED và ISO timestamp; Count/Source/Actor ID technical lines; footer paragraph thay permission badge.|Map known event display labels, format time, giữ actual count/source/UUID và newest100 notice; không fabricate narrative/name/history.|
|D05 · V vừa + C|Modal Manual resolution tag+icon ở trên heading, margin-bottom20px; confirm có check icon.|Shared Dialog heading+close ở trên tag; thiếu tag/button icons và spacing tương ứng; thêm pending/error/reconciliation UI.|Restore visual order/icons; retain native dialog semantics, close accessibility, pending lock, no PATCH replay, outcome uncertainty và backend-authenticated actor.|

Legacy audit Unknown/Not recorded thay demo name/source và API-backed transactional
behavior là **C** cần giữ. Detail events newest100, after-commit SSE invalidation
không giống local mock append; không được đổi thành optimistic fake history.

## 8. Analytics

Reference `engineer_v1.html:210–211`; React `features/analytics/AnalyticsPage.tsx:29–44`,
`components/charts/HourlyActivityChart.tsx:11–23`, `EnvironmentBreakdown.tsx:10–20`.

| ID / mức | HTML mẫu | React hiện tại | Đề nghị |
|---|---|---|---|
|N01 · Q cao|Hourly **stacked bars**, INFO/WARN/ERROR legend; 24 sample buckets.|Cùng line-chart component như Overview,3 Total/ERROR/CRITICAL series; không bar stacks.|Có thể khôi phục bar visual với actual ERROR/CRITICAL/**Other** (`total-error-critical`), hoặc total bars. Phải duyệt legend; không bịa INFO/WARN riêng. Hour count/scale theo actual window, không fixed24.|
|N02 · Q cao|Dưới chart là split-grid2 panels: level distribution và environment breakdown.|Bỏ distribution panel; Environment panel full width. Bố cục trang khác hẳn mẫu.|Chọn giữ current supported-only layout, hoặc2 panels với distribution ERROR/CRITICAL/Other từ data thật. Không backend change, nhưng cần duyệt meaning của Other.|
|N03 · V vừa + C|Environment row gọn: env, track, percentage.|Thêm log count+percentage và một full-width ERROR/CRITICAL/rate line mỗi env; rows cao hơn.|Giữ compact primary row; detailed counts có thể tách thành secondary view nếu duyệt. Real returned envs/null/empty không thay bằng DEV78%/TEST16%/STAGING6%.|

KPI icons/number formatting của Analytics chịu S05; rate math hiện đúng weighted
sum, không average hourly ratios. Header “Hourly aggregates” badge đổi Live/Refresh;
đưa về sample “Add demo event” không hợp lệ.

## 9. Feedback, copy và responsive — xuyên màn hình

| ID / mức | Khác biệt | Đề nghị |
|---|---|---|
|F01 · V/Q cao|Nhiều đoạn “REST snapshots”, “not a total”, raw ISO windows, “PATCH response authoritative”, “backend enforces…”, “not exact fingerprint/receipt join” nằm ngay normal UI; mẫu có subtitle/hints ngắn. Layout dài và developer-oriented.|Giữ semantic warnings, nhưng rút gọn user-facing text; dùng một loaded/as-of label rõ, giữ detail/error/uncertain notice đúng lúc. Không bỏ safety information hoặc ngụ ý total/health/replay.|
|F02 · V vừa|Prototype Copy trace báo toast fixed bottom-right2700ms kèm icon; React đặt persistent status paragraph dưới button, không toast.|Khôi phục feedback appearance/duration bằng UI nhỏ có aria-live; giữ clipboard failure fallback và cleanup. Không dependency/toast framework.|

Responsive source differences: React cố ý giữ env/time selectors hiện trên mobile;
topbar wrap override chỉ dành workspace `:has(.logs-explorer)`. Prototype hiding rule
không đáng tin theo DOM siblings; không nên blind-copy. Cần browser kiểm trên cả
Overview/Analytics/Incidents/Detail ở1250/780/570/390px: wrap, clipping, long app names,
wide ISO/UUID và modal/drawer focus. Chưa có rendered evidence nên không gán overflow
cụ thể là confirmed. Default focus/aria/reduced-motion improvements phải giữ.

## Những khác biệt không đề nghị khôi phục

- Demo auth, email-only login, prefilled password, default foreign app fallback.
- Fake health verdict, previous-period deltas, simulation buttons, fixed metrics.
- Mock service/span/request/type/actor-name fields mà API không cung cấp.
- Numbered pagination/total counts và unrestricted search trái cursor/filter contract.
- Local manual resolve/reopen/history append hoặc xóa async error/pending states.
- Fixed sample24-hour chart cho1h/7d, false Live/connected hoặc replay guarantee.

## Thứ tự duyệt corrections đề nghị

1. **Visual không đổi data/API:** S01–S05, L01–L02, A01–A02, O04, G03–G04,
   I01–I03, D01–D05, F02; từng item giữ C caveat bên trên. Khôi phục missing icons,
   spacing, compact timestamps, status badges, compact recent list và modal/timeline.
2. **Composition/copy cần lựa chọn:** O01–O03, G01–G02, N01–N03, F01. Đặc biệt:
   card4 Overview = Open incidents(loaded) hay Error rate; Analytics bars/distribution
   dùng Other hay giữ layout hiện tại. G05 giữ snapshot-close hay cho drawer tồn tại
   khi Live reconcile là một behavior decision riêng.
3. Sau approval mới lập exact patch scope/verification cho batch đã chọn. Giữ
   architecture/domain APIs/session/SSE lifecycle; không mở backend/schema/F3 scope.
   Browser compare cần screenshots cùng viewport/context và real safe data hoặc
   isolated review harness được duyệt; không inject fake auth vào production.

Đánh giá: **source-level parity FINDINGS**; browser/pixel parity **NOT VERIFIED**.
Không cần sửa lại toàn bộ CSS hoặc redesign. Trọng tâm là JSX composition, component
variants và presentation formatting. Những khác biệt C giữ nguyên, Q phải chọn;
không coi danh sách này là authorization triển khai.

## Verification / preservation

Chỉ read-only source comparison, hash comparison và CSS parsing bằng installed
PostCSS. Không chạy tests/build/lint/native/backend/dev server hoặc live request;
không dùng old PASS như visual evidence mới. Cả hai HTML/frozen/DESIGN giữ nguyên.
Không đọc `.env`, actual fence hoặc runtime secrets; không kiểm tra lại SSE activation.
Chỉ thêm báo cáo này, không chỉnh React/backend/config, không stage/commit. Báo cáo
acceptance/corrections cũ giữ nguyên. Chờ người dùng duyệt trước mọi điều chỉnh.
