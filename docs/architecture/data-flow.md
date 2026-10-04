# Data Flow

## 1. End-to-End Log Flow

```mermaid
flowchart LR
    APP["Application"] -->|"POST JSON + API Key"| ING["Ingestion"]
    ING --> RE["raw.exchange"]
    RE --> RQ["raw.queue"] 
    RQ --> PROC["Processing"]

    PROC -->|"batch insert"| CH[("ClickHouse")]
    PROC --> PE["processed.exchange"] 
    PE --> RTQ["realtime.queue"]
    RTQ --> RT["Realtime"]
    RT -->|"SSE"| UI["Dashboard"]

    PROC -->|"ERROR / CRITICAL"| CE["critical.exchange"]
    CE --> AQ["alert.queue"]
    AQ --> AL["Alert"]
    AL --> RD[("Redis")]
    AL --> PG[("PostgreSQL")]
    AL --> TG["Telegram"]
    AL -->|"incident event"| RT

    PROC -->|"permanent failure"| DLE["dead-letter.exchange"]
    DLE --> DLQ["dead-letter.queue"]

    PROC -. "transient failure" .-> RRQ["raw.retry.queue"]
    RRQ -. "delayed retry" .-> RQ
```

## 2. Ingestion Flow

```mermaid
sequenceDiagram
    participant App as Application
    participant In as Ingestion
    participant Id as Identity
    participant MQ as RabbitMQ

    App->>In: POST logs + API Key
    In->>In: Validate JSON + local API-key cache
    alt Cache miss
        In->>Id: Validate API key
        Id-->>In: Application/environment context
        In->>In: Cache credential
    end
    In->>In: Verify application/environment binding
    In->>MQ: Publish raw event
    MQ-->>In: Publisher confirm
    In-->>App: 202 Accepted
```

The request does not wait for ClickHouse. The raw event carries a stable `event_id`, application/environment identity, original log data, and ingestion metadata.

## 3. Processing Flow

```mermaid
flowchart TD
    Q["raw.queue"] --> V{"Valid?"}
    V -->|"Permanent error"| DLQ["DLQ flow"]
    V -->|"Yes"| N["Normalize"]
    N --> B["Batch"]
    B --> F{"1,000 logs OR 2 seconds?"}
    F -->|"No"| B
    F -->|"Yes"| CH[("ClickHouse")]
    CH -->|"Success"| PUB["Publish processed event + critical event when ERROR/CRITICAL"]
    CH -->|"Transient failure"| RETRY["Retry flow"]
```

Canonical stored log context includes:
- `event_id`
- `application_id`
- `environment_id`
- `host_ip`
- `level`
- `message`
- `timestamp`
- `trace_id`
- `metadata`
- ingestion/processing timestamps

Permanent data errors go to DLQ and are not application incidents.

## 4. Alert Flow

```mermaid
sequenceDiagram
    participant Q as alert.queue
    participant A as Alert
    participant R as Redis
    participant P as PostgreSQL
    participant T as Telegram
    participant RT as Realtime

    Q->>A: Critical event
    A->>P: Load applicable rule
    A->>R: Atomic threshold/dedup check
    alt Threshold not reached
        A->>Q: ACK
    else Threshold reached
        A->>P: Create/update Incident
        A->>T: Notify according to cooldown
        A->>RT: Incident event
        A->>Q: ACK after safe accounting
    end
```

Matching errors with the same fingerprint update the active Incident. Resolution occurs after the configured `resolve_after` period without matching errors.

## 5. Realtime Flow

```mermaid
flowchart LR
    Q["realtime.queue"] --> RT["Realtime"]
    AL["Alert incident event"] --> RT
    RT --> AUTH{"Authorized?"}
    AUTH -->|"No"| DROP["Do not send"]
    AUTH -->|"Yes"| BUF["Bounded buffer / batch / throttle"]
    BUF -->|"SSE"| UI["Dashboard"]
```

Realtime is best-effort. Historical logs are recovered through REST search.

## 6. Search & Analytics

```mermaid
flowchart LR
    UI["Dashboard"] --> API["Backend API"]
    API --> SEARCH["Log Search"] --> CH[("ClickHouse")]
    API --> AN["Analysis"] --> CH
```

Search filters: application, environment, level, time range, trace_id, and message. Pagination is cursor-based.

MVP analytics:
- total logs;
- ERROR count;
- CRITICAL count;
- Log Error Rate;

grouped by application, environment, and hour.

## 7. Retention

```mermaid
flowchart LR
    RET["Retention"] --> CH[("ClickHouse")]
    CH --> DEL["Delete logs older than 7 days"]
```

The policy applies to all log levels in dev/test/staging.
