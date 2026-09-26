CREATE TABLE omega_servers
(
    id        INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL UNIQUE
);

ALTER TABLE player_sessions
    ADD COLUMN end_reason VARCHAR(16),
    ADD COLUMN recovered_at TIMESTAMPTZ;

-- Existing closed rows have no recorded reason. Do not invent a normal quit.
UPDATE player_sessions
SET end_reason = 'LEGACY'
WHERE left_at IS NOT NULL;

ALTER TABLE player_sessions
    ADD CONSTRAINT session_end_state CHECK
        (
        (end_reason IS NULL AND left_at IS NULL AND recovered_at IS NULL)
            OR
        (end_reason IS NOT NULL AND end_reason IN ('QUIT', 'SHUTDOWN', 'LEGACY')
            AND left_at IS NOT NULL AND recovered_at IS NULL)
            OR
        (end_reason IS NOT NULL AND end_reason = 'INTERRUPTED'
            AND left_at IS NULL AND recovered_at IS NOT NULL)
        );

-- Historical conflicting open rows deliberately fail migration instead of being deleted.
CREATE UNIQUE INDEX player_sessions_open_player_server_idx
    ON player_sessions (server_id, player_uuid) WHERE end_reason IS NULL;
