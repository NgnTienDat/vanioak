# Analysis Module

## Purpose
Provide application health analytics from ClickHouse without affecting the ingestion path.

## Input
- REST/on-demand analysis requests from Backend API.
- Query parameters:
  - application
  - environment
  - time range
  - aggregation interval

## Responsibility
1. Query ClickHouse for aggregated statistics.
2. Calculate metrics such as:
   - total logs
   - ERROR count
   - CRITICAL count
   - Log Error Rate
3. Group data by application/environment/time window.
4. Return dashboard-friendly data.
5. Use bounded time ranges and appropriate ClickHouse query patterns.
6. Optionally cache expensive repeated aggregates when justified.

## Output
Structured analytics response for Backend API, for example:
- hourly time series
- per-application totals
- error-rate metrics

## Dependencies
- ClickHouse
- Redis (optional analytics cache)
- Backend API

## Error handling
- ClickHouse unavailable -> return dependency error; do not affect ingestion.
- Invalid/too-wide query -> reject or constrain the request.
- Slow query -> enforce timeout.
- Empty dataset -> return successful response with empty `data`.

## Must NOT do
- Must not consume the raw ingestion queue.
- Must not insert primary log records.
- Must not modify Alert Rules.
- Must not open/resolve Incidents.
- Must not block Processing.
