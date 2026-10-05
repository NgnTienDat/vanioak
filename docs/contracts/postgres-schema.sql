-- Log Monitoring Platform - PostgreSQL
-- Purpose: relational/configuration/state data only.
-- Logs themselves are stored in ClickHouse.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(100) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role
        CHECK (role IN ('ADMIN', 'ENGINEER')),
    CONSTRAINT ck_users_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE applications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_applications_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE environments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID NOT NULL,
    name            VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_environments_application
        FOREIGN KEY (application_id)
        REFERENCES applications(id),
    CONSTRAINT uq_environments_application_name
        UNIQUE (application_id, name),
    CONSTRAINT ck_environments_name
        CHECK (name IN ('DEV', 'TEST', 'STAGING')),
    CONSTRAINT ck_environments_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX ix_environments_application
    ON environments(application_id);

CREATE TABLE ingestion_credentials (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id      UUID NOT NULL,
    key_prefix          VARCHAR(16) NOT NULL,
    key_hash            CHAR(64) NOT NULL UNIQUE,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at          TIMESTAMPTZ,
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,

    CONSTRAINT fk_ingestion_credentials_environment
        FOREIGN KEY (environment_id)
        REFERENCES environments(id),
    CONSTRAINT fk_ingestion_credentials_created_by
        FOREIGN KEY (created_by)
        REFERENCES users(id),
    CONSTRAINT ck_ingestion_credentials_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX ix_ingestion_credentials_environment_status
    ON ingestion_credentials(environment_id, status);

CREATE TABLE user_application_access (
    user_id         UUID NOT NULL,
    application_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, application_id),

    CONSTRAINT fk_user_application_access_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_user_application_access_application
        FOREIGN KEY (application_id)
        REFERENCES applications(id)
);

CREATE INDEX ix_user_application_access_application
    ON user_application_access(application_id);

CREATE TABLE alert_rules (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id          UUID NOT NULL,
    level                   VARCHAR(20) NOT NULL,
    threshold               INTEGER NOT NULL,
    window_seconds          INTEGER NOT NULL,
    cooldown_seconds        INTEGER NOT NULL,
    resolve_after_seconds   INTEGER NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_by              UUID NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_alert_rules_environment
        FOREIGN KEY (environment_id)
        REFERENCES environments(id),
    CONSTRAINT fk_alert_rules_created_by
        FOREIGN KEY (created_by)
        REFERENCES users(id),
    CONSTRAINT uq_alert_rules_scope_level
        UNIQUE (environment_id, level),
    CONSTRAINT ck_alert_rules_level
        CHECK (level IN ('ERROR', 'CRITICAL')),
    CONSTRAINT ck_alert_rules_threshold
        CHECK (threshold > 0),
    CONSTRAINT ck_alert_rules_window
        CHECK (window_seconds > 0),
    CONSTRAINT ck_alert_rules_cooldown
        CHECK (cooldown_seconds > 0),
    CONSTRAINT ck_alert_rules_resolve_after
        CHECK (resolve_after_seconds > 0)
);

CREATE INDEX ix_alert_rules_environment_enabled
    ON alert_rules(environment_id, enabled);

CREATE TABLE incidents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_rule_id       UUID NOT NULL,
    environment_id      UUID NOT NULL,
    level               VARCHAR(20) NOT NULL,
    fingerprint         CHAR(64) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    message             TEXT NOT NULL,
    error_count         BIGINT NOT NULL DEFAULT 0,
    first_seen_at       TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_incidents_alert_rule
        FOREIGN KEY (alert_rule_id)
        REFERENCES alert_rules(id),
    CONSTRAINT fk_incidents_environment
        FOREIGN KEY (environment_id)
        REFERENCES environments(id),
    CONSTRAINT ck_incidents_level
        CHECK (level IN ('ERROR', 'CRITICAL')),
    CONSTRAINT ck_incidents_status
        CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_incidents_error_count
        CHECK (error_count >= 0)
);

-- Only one OPEN incident may exist for the same application/environment/fingerprint.
-- Fingerprint includes level; generation is defined in docs/modules/04-alert.md.
-- The same fingerprint may be opened again after the previous incident is RESOLVED.
CREATE UNIQUE INDEX ux_incidents_open_fingerprint
    ON incidents(environment_id, fingerprint)
    WHERE status = 'OPEN';

CREATE INDEX ix_incidents_scope_status_last_seen
    ON incidents(environment_id, status, last_seen_at DESC);

CREATE INDEX ix_incidents_fingerprint
    ON incidents(fingerprint);

-- Alert accounting receipts only: no message, metadata, or full log payload.
-- Commit with required Incident/notification effects; retain for redelivery safety.
CREATE TABLE alert_event_receipts (
    event_id        UUID PRIMARY KEY,
    environment_id  UUID NOT NULL REFERENCES environments(id),
    fingerprint     CHAR(64) NOT NULL,
    alert_rule_id   UUID REFERENCES alert_rules(id),
    received_at     TIMESTAMPTZ NOT NULL,
    incident_id     UUID REFERENCES incidents(id),
    accounted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- NULL alert_rule_id means accounted without an enabled rule; no frequency contribution.
-- NULL incident_id means the event has not been included in an Incident count.
CREATE INDEX ix_alert_event_receipts_window
    ON alert_event_receipts(environment_id, fingerprint, alert_rule_id, received_at);

CREATE TABLE incident_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     UUID NOT NULL,
    event_type      VARCHAR(30) NOT NULL,
    event_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_count     BIGINT,
    message         TEXT,
    metadata        JSONB,

    CONSTRAINT fk_incident_events_incident
        FOREIGN KEY (incident_id)
        REFERENCES incidents(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_incident_events_type
        CHECK (event_type IN ('OPENED', 'COUNT_UPDATED', 'RESOLVED'))
);

CREATE INDEX ix_incident_events_incident_event_at
    ON incident_events(incident_id, event_at DESC);

CREATE TABLE alert_notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     UUID NOT NULL,
    channel         VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_alert_notifications_incident
        FOREIGN KEY (incident_id)
        REFERENCES incidents(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_alert_notifications_channel
        CHECK (channel IN ('TELEGRAM')),
    CONSTRAINT ck_alert_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_alert_notifications_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX ix_alert_notifications_incident
    ON alert_notifications(incident_id);

CREATE INDEX ix_alert_notifications_status
    ON alert_notifications(status);

-- NOTE:
-- 1) application/environment records are disabled rather than physically deleted.
-- 2) An ingestion credential is scoped to an environment; the environment belongs to an application.
-- 3) Alert rules and incidents reference environment_id; application is derived through environments.
-- 4) This schema intentionally does NOT store log rows.
