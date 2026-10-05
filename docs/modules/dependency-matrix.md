# Module Dependency Matrix

| Module | PostgreSQL | ClickHouse | Redis | RabbitMQ | Identity | Telegram |
|---|---|---|---|---|---|---|
| Identity | R/W | - | R/W | - | self | - |
| Ingestion | - | - | local/indirect | Publish | Call | - |
| Processing | - | Write | - | Consume/Publish | - | - |
| Alert | R/W | - | R/W | Consume | - | Send |
| Realtime | - | - | - | Consume | Authorization | - |
| Analysis | - | Read | optional | - | - | - |
| Retention | - | Write/Delete | - | - | - | - |

## Dependency rules
- Ingestion must not depend on ClickHouse availability.
- Processing must not depend on Backend API availability.
- Alert must not depend on Realtime availability for Incident persistence.
- Realtime must not be required for data durability.
- Analysis must not be in the ingestion critical path.
- Retention must not block normal log processing.
