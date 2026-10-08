-- Identity-owned refresh JWT rotation metadata; never persist raw JWTs.
-- One family represents one login/refresh session. Access revocation is Redis-only.
CREATE TABLE refresh_tokens (
    token_id        UUID PRIMARY KEY,
    family_id       UUID NOT NULL,
    parent_token_id UUID,
    user_id         UUID NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_refresh_tokens_parent
        FOREIGN KEY (parent_token_id) REFERENCES refresh_tokens(token_id),
    CONSTRAINT uq_refresh_tokens_parent
        UNIQUE (parent_token_id),
    CONSTRAINT ck_refresh_tokens_not_self_parent
        CHECK (parent_token_id IS NULL OR parent_token_id <> token_id),
    CONSTRAINT ck_refresh_tokens_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_tokens_family
    ON refresh_tokens(family_id);

CREATE INDEX ix_refresh_tokens_user
    ON refresh_tokens(user_id);
