ALTER TABLE incidents
    ADD COLUMN resolution_source VARCHAR(20),
    ADD COLUMN resolved_by_user_id UUID REFERENCES users(id),
    ADD CONSTRAINT ck_incidents_resolution_audit CHECK (
        (resolution_source IS NULL AND resolved_by_user_id IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_source IS NOT NULL
            AND ((resolution_source = 'MANUAL' AND resolved_by_user_id IS NOT NULL)
                 OR (resolution_source = 'AUTOMATIC' AND resolved_by_user_id IS NULL)))
    );

CREATE INDEX ix_incidents_manual_resolution
    ON incidents(environment_id, fingerprint, resolved_at DESC)
    WHERE resolution_source = 'MANUAL';
