CREATE TABLE player_nicknames
(
    player_uuid UUID PRIMARY KEY REFERENCES players (uuid),
    nickname    VARCHAR(16),
    revision    BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT nickname_revision CHECK (revision >= 0),
    CONSTRAINT nickname_syntax CHECK
        (nickname IS NULL OR (nickname COLLATE "C" ~ '^[A-Za-z0-9_]{3,16}$'
            AND lower(nickname COLLATE "C") NOT IN ('clear', 'moderator', 'admin', 'owner')))
);

CREATE UNIQUE INDEX player_nickname_key_idx
    ON player_nicknames (lower(nickname COLLATE "C")) WHERE nickname IS NOT NULL;

INSERT INTO player_nicknames (player_uuid)
SELECT uuid
FROM players;

-- Multiple authenticated UUIDs may have recorded the same account spelling.
-- These are reservations, not historical command aliases.
CREATE TABLE player_account_names
(
    name_key    VARCHAR(16) NOT NULL,
    player_uuid UUID        NOT NULL REFERENCES players (uuid),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (name_key, player_uuid)
);

INSERT INTO player_account_names (name_key, player_uuid)
SELECT translate(username, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), uuid
FROM players;

INSERT INTO player_account_names (name_key, player_uuid)
SELECT translate(substring(username FROM 2), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), uuid
FROM players
WHERE username LIKE '.%' ON CONFLICT DO NOTHING;

CREATE TABLE player_nickname_audit
(
    id                        UUID PRIMARY KEY,
    player_uuid               UUID        NOT NULL REFERENCES players (uuid),
    server_id                 VARCHAR(64) NOT NULL REFERENCES omega_servers (server_id),
    actor_uuid                UUID        NOT NULL REFERENCES players (uuid),
    cause                     VARCHAR(32) NOT NULL,
    previous_nickname         VARCHAR(16),
    new_nickname              VARCHAR(16),
    revision                  BIGINT      NOT NULL CHECK (revision > 0),
    changed_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    notification_delivered_at TIMESTAMPTZ,

    CONSTRAINT nickname_audit_cause CHECK
        ((cause = 'SET' AND actor_uuid = player_uuid AND new_nickname IS NOT NULL)
            OR (cause = 'CLEAR' AND actor_uuid = player_uuid AND previous_nickname IS NOT NULL
                AND new_nickname IS NULL)
            OR (cause = 'ACCOUNT_NAME_CONFLICT' AND actor_uuid <> player_uuid
                AND previous_nickname IS NOT NULL AND new_nickname IS NULL)),
    CONSTRAINT nickname_audit_change CHECK (previous_nickname IS DISTINCT FROM new_nickname
) ,
    CONSTRAINT nickname_notice_cause CHECK
        (notification_delivered_at IS NULL OR cause = 'ACCOUNT_NAME_CONFLICT')
);

CREATE UNIQUE INDEX player_nickname_audit_revision_idx
    ON player_nickname_audit (player_uuid, revision);

CREATE INDEX player_nickname_pending_notice_idx
    ON player_nickname_audit (player_uuid, changed_at, id) WHERE cause = 'ACCOUNT_NAME_CONFLICT' AND notification_delivered_at IS NULL;
