export interface Env {
  DB: D1Database;
  SPOTIFY_CLIENT_ID: string;
  SPOTIFY_CLIENT_SECRET: string;
}

export interface UserRow {
  id: string;
  display_name: string;
  device_token: string | null;
  pending_state: string | null;
  refresh_token: string | null;
  access_token: string | null;
  token_expires_at: number | null;
  last_forced_at: number;
}

export interface PlaybackRow {
  user_id: string;
  is_playing: number;
  track_name: string | null;
  artist_name: string | null;
  album_name: string | null;
  album_art_url: string | null;
  track_uri: string | null;
  album_uri: string | null;
  device_name: string | null;
  device_type: string | null;
  progress_ms: number | null;
  duration_ms: number | null;
  polled_at: number;
  last_active_at: number | null;
}

/** Normalised snapshot of Spotify's player state. */
export interface PlayerSnapshot {
  is_playing: boolean;
  track_name: string;
  artist_name: string;
  album_name: string | null;
  album_art_url: string | null;
  track_uri: string;
  album_uri: string | null;
  device_name: string | null;
  device_type: string | null;
  progress_ms: number;
  duration_ms: number;
  /** When the track was actually played, from recently-played. Null for live player state. */
  played_at: number | null;
}

export interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
}
