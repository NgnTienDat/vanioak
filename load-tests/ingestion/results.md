# Ingestion load test — cập nhật 10/10/2026

Đo HTTP POST đến RabbitMQ-confirmed 202; chưa đo Processing hoặc lưu log.
Người dùng chạy k6 thủ công trên localhost, scope order-service / DEV.
Máy: Windows, RAM 16 GB, 8 CPU theo người dùng; resource usage khi chạy chưa đo.
DB đã đổi trong retest; backend build, DB mới và broker resources chưa ghi đủ.

## Kết quả mới
Các lượt dài 30 giây; 20 preallocated / 100 max VUs; request timeout 10 giây.
p50/p95/p99 dùng HTTP completion; throughput dùng elapsed thực gồm drain.

| Nguồn JSON | Logs/req | Target req/s | Đạt req/s | Đạt logs/s | Accepted logs | 202/tổng | Lỗi | Dropped | p50/p95/p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| [single-1_30](single-1_30-summary.json) | 1 | 30 | 30,03 | 30,03 | 901 | 901/901 | 0 | 0 | 5/12/128 |
| [single-1_50](single-1_50-summary.json) | 1 | 50 | 50,02 | 50,02 | 1.501 | 1.501/1.501 | 0 | 0 | 4/7/24 |
| [batch-100_10000](batch-100_10000-summary.json) | 100 | 100 | 99,27 | 9.926,81 | 297.900 | 2.979/2.979 | 0 | 22 | 6/42/128,22 |

Error rate cả ba lượt: 0%; elapsed lần lượt 30,0063747 / 30,0052604 / 30,0096416 giây.
[NFR](../../docs/product/non-functional-requirements.md): ≥1.000 logs/s sustained, p95 <100 ms.
p95 PASS cả ba; batch throughput PASS trong 30 giây, đạt 99,27% target 10.000 logs/s.
Single target 30/50 nên threshold 1.000 logs/s FAIL; chưa kiểm chứng sustained.
Dropped là lượt chưa gửi HTTP; 22 dropped ở batch chưa xác định nguyên nhân.
Requests/s khác requests đồng thời; VUs không chứng minh số requests đang xử lý.

## Vấn đề và bản sửa
Trước sửa: [single 50](single-50-summary.json) đạt 31,16 logs/s, 204/1.139 lỗi, 362 dropped, p95 5.028 ms.
Logs backend ghi nhận Hikari cạn pool 10 connections, acquisition timeout khoảng 5 giây.
Sửa: thêm @Transactional(readOnly=true) cho IngestionCredentialRepository.findByKeyHash().
Giữ verify() NOT_SUPPORTED, TTL local 5s/1.000 entries, Redis 30s, pool và timeout.
JDBC IT xác nhận trả connection trước lookup tiếp theo và Redis population; open-in-view=false.
27 focused tests, 154 ordinary verify tests và 4 PostgreSQL/Redis IT PASS.
Trạng thái: ĐÃ GIẢI QUYẾT trong phạm vi retest; DB cũng đổi nên chưa tách riêng tác động.
Chi tiết và lịch sử: [nhật ký](../../docs/diary/2026-10-09-ingestion-load-test.md).

## Chạy và export — PowerShell tại root
Cài k6: winget install k6 --source winget. Chỉ dùng môi trường test được phép.
Đặt BASE_URL, APPLICATION, ENVIRONMENT, TEST_ENVIRONMENT_APPROVED=true bằng $env:.
API_KEY chỉ qua process environment; không ghi secret vào file hoặc HTTP debug.
```powershell
$testKey = Read-Host 'Test API key' -AsSecureString
$env:API_KEY = [System.Net.NetworkCredential]::new('', $testKey).Password
$env:K6_NEW_MACHINE_READABLE_SUMMARY = 'false'
$env:MODE = 'single'; $env:TARGET_LOGS_PER_SEC = '50'; $env:DURATION = '30s'
$env:SUMMARY_JSON = 'load-tests/ingestion/single-new-summary.json'
k6 run load-tests/ingestion/ingestion.js
$env:MODE = 'batch'; $env:BATCH_SIZE = '100'; $env:TARGET_LOGS_PER_SEC = '10000'
$env:SUMMARY_JSON = 'load-tests/ingestion/batch-new-summary.json'
k6 run --out json=load-tests/ingestion/batch-points.json load-tests/ingestion/ingestion.js
```
1.000 req/s nhiều scope và sustained 5 phút: NOT RUN. Chưa có consumer; queue tích lũy, không purge shared queues.
