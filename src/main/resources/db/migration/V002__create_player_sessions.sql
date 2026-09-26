CREATE TABLE player_sessions
(
    id          UUID PRIMARY KEY,
    player_uuid UUID        NOT NULL REFERENCES players (uuid),
    server_id   VARCHAR(64) NOT NULL,
    joined_at   TIMESTAMPTZ NOT NULL,
    left_at     TIMESTAMPTZ,
    CONSTRAINT session_time_order CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE INDEX player_sessions_player_joined_idx
    ON player_sessions (player_uuid, joined_at);
