CREATE TABLE IF NOT EXISTS users (
  id               TEXT PRIMARY KEY,
  display_name     TEXT NOT NULL,
  device_token     TEXT,
  pending_state    TEXT,
  refresh_token    TEXT,
  access_token     TEXT,
  token_expires_at INTEGER,
  last_forced_at   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS playback (
  user_id        TEXT PRIMARY KEY REFERENCES users(id),
  is_playing     INTEGER NOT NULL DEFAULT 0,
  track_name     TEXT,
  artist_name    TEXT,
  album_name     TEXT,
  album_art_url  TEXT,
  track_uri      TEXT,
  album_uri      TEXT,
  device_name    TEXT,
  device_type    TEXT,
  progress_ms    INTEGER,
  duration_ms    INTEGER,
  polled_at      INTEGER NOT NULL DEFAULT 0,
  last_active_at INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_device_token ON users(device_token);

INSERT OR IGNORE INTO users (id, display_name) VALUES ('anirudh', 'Anirudh'), ('divya', 'Divya');
INSERT OR IGNORE INTO playback (user_id) VALUES ('anirudh'), ('divya');
