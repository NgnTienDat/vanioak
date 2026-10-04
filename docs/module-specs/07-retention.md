# Retention Module

## Purpose
Automatically remove log data older than the 7-day dev/test retention window.

## Input
- Scheduled background job.
- Retention configuration.

## Responsibility
1. Run on a fixed schedule.
2. Enforce the current MVP retention policy:
   - all log levels
   - all applications
   - all environments
   - older than 7 days are eligible for removal
3. Use ClickHouse-native TTL/partition cleanup where practical.
4. Monitor/report cleanup execution status and failures.
5. Never block ingestion or Processing.

## Output
- Cleanup actions in ClickHouse.
- Operational job status/logs.

## Dependencies
- ClickHouse
- Scheduler/Cron mechanism

## Error handling
- ClickHouse unavailable -> log failure and retry next scheduled run; do not affect ingestion.
- Partial cleanup failure -> report failure; do not claim complete cleanup.
- Job overlap -> prevent concurrent retention jobs.

## Must NOT do
- Must not delete PostgreSQL configuration/incident data as part of log retention.
- Must not delete recent logs.
- Must not delete based on log level in MVP.
- Must not run synchronous cleanup during log ingestion.
- Must not block Processing or Realtime modules.
