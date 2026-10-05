# Analysis Module

## Purpose
Provide application health analytics and centralized Log Search from ClickHouse without affecting the ingestion path. Analysis owns the `GET /api/v1/logs` search use case: follow FR04 in [Functional Requirements](../product/functional-requirements.md) and the exact filters/cursors in [OpenAPI](../contracts/openapi.yaml). The Backend API layer applies Identity authorization before invoking the query; Analysis receives the authorized scope, never an unrestricted client scope.

## Input
- REST/on-demand analytics and authorized Log Search requests from Backend API.
- Query parameters:
  - application
  - environment
  - time range
  - aggregation interval (`hour` only in MVP)

## Responsibility
1. Query ClickHouse for aggregated statistics and filtered/cursor-paginated logs within the authorized scope.
2. Calculate metrics such as:
   - total logs
   - ERROR count
   - CRITICAL count
   - Log Error Rate
3. Group data by application/environment/hour.
4. Return dashboard-friendly data.
5. Use bounded time ranges and appropriate ClickHouse query patterns.
6. Optionally cache expensive repeated aggregates when justified.

## Output
Responses for Backend API follow [OpenAPI](../contracts/openapi.yaml):

- Log Search: `LogListResponse`, with log records in `data.items` and pagination in `data.next_cursor`.
- Health Analytics: `HealthAnalyticsResponse`, with `data.bucket` and hourly metrics in `data.items`.

## Dependencies
- ClickHouse
- Redis (optional analytics cache)
- Backend API

## Error handling
- ClickHouse unavailable -> return dependency error; do not affect ingestion.
- Invalid/too-wide query -> reject or constrain the request.
- Slow query -> enforce timeout.
- Empty Log Search result -> return a successful `LogListResponse` with `data.items = []` and `data.next_cursor = null`.
- Empty Health Analytics dataset -> return a successful `HealthAnalyticsResponse` with `data.bucket = "hour"` and `data.items = []`.

## Must NOT do
- Must not consume the raw ingestion queue.
- Must not insert primary log records.
- Must not modify Alert Rules.
- Must not open/resolve Incidents.
- Must not block Processing.
