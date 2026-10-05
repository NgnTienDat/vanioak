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
    B --> F{"Batch flush due?"}
    F -->|"No"| B
    F -->|"Yes"| CH[("ClickHouse")]
    CH -->|"Success"| PUB["Publish processed event + critical event when ERROR/CRITICAL"]
    CH -->|"Transient failure"| RETRY["Retry flow"]
```

The flush policy is defined in [Processing Module](../modules/03-processing.md). Canonical event and stored-log shapes are defined in [Event Contracts](../contracts/event-contracts.md) and [ClickHouse Schema](../contracts/clickhouse-schema.sql).

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

    Q->>A: Critical event (stable event_id)
    A->>P: Check receipt under scope lock
    alt Already committed receipt
        A->>Q: ACK without recounting or changing state
    else Unseen event
        A->>R: Reconcile frequency and evaluate rule
        Note over A,P: Account below-threshold events; update matching OPEN Incident even below threshold
        A->>P: Persist receipt and required Incident/notification state in one transaction
        alt Safe accounting committed
            P-->>A: Commit confirmed
            A->>Q: ACK
            opt Notification work reserved
                A-->>T: Deliver/retry committed notification independently
            end
            opt Incident lifecycle changed
                A-->>RT: Best-effort Incident event
            end
        else Transient failure before safe accounting
            A->>A: Retry same event_id; no ACK before safe accounting or transfer
        end
    end
```

Detailed receipt persistence, redelivery without double-counting, and confirmed retry/DLQ transfer rules are defined in [Alert accounting and redelivery](failure-handling.md#alert-accounting-and-redelivery). Matching errors update the active Incident; resolution follows the configured `resolve_after` period without matching errors.

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
    API -->|"Log Search / Health Analytics"| AN["Analysis"]
    AN --> CH[("ClickHouse")]
```

Search behavior and analytics metrics are defined in FR04 and FR08 of [Functional Requirements](../product/functional-requirements.md).

## 7. Retention

```mermaid
flowchart LR
    RET["Retention"] --> CH[("ClickHouse")]
    CH --> DEL["Delete logs older than 7 days"]
```

The policy applies to all log levels in dev/test/staging.
