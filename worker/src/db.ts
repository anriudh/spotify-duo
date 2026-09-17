import type { Env, PlaybackRow, PlayerSnapshot, UserRow } from './types';

export async function getUser(env: Env, id: string): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(id).first<UserRow>();
}

export async function getUserByDeviceToken(env: Env, token: string): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE device_token = ?').bind(token).first<UserRow>();
}

export async function getUserByPendingState(env: Env, state: string): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE pending_state = ?').bind(state).first<UserRow>();
}

export async function listUsers(env: Env): Promise<UserRow[]> {
  const res = await env.DB.prepare('SELECT * FROM users ORDER BY id').all<UserRow>();
  return res.results ?? [];
}

export async function getPlayback(env: Env, userId: string): Promise<PlaybackRow | null> {
  return env.DB.prepare('SELECT * FROM playback WHERE user_id = ?').bind(userId).first<PlaybackRow>();
}

export async function listPlayback(env: Env): Promise<PlaybackRow[]> {
  const res = await env.DB.prepare('SELECT * FROM playback').all<PlaybackRow>();
  return res.results ?? [];
}

export async function setPendingState(env: Env, userId: string, state: string): Promise<void> {
  await env.DB.prepare('UPDATE users SET pending_state = ? WHERE id = ?').bind(state, userId).run();
}

export async function saveLinkedTokens(
  env: Env,
  userId: string,
  tokens: { access_token: string; refresh_token: string; expires_at: number },
  deviceToken: string,
): Promise<void> {
  await env.DB.prepare(
    `UPDATE users
       SET refresh_token = ?, access_token = ?, token_expires_at = ?,
           device_token = ?, pending_state = NULL
     WHERE id = ?`,
  ).bind(tokens.refresh_token, tokens.access_token, tokens.expires_at, deviceToken, userId).run();
}

export async function saveAccessToken(
  env: Env,
  userId: string,
  accessToken: string,
  expiresAt: number,
  refreshToken?: string,
): Promise<void> {
  if (refreshToken) {
    await env.DB.prepare(
      'UPDATE users SET access_token = ?, token_expires_at = ?, refresh_token = ? WHERE id = ?',
    ).bind(accessToken, expiresAt, refreshToken, userId).run();
    return;
  }
  await env.DB.prepare('UPDATE users SET access_token = ?, token_expires_at = ? WHERE id = ?')
    .bind(accessToken, expiresAt, userId).run();
}

/** Clears the refresh token so /state can report that this user must log in again. */
export async function clearLink(env: Env, userId: string): Promise<void> {
  await env.DB.prepare(
    'UPDATE users SET refresh_token = NULL, access_token = NULL, token_expires_at = NULL WHERE id = ?',
  ).bind(userId).run();
}

export async function setDisplayName(env: Env, userId: string, name: string): Promise<void> {
  await env.DB.prepare('UPDATE users SET display_name = ? WHERE id = ?').bind(name, userId).run();
}

export async function markForced(env: Env, userId: string, at: number): Promise<void> {
  await env.DB.prepare('UPDATE users SET last_forced_at = ? WHERE id = ?').bind(at, userId).run();
}

/** `lastActiveAt` of null leaves the existing value alone. */
export async function writePlayback(
  env: Env,
  userId: string,
  s: PlayerSnapshot,
  now: number,
  lastActiveAt: number | null,
): Promise<void> {
  await env.DB.prepare(
    `UPDATE playback
       SET is_playing = ?, track_name = ?, artist_name = ?, album_name = ?, album_art_url = ?,
           track_uri = ?, album_uri = ?, device_name = ?, device_type = ?,
           progress_ms = ?, duration_ms = ?,
           polled_at = ?, last_active_at = COALESCE(?, last_active_at)
     WHERE user_id = ?`,
  ).bind(
    s.is_playing ? 1 : 0, s.track_name, s.artist_name, s.album_name, s.album_art_url,
    s.track_uri, s.album_uri, s.device_name, s.device_type,
    s.progress_ms, s.duration_ms,
    now, lastActiveAt, userId,
  ).run();
}

/** No active session and no history to fall back on: keep the last known track, mark it stopped. */
export async function markStopped(env: Env, userId: string, now: number, wasPlaying: boolean): Promise<void> {
  await env.DB.prepare(
    `UPDATE playback
       SET is_playing = 0, polled_at = ?, last_active_at = CASE WHEN ? = 1 THEN ? ELSE last_active_at END
     WHERE user_id = ?`,
  ).bind(now, wasPlaying ? 1 : 0, now, userId).run();
}
