CREATE TABLE command_audit
(
    id                   UUID PRIMARY KEY,
    operation_id         UUID         NOT NULL,
    server_id            VARCHAR(64)  NOT NULL REFERENCES omega_servers (server_id),
    category             VARCHAR(16)  NOT NULL,
    command_path         VARCHAR(128) NOT NULL,
    actor_kind           VARCHAR(16)  NOT NULL,
    actor_uuid           UUID,
    actor_role           VARCHAR(16),
    target_uuid          UUID,
    requested_role       VARCHAR(16),
    outcome              VARCHAR(16)  NOT NULL,
    role_change_audit_id UUID REFERENCES role_change_audit (id),
    occurred_at          TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT command_audit_category CHECK (category IN ('STAFF', 'MANAGEMENT')),
    CONSTRAINT command_audit_path CHECK (command_path ~ '^[a-z][a-z0-9._-]*$'
) ,
    CONSTRAINT command_audit_actor CHECK
        ((actor_kind IN ('LOCAL_CONSOLE', 'UNTRUSTED') AND actor_uuid IS NULL AND actor_role IS NULL)
            OR (actor_kind = 'PLAYER' AND actor_uuid IS NOT NULL
                AND actor_role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER'))),
    CONSTRAINT command_audit_outcome CHECK
        (outcome IN ('INVOKED', 'QUEUED', 'CHANGED', 'UNCHANGED', 'DENIED', 'NOT_FOUND', 'FAILED')),
    CONSTRAINT command_audit_requested_role CHECK
        (requested_role IS NULL OR requested_role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER')),
    CONSTRAINT command_audit_role_subject CHECK
        (command_path <> 'role.set' OR (target_uuid IS NOT NULL AND requested_role IS NOT NULL))
);

CREATE INDEX command_audit_operation_idx ON command_audit (operation_id, occurred_at);
CREATE INDEX command_audit_server_time_idx ON command_audit (server_id, occurred_at DESC);
CREATE INDEX command_audit_actor_time_idx ON command_audit (actor_uuid, occurred_at DESC) WHERE actor_uuid IS NOT NULL;
CREATE INDEX command_audit_target_time_idx ON command_audit (target_uuid, occurred_at DESC) WHERE target_uuid IS NOT NULL;
