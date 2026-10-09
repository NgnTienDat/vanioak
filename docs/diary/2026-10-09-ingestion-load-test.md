# Nhật ký test tải Ingestion — 09/10/2026

Ngày tổng hợp: 09/10/2026, múi giờ Asia/Saigon.

Cập nhật mới nhất: 10/10/2026, sau các lượt k6 người dùng chạy lại.

**Trạng thái tổng thể: Đã giải quyết vấn đề connection lifetime và đóng vấn đề cạn pool ở phạm vi retest hiện tại.** Bản sửa đã pass JDBC IT; single 50 requests/s không còn failures/dropped trong 30 giây. Batch 100 logs/request đạt 9.926,81 logs/s ở 99,27 requests/s, p95 42 ms, không request lỗi nhưng có 22 dropped iterations. Người dùng đã đổi DB trong quá trình retest, nên chưa tách riêng ảnh hưởng của bản sửa và môi trường. Kiểm chứng hoàn chỉnh 1.000 requests/s với nhiều scope và sustained capacity là công việc tiếp theo, chưa thực hiện.

Nhật ký ghi lại kết quả người dùng chạy và quá trình điều tra trong phiên làm việc. Đây là dữ liệu quan sát, không thay đổi authoritative contracts hoặc thay thế kết quả benchmark mới.

## 1. Phạm vi và tiêu chí

Đo đường đi từ HTTP POST đến response 202 sau khi RabbitMQ xác nhận publication, gồm validation, API-key authentication/cache, application/environment binding và publishing. Chưa đo Processing, lưu logs, alerts hoặc queue drain.

- [NFR01](../product/non-functional-requirements.md): mục tiêu sustained ingestion 1.000 logs/s.
- [NFR02](../product/non-functional-requirements.md): trong điều kiện khỏe, ingestion p95 < 100 ms, đo đến MQ confirmation.
- [Testing Strategy](../quality/testing.md#6-load--demo-test): benchmark single và batch riêng, kiểm chứng sustained capacity; không yêu cầu mọi laptop phải đạt cùng capacity.
- [AC15](../quality/acceptance-criteria.md#ac15--required-demo): demo đầy đủ còn bao gồm ClickHouse, Live View và alert path; các lượt đo này chưa chứng minh AC15.

Các định nghĩa đã thống nhất:

```text
Requests/s mục tiêu = TARGET_LOGS_PER_SEC / logs mỗi request
Accepted logs = số responses 202 hợp lệ × logs mỗi request
Actual accepted logs/s = accepted logs / thời gian đo thực, gồm request drain
```

Một HTTP request tạo một raw batch message. Single cũng dùng envelope này với một log. Một message chứa 100 logs vẫn chỉ là một message. VUs là worker phục vụ tải; arrival rate quyết định tốc độ phát request.

Chỉ tính accepted khi response là 202, envelope hợp lệ, accepted=true, request_id là UUID và accepted_count đúng batch size đối với batch. Failed requests và dropped iterations được ghi riêng: dropped là iterations chưa được phát thành HTTP request. Một request thất bại không chứng minh RabbitMQ chưa nhận message.

## 2. Môi trường đã biết

| Thông tin | Ghi nhận |
| --- | --- |
| Người chạy load test | Người dùng chạy k6 thủ công; agent đọc kết quả và điều tra |
| Máy local | Windows/PowerShell; theo người dùng: RAM 16 GB, khoảng 10/15,9 GB đang dùng, 8 CPU; không có time series tài nguyên trong lúc chạy |
| HTTP origin | http://127.0.0.1:8080 |
| Workload | order-service / DEV; dữ liệu INFO với message/metadata cố định, timestamp và trace ID được sinh |
| Redis/RabbitMQ | Local Docker Compose; vhost và resource limits riêng của từng load run chưa được ghi đầy đủ |
| PostgreSQL | Khi điều tra ban đầu: endpoint remote, TLS bắt buộc. Retest 10/10: người dùng đã đổi DB, ban đầu DB trống; chưa ghi topology/RTT/query duration của DB mới |
| Hikari | Log ghi nhận pool 10 connections; acquisition timeout cấu hình mặc định 5.000 ms |
| Cache | Local mặc định 5 giây / 1.000 entries; Identity Redis mặc định 30 giây; deadline không sliding |
| Processing | Chưa có consumer; messages tích lũy trong raw.queue |
| Backend build/cache state | Chưa ghi commit/build và trạng thái warm/cold cho từng lượt; không giả định mọi lượt có cùng điều kiện |
| Isolation | Người dùng đã xác nhận cấu hình hiện tại dành cho dev/test trước JDBC IT; chưa ghi nhận tài nguyên có độc quyền cho từng load run hay không |
| Cloud | Chưa có benchmark trên Azure VM; không ngoại suy capacity từ laptop |

Không lưu API key, hash, password hoặc connection secrets trong nhật ký. Không đọc lại hoặc sửa deployment .env khi tạo tài liệu này.

## 3. Diễn tiến

1. Tạo script k6 và template kết quả: single/batch riêng, constant-arrival-rate, credentials từ process environment, không retry tự động. Syntax đã được kiểm tra khi tạo script.
2. Người dùng chạy single 1.000 logs/s: VU exhaustion, HTTP timeouts và nhiều dropped iterations. Kết quả không đạt throughput/latency.
3. Hạ tải xuống single 10 logs/s: toàn bộ 301 requests thành công, p95 6 ms. Đây là baseline tải thấp.
4. Chạy nhiều lượt single 50 logs/s: responses thành công thường nhanh, nhưng xuất hiện nhóm requests chờ khoảng 5 giây hoặc lâu hơn.
5. Người dùng cung cấp backend logs lúc 17:40:24 +07:00: Hikari cạn pool và Identity lookup thất bại. Điều tra chuyển sang nhánh API-key verification và connection lifetime.
6. Chạy batch 100/300 logs mỗi request ở 1 request/s: không đủ so sánh trực tiếp với single 50 requests/s; tăng logs mỗi request chưa tăng số authentication/publications mỗi giây.
7. Batch 100 logs mỗi request ở 10 requests/s: đạt 1.002,82 accepted logs/s, p95 18 ms, không có lỗi/dropped trong 30 giây.
8. Batch một log mỗi request ở 100 requests/s: xuất hiện lại lỗi, dropped iterations và p99 khoảng 5 giây, dù p95 vẫn dưới 100 ms.
9. Thêm transaction read-only cho findByKeyHash(), giữ verify() NOT_SUPPORTED; 27 focused tests, ordinary verify 154 tests và 4 PostgreSQL/Redis IT đều pass. JDBC IT xác nhận connection được trả trước lookup tiếp theo và Redis population.
10. Retest ngày 10/10 sau khi người dùng đổi DB: single 30 và 50 requests/s đều không lỗi/dropped; batch 100 logs/request ở target 100 requests/s đạt gần 10.000 logs/s. Chi tiết kết quả và trạng thái đóng vấn đề nằm ở mục 10.

Thứ tự trên theo quá trình trao đổi. JSON aggregate không ghi đủ timestamp để dựng timeline chính xác cho từng request hoặc xác định cache state.

## 4. Các kết quả đo

Tất cả các lượt dưới đây có scheduled duration 30 giây. Throughput dùng thời gian elapsed thực trong export. p95/p99 là http_completion_ms, bao gồm setup/chờ kết nối và đọc response.

### Các JSON đang có khi tổng hợp

| Nguồn | Logs/request | Req/s mục tiêu | Logs/s accepted | p95 (ms) | p99 (ms) | Failed / tổng requests | Dropped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [single-10-summary.json](../../load-tests/ingestion/single-10-summary.json) | 1 | 10 | 10,03 | 6 | 251 | 0 / 301 | 0 |
| [single-50-summary.json](../../load-tests/ingestion/single-50-summary.json) | 1 | 50 | 31,16 | 5.028 | 10.001 | 204 / 1.139 | 362 |
| [single-summary.json, hiện tại](../../load-tests/ingestion/single-summary.json) | 1 | 50 | 42,33 | 5.017 | 5.572,19 | 200 / 1.470 | 30 |
| [batch-50-summary.json](../../load-tests/ingestion/batch-50-summary.json) | 100 | 1 | 99,96 | 13,5 | 253,4 | 1 / 31 | 0 |
| [batch-100-summary.json](../../load-tests/ingestion/batch-100-summary.json) | 100 | 1 | 103,22 | 411,5 | 1.421 | 0 / 31 | 0 |
| [batch-300-summary.json](../../load-tests/ingestion/batch-300-summary.json) | 300 | 1 | 309,82 | 113,5 | 264,5 | 0 / 31 | 0 |
| [batch-100_1000-summary.json](../../load-tests/ingestion/batch-100_1000-summary.json) | 100 | 10 | 1.002,82 | 18 | 279 | 0 / 301 | 0 |
| [batch-1_100-summary.json](../../load-tests/ingestion/batch-1_100-summary.json) | 1 | 100 | 82,39 | 46 | 5.021,16 | 113 / 2.585 | 416 |

Tên batch-50-summary.json không phản ánh target: export ghi target 100 logs/s, batch size 100 và 1 request/s. Đọc configuration trong JSON thay vì suy ra từ tên file.

Lượt batch 100 × 10 requests/s có 301 responses 202 hợp lệ, 30.100 logs accepted trong 30,0154391 giây. Lượt batch 300 có 31 responses 202 hợp lệ, 9.300 logs accepted trong 30,0174241 giây. Số đo ngắn có thể hơi vượt offered rate; không coi phần vượt đó là capacity dư đã được chứng minh.

### Kết quả lịch sử có nguồn đã bị ghi đè

[results.md](../../load-tests/ingestion/results.md) giữ các snapshot sau. Không gán chúng cho nội dung JSON hiện tại:

| Lượt lịch sử | Req/s mục tiêu | Elapsed (s) | Logs/s accepted | p95 (ms) | Failed / tổng requests | Dropped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Single 1.000 logs/s ban đầu | 1.000 | 32,9069211 | 48,47 | 10.140 | 2.190 / 3.785 | 26.246 |
| Single 50 logs/s trước đó | 50 | 30,0009367 | 41,23 | 5.012 | 102 / 1.339 | 161 |

single-summary.json hiện chứa lượt 50 logs/s khác. results.md chưa phản ánh đầy đủ các JSON batch mới trong bảng chính; nhật ký này bổ sung snapshot hiện tại, không sửa dữ liệu lịch sử.

## 5. Vấn đề và bằng chứng

### Đã xác nhận: PostgreSQL pool acquisition failure

Backend logs do người dùng cung cấp cho lượt 50 logs/s ghi nhận:

```text
Connection acquisition timeout: 5002–5008 ms
Pool: total=10, active=10, idle=0, waiting=5–9
Identity credential lookup unavailable:
DataAccessResourceFailureException / CannotCreateTransactionException
```

Đây là bằng chứng PostgreSQL pool acquisition thất bại trong Identity verification đối với các request được log. Ingestion chuyển lỗi Identity này thành safe 503. Không đủ dữ liệu để quy toàn bộ failures của mọi lượt cho cùng nguyên nhân; JSON chưa có breakdown HTTP statuses hoặc stage timings.

Ở single-50-summary.json, responses 202 có HTTP duration p95 khoảng 15,13 ms trong khi completion p95 toàn bộ requests là 5.028 ms. Ở batch-1_100-summary.json, responses 202 có HTTP duration p95 khoảng 8 ms, nhưng p99 toàn bộ requests khoảng 5 giây. Phần lớn request nhanh, nhóm request chậm giữ VUs lâu và làm giảm tốc độ phát tải.

### Nguyên nhân có khả năng cao: connection lifetime trong DB fallback

Đối chiếu [ApiKeyService](../../backend/src/main/java/com/h/vanioak/modules/identity/internal/apikey/ApiKeyService.java) và [IngestionCredentialRepository](../../backend/src/main/java/com/h/vanioak/modules/identity/internal/apikey/IngestionCredentialRepository.java) tại thời điểm điều tra, trước bản sửa ở mục 8:

- verify() dùng NOT_SUPPORTED; Spring vẫn có transaction synchronization trong phạm vi lời gọi.
- findByKeyHash() là declared query chưa có transaction riêng. EntityManager của query đầu có thể sống đến hết verify(), giữ connection theo mặc định Hibernate adapter.
- Các findById() kế tiếp có transaction mặc định và có thể mở EntityManager khác, cần thêm connection.
- Khi nhiều request cùng DB fallback, chúng có thể giữ connection đầu rồi cùng chờ connection tiếp theo. Connection đầu cũng có thể còn giữ trong lúc Redis population.

Diagnostic offline dùng JpaTransactionManager của Spring 7.0.9 với EntityManager giả, không kết nối PostgreSQL/Redis/RabbitMQ, cho kết quả:

| Số EntityManager còn mở | Query đầu không có transaction riêng | Query đầu có transaction read-only riêng |
| --- | ---: | ---: |
| Sau query đầu | 1 | 0 |
| Trong query thứ hai | 2 | 1 |
| Trước Redis population | 1 | 0 |
| Sau verify() | 0 | 0 |

Diagnostic xác nhận cơ chế scope của EntityManager, không phải phép đo connection JDBC thực hoặc tái hiện cạn pool dưới tải. Mã nguồn [JpaTransactionManager](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java), [Hibernate adapter](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaVendorAdapter.java) và [Spring Data transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html) hỗ trợ hướng điều tra này.

Chưa có bằng chứng leak connection vĩnh viễn: diagnostic cho thấy EntityManager được đóng khi verify() thoát. Chưa đo slow queries hoặc query plans; lookup hash đã có unique index và lookup scope dùng primary key.

### Yếu tố khuếch đại: concurrent cache misses

[IngestionService](../../backend/src/main/java/com/h/vanioak/modules/ingestion/internal/IngestionService.java) chỉ khóa các thao tác map ngắn. Identity lookup chạy ngoài lock, nên cùng key có thể được verify đồng thời khi cache hết hạn hoặc còn cold. Identity cũng chưa gộp lookup khi Redis miss.

Cache hit vẫn tránh lookup tầng sau: local hit tránh Identity; Redis context hợp lệ tránh PostgreSQL. Local TTL 5 giây, Redis TTL 30 giây và validUntil không sliding là chủ đích. Chưa thấy lỗi fingerprint/TTL trong code hoặc unit tests.

Redis snapshot lúc điều tra có 74 keyspace hits và 1.635 misses cộng dồn. Không có dữ liệu theo key/run để suy ra hit rate của benchmark. Clock Redis/host hiện tại không lệch đáng kể trong phép kiểm tra; không chứng minh clock state của mọi lượt trước đó.

### Giới hạn diễn giải latency và logging

- Batch một log ở 100 requests/s có error rate 4,37%, p95 46 ms nhưng p99 5.021 ms. p95 PASS không làm mất ý nghĩa của 113 failures và 416 dropped iterations.
- Backend không in warning ở lượt toàn bộ thành công là phù hợp với code: warnings chủ yếu dành cho dependency/publication failure. Request chậm nhưng thành công không được log riêng.
- RabbitMQ confirmation timeout cũng mặc định 5 giây. Chỉ aggregate latency khoảng 5 giây không đủ phân biệt DB và broker; Hikari logs mới là bằng chứng trực tiếp cho các request được ghi nhận.
- Ingestion không giữ DB transaction qua RabbitMQ publication. Bằng chứng hiện tại chưa xác định RabbitMQ là bottleneck capacity.

## 6. Trạng thái các vấn đề

| Hạng mục | Trạng thái | Ghi nhận |
| --- | --- | --- |
| Nhầm logs/s, requests/s và messages/s | **Đã làm rõ** | Dùng configuration thật và một raw batch message mỗi request |
| Đo accepted throughput, envelope và export | **Đã kiểm chứng** | Người dùng đã chạy script; số đếm và phép tính được đối chiếu offline |
| Batch 100 đạt 1.000 logs/s, p95 <100 ms | **Đã kiểm chứng trong 30 giây** | 301/301 responses hợp lệ, 0 lỗi/dropped; chưa đạt kết luận sustained |
| Cạn Hikari pool/timeout khi tăng requests/s | **Đã giải quyết trong phạm vi retest hiện tại** | Single 50 requests/s: 1.501/1.501 responses hợp lệ, p95 7 ms, 0 lỗi/dropped; DB cũng đã đổi. Chưa xác nhận mọi mức tải |
| Connection lifetime của findByKeyHash() | **Đã sửa và kiểm chứng JDBC IT** | Connection được trả trước lookup tiếp theo và Redis population trên PostgreSQL/Redis thực, với open-in-view=false |
| Concurrent lookup cùng key khi cache miss | **Chưa thay đổi; theo dõi ở lượt test tiếp theo** | Code vẫn cho phép duplicate lookups; chưa đo mức đóng góp. Không thêm coalescing vì chưa có bằng chứng cần thiết sau sửa |
| Tỷ lệ cache hit/miss, query/confirm latency riêng từng run | **Chưa kiểm chứng** | Chưa có time series/stage metrics tương ứng |
| Sustained capacity, 1.000 requests/s và nhiều scope | **Chưa kiểm chứng; dự kiến test sau** | Các lượt hiện tại là smoke 30 giây, một scope; không quy đổi kết quả batch thành khả năng 1.000 requests/s |
| Processing, queue drain, end-to-end demo | **Chưa triển khai/ngoài phạm vi** | 202 xác nhận publication; không chứng minh downstream persistence |

Không coi thành công với batch là bản sửa lỗi single/high-request-rate. Không tăng pool/timeout, kéo dài cache deadline, thay security hoặc thêm framework để làm benchmark pass trong quá trình điều tra.

## 7. Verification đã chạy và bước tiếp theo

Trong quá trình điều tra, đã chạy từ backend/:

```powershell
mvn.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Lenovo/.m2/repository' '-Dtest=IngestionServiceTest,ApiKeyVerificationTest' test
```

Kết quả: **BUILD SUCCESS — 21 tests, 0 failures/errors/skipped**; ApiKeyVerificationTest 10 tests, IngestionServiceTest 11 tests. Đây là unit tests với mocked storage, chưa chứng minh connection lifetime/concurrent misses trên PostgreSQL thực. Diagnostic offline ở trên cũng đã chạy. Không chạy lại load test hoặc ordinary Maven verify trong các lượt phân tích/tạo nhật ký.

Đề xuất sửa nhỏ nhất ban đầu: thêm @Transactional(readOnly = true) riêng cho findByKeyHash(), giữ verify() NOT_SUPPORTED. Bản sửa này đã được thực hiện ở mục 8. Cần kiểm chứng qua actual Spring proxy rằng connection được trả trước query tiếp theo và Redis I/O; không tăng pool/timeout để che triệu chứng. Chỉ cân nhắc gộp concurrent misses nếu bằng chứng sau sửa vẫn cần.

Sau khi sửa được duyệt và verified, người dùng có thể chạy lại single/batch ở các mức request rate cố định, giữ batch size ổn định khi so sánh, lưu filename riêng và theo dõi pool/cache/resource usage. Sustained run cần test resources được phép sử dụng và đủ dung lượng queue vì chưa có consumer. Không tự chạy tải, purge/delete queue hoặc cleanup dữ liệu trong lượt tạo nhật ký này.

**Kết luận tại thời điểm ghi:** main flow đã có bằng chứng hoạt động tốt với batch ở 10 requests/s. Chịu tải request và sustained capacity còn việc chưa giải quyết; chưa kết luận production readiness hoặc capacity của Azure VM.

## 8. Bổ sung: bản sửa nhỏ nhất — 09/10/2026, 22:54 +07:00

Theo yêu cầu người dùng, đã thêm import và @Transactional(readOnly = true) riêng cho IngestionCredentialRepository.findByKeyHash(). ApiKeyService.verify() vẫn là NOT_SUPPORTED. Không sửa TTL, pool size, timeout hoặc verification business logic.

Thêm một regression test vào [ApiKeyVerificationIT](../../backend/src/test/java/com/h/vanioak/modules/identity/internal/user/ApiKeyVerificationIT.java): databaseConnectionsAreReturnedBeforeNextLookupAndRedisPopulation(). Test dùng actual service/repository proxies, datasource Hikari hiện có và PostgreSQL/Redis của smoke setup. Spies quan sát rồi delegate lời gọi thật, không thay query/cache write bằng kết quả giả.

Test được thiết kế để kiểm tra:

- Configuration hiện tại là spring.jpa.open-in-view=false; không có EntityManager prebound trước verify().
- Hikari active connections bằng 0 trước environment lookup, trước application lookup và trước Redis population.
- Verify trả context hợp lệ và Redis cache thực được populate.

Test mới **đã compile nhưng chưa chạy**. Automatic approval review từ chối lệnh smoke dùng existing environment loader với deployment .env vì PostgreSQL remote chưa được xác nhận là tài nguyên test và chưa có ủy quyền rõ ràng cho tạo/xóa fixtures trên endpoint đó. Lệnh bị chặn trước khi thực thi. Cần tài nguyên PostgreSQL/Redis dành cho test hoặc xác nhận/ủy quyền phù hợp để chạy JDBC IT; không bypass rejection hoặc đổi assertions để giả pass.

Verification thực chạy từ backend/, không nạp deployment .env:

```powershell
mvn.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Lenovo/.m2/repository' '-Dtest=ApiKeyVerificationTest,ApiKeyServiceTest,IngestionServiceTest' test
mvn.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Lenovo/.m2/repository' verify
```

- Focused tests: **27 tests pass**, 0 failures/errors/skipped.
- Ordinary verify: **154 tests pass**, BUILD SUCCESS, 0 failures/errors/skipped. Ordinary verify compile IT nhưng không execute smoke IT.
- Diagnostic offline chạy lại với transaction metadata của repository đã compile: EntityManager còn mở sau query đầu và trước Redis population giảm từ 1 xuống 0 khi query đầu dùng transaction riêng. Đây vẫn là diagnostic với EntityManager giả, không phải kiểm chứng JDBC trên PostgreSQL thật.
- Chưa chạy k6, gửi HTTP đến backend, publish RabbitMQ hoặc thực hiện cleanup hạ tầng trong pass sửa này.

Người dùng sẽ tự rerun k6 ở 50 logs/s sau review; cần đảm bảo backend chạy bản build mới. Hiệu quả thực tế dưới tải, concurrent cache misses và sustained capacity vẫn chưa được xác nhận giải quyết.

## 9. Bổ sung: PostgreSQL/Redis integration verification — 09/10/2026, 23:54 +07:00

Người dùng xác nhận toàn bộ cấu hình hiện tại dành cho dev/test. Sau xác nhận này, đã dùng existing environment loader đọc .env vào process environment, không in secrets hoặc sửa file. Redis host/port được override chỉ trong process thành localhost:6379. Blocker về quyền chạy fixture trên endpoint hiện tại đã được gỡ.

Lệnh thực chạy từ backend/:

```powershell
. ./scripts/environment.ps1
Import-FoundationEnvironment -EnvFile '../.env'
$env:SPRING_DATA_REDIS_HOST = 'localhost'
$env:SPRING_DATA_REDIS_PORT = '6379'
mvn.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Lenovo/.m2/repository' -Psmoke '-Dtest=ApiKeyVerificationTest,ApiKeyServiceTest,IngestionServiceTest' '-Dit.test=ApiKeyVerificationIT' '-Dlogging.level.root=WARN' '-Dlogging.level.org.hibernate=ERROR' '-Dlogging.level.com.zaxxer.hikari=ERROR' '-Dlogging.level.org.springframework=WARN' verify
```

Output được redirect vào target/connection-boundary-integration.log; chỉ đọc báo cáo tổng kết, không đưa secrets vào nhật ký.

Kết quả: **BUILD SUCCESS — 27 focused unit tests và 4 integration tests pass**, 0 failures/errors/skipped. Integration suite mất 28,12 giây; toàn bộ Maven command khoảng 1 phút 2 giây.

- Regression test mới xác nhận Hikari active connections bằng 0 trước environment lookup, application lookup và Redis population. Không còn EntityManager bound tại Redis write; verify trả kết quả hợp lệ và tạo cache thực. Đây là PostgreSQL/Redis verification, không còn chỉ là diagnostic offline.
- Các test hiện có xác nhận absolute expiry/non-sliding cache; rotate/revoke sau commit invalidate cache; rollback giữ credential/cache cũ; Redis fault injection cho phép PostgreSQL fallback và không báo management operation đã commit là thất bại. Fault injection không phải real Redis outage.
- Cleanup fixture PostgreSQL và exact Redis keys của test hoàn tất trong suite; không cleanup dữ liệu khác hoặc queues.
- Ordinary Maven verify trước đó đã pass 154 tests; không chạy lại vì không có code thay đổi sau verification đó. Lượt này bổ sung smoke IT.
- Không phát hiện integration defect trong phạm vi suite. Không sửa thêm production code, không tăng pool/timeout hoặc TTL.

**Còn chờ:** người dùng restart backend với bản build mới và tự rerun k6 50 logs/s. Integration test chứng minh connection boundary trong một verification flow; chưa chứng minh hết pool exhaustion dưới tải, cache miss contention hoặc sustained capacity. Không tự chạy k6, gửi HTTP đến backend hay publish RabbitMQ trong lượt này.

## 10. Retest và đóng vấn đề hiện tại — 10/10/2026

Người dùng chạy lại k6 thủ công, agent đọc ba JSON dưới đây. Tất cả scheduled duration 30 giây, PRE_ALLOCATED_VUS=20, MAX_VUS=100, request timeout 10 giây; workload order-service / DEV. Không lưu credentials trong nhật ký.

| Nguồn | Logs/request | Req/s mục tiêu | Req/s đạt | Logs/s accepted | Accepted logs | 202 / tổng requests | p50 / p95 / p99 (ms) | Failed | Dropped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| [single-1_30-summary.json](../../load-tests/ingestion/single-1_30-summary.json) | 1 | 30 | 30,03 | 30,03 | 901 | 901 / 901 | 5 / 12 / 128 | 0 | 0 |
| [single-1_50-summary.json](../../load-tests/ingestion/single-1_50-summary.json) | 1 | 50 | 50,02 | 50,02 | 1.501 | 1.501 / 1.501 | 4 / 7 / 24 | 0 | 0 |
| [batch-100_10000-summary.json](../../load-tests/ingestion/batch-100_10000-summary.json) | 100 | 100 | 99,27 | 9.926,81 | 297.900 | 2.979 / 2.979 | 6 / 42 / 128,22 | 0 | 22 |

Throughput chia theo elapsed thực lần lượt 30,0063747 / 30,0052604 / 30,0096416 giây, không lấy target làm kết quả đạt. Trong lượt batch, arrival_rate=10000 với timeUnit=100s tương đương 100 requests/s.

### Đã sửa và đã kiểm chứng

- **Code:** thêm duy nhất @Transactional(readOnly = true) cho IngestionCredentialRepository.findByKeyHash(). Query credential có transaction riêng, tránh giữ connection đầu khi lookup environment/application cần connection khác hoặc khi populate Redis.
- **Giữ nguyên:** ApiKeyService.verify() NOT_SUPPORTED; local cache 5 giây / 1.000 entries, Redis cache 30 giây, validUntil/non-sliding deadline, Hikari pool/timeout và verification/security business logic. Không thêm cache framework hoặc coalescing.
- **Regression verification:** 27 focused unit tests, 154 ordinary verify tests và 4 real PostgreSQL/Redis IT pass. IT đo active connections bằng 0 trước environment lookup, application lookup và Redis population, với open-in-view=false hiện tại.
- **Retest:** single 50 logs/s trước đây đạt 31,16 logs/s, 204 failures và 362 dropped, p95 5.028 ms; lượt mới đạt 50,02 logs/s, không failures/dropped, p95 7 ms. Triệu chứng request lỗi/chờ khoảng 5 giây không tái hiện trong aggregate kết quả lượt mới.

**Trạng thái vấn đề cạn pool: ĐÃ GIẢI QUYẾT trong phạm vi sửa connection boundary và các lượt retest hiện tại.** Đây là trạng thái đóng vấn đề hiện tại theo yêu cầu người dùng, không phải cam kết không thể cạn pool ở tải cao hơn. DB mới là thay đổi môi trường đồng thời, nên không quy toàn bộ cải thiện benchmark cho annotation.

### Kết quả đạt được và giới hạn

- Single đã đo 50,02 requests/s; batch đã đo 99,27 requests/s, tương đương 9.926,81 logs/s với 100 logs/request. Đây là throughput đến RabbitMQ-confirmed 202, chưa đo Processing hoặc lưu log downstream.
- Cả ba lượt đạt p95 <100 ms. Single đặt tải dưới 1.000 logs/s nên script NFR01 vẫn FAIL; không dùng đó để kết luận thất bại ở target 30/50. Batch vượt 1.000 logs/s nên cả hai thresholds PASS trong 30 giây; chưa chứng minh tiêu chí sustained.
- Batch đạt khoảng 99,27% target 10.000 logs/s; có 22 iterations chưa được phát. Không ghi nhận backend failed requests, nhưng chưa xác định nguyên nhân dropped iterations hoặc tuyên bố đạt trọn vẹn target 100 requests/s.
- Không suy ra số requests đồng thời từ requests/s hoặc VUs. Summary chưa đo số HTTP requests thực sự đang xử lý cùng lúc.
- Lượt batch-1_30-summary.json ngay trước retest có 894/894 failures; người dùng xác nhận vừa đổi DB và DB trống. Không dùng lượt này đánh giá bản sửa chịu tải; chưa có HTTP status breakdown để kết luận cụ thể hơn.

### Công việc để sau

Test hoàn chỉnh khả năng tiếp nhận **1.000 requests/s với nhiều application/environment scopes và API keys**, gồm cache cold/expiry, tải duy trì dài hơn và ghi rõ môi trường DB/backend/broker, payload/batch size, lỗi/dropped và tài nguyên. Đây là mục tiêu kiểm chứng bổ sung của người dùng; NFR01 hiện tại vẫn là 1.000 logs/s, không thay authoritative contract thành 1.000 requests/s.

Agent chỉ cập nhật nhật ký trong lượt này; không chạy k6, gửi HTTP, publish RabbitMQ, cleanup queues hoặc sửa production/configuration code.
