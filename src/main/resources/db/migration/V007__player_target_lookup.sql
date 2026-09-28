-- Usernames are mutable metadata, so this lookup index deliberately is not unique.
CREATE INDEX players_current_username_lookup_idx ON players ((lower (username)), uuid);
