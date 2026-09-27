CREATE TABLE player_authorizations
(
    player_uuid UUID PRIMARY KEY REFERENCES players (uuid),
    role        VARCHAR(16) NOT NULL,
    revision    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT player_authorization_role CHECK
        (role IN ('PLAYER', 'MODERATOR', 'ADMIN', 'OWNER')),
    CONSTRAINT player_authorization_revision CHECK (revision >= 0)
);

-- No historical role authority exists to import. Existing identities start as PLAYER.
INSERT INTO player_authorizations (player_uuid, role, revision)
SELECT uuid, 'PLAYER', 0
FROM players;
