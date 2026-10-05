-- Log Monitoring Platform - ClickHouse
-- Purpose: high-volume application log storage and analytics.

CREATE TABLE logs
(
    event_id         UUID,
    application_id   UUID,
    environment_id   UUID,
    host_ip          String,
    level            LowCardinality(String),
    message          String,
    timestamp        DateTime64(3, 'UTC'),
    trace_id         String,
    metadata_json    String,
    received_at      DateTime64(3, 'UTC'),
    processed_at     DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree(processed_at)
PARTITION BY toDate(timestamp)
ORDER BY
(
    application_id,
    environment_id,
    level,
    timestamp,
    event_id
)
TTL timestamp + INTERVAL 7 DAY DELETE
SETTINGS index_granularity = 8192;

-- Data quality constraint is enforced in Processing Module, not by ClickHouse.
-- Expected values for level: INFO, WARN, ERROR, CRITICAL.
-- metadata_json is kept as JSON text for schema flexibility in MVP.
-- event_id is generated once per logical log event and reused on retry/redelivery.
