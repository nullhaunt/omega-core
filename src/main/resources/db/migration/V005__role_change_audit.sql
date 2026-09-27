CREATE TABLE role_change_audit
(
    id                UUID PRIMARY KEY,
    server_id         VARCHAR(64)  NOT NULL REFERENCES omega_servers (server_id),
    actor_kind        VARCHAR(16)  NOT NULL,
    actor_uuid        UUID REFERENCES players (uuid),
    actor_role        VARCHAR(16),
    target_uuid       UUID         NOT NULL REFERENCES players (uuid),
    previous_role     VARCHAR(16)  NOT NULL,
    new_role          VARCHAR(16)  NOT NULL,
    previous_revision BIGINT       NOT NULL,
    new_revision      BIGINT       NOT NULL,
    changed_at        TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    reason            VARCHAR(512) NOT NULL,

    CONSTRAINT role_audit_actor CHECK
        ((actor_kind = 'LOCAL_CONSOLE' AND actor_uuid IS NULL AND actor_role IS NULL)
            OR (actor_kind = 'PLAYER' AND actor_uuid IS NOT NULL AND actor_role IS NOT NULL
                AND actor_role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER'))),
    CONSTRAINT role_audit_roles CHECK
        (previous_role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER')
            AND new_role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER')
            AND previous_role <> new_role),
    CONSTRAINT role_audit_revision CHECK
        (previous_revision >= 0 AND new_revision > 0 AND new_revision - 1 = previous_revision),
    CONSTRAINT role_audit_reason CHECK
        (length(btrim(reason)) > 0 AND reason !~ '[[:cntrl:]]'
)
    );

CREATE UNIQUE INDEX role_change_audit_target_revision_idx
    ON role_change_audit (target_uuid, new_revision);

CREATE INDEX role_change_audit_time_idx ON role_change_audit (changed_at);
