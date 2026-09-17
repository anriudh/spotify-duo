import * as db from './db';
import type { Env, PlayerSnapshot, TokenResponse, UserRow } from './types';

const TOKEN_URL = 'https://accounts.spotify.com/api/token';
const API = 'https://api.spotify.com/v1';

export const SCOPES = 'user-read-currently-playing user-read-playback-state user-read-recently-played';

function basicAuth(env: Env): string {
  return `Basic ${btoa(`${env.SPOTIFY_CLIENT_ID}:${env.SPOTIFY_CLIENT_SECRET}`)}`;
}

export async function exchangeCode(env: Env, code: string, redirectUri: string): Promise<TokenResponse> {
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { Authorization: basicAuth(env), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: redirectUri }),
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  return res.json<TokenResponse>();
}

/**
 * Returns a usable access token, refreshing only within 5 minutes of expiry.
 * Returns null when the user has never linked, or when Spotify rejects the
 * refresh token outright (password change / revoked access) -- in that case the
 * link is cleared so /state can surface "needs re-login".
 */
export async function getFreshAccessToken(env: Env, user: UserRow): Promise<string | null> {
  if (!user.refresh_token) return null;

  const now = Date.now();
  if (user.access_token && user.token_expires_at && user.token_expires_at - now > 5 * 60_000) {
    return user.access_token;
  }

  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { Authorization: basicAuth(env), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: user.refresh_token }),
  });

  if (res.status === 400) {
    await db.clearLink(env, user.id);
    return null;
  }
  if (!res.ok) return null;

  const tok = await res.json<TokenResponse>();
  const expiresAt = now + tok.expires_in * 1000;
  await db.saveAccessToken(env, user.id, tok.access_token, expiresAt, tok.refresh_token);
  return tok.access_token;
}

/**
 * Largest available, normally 640px. The widget decodes it *down* to its own
 * size; detail has to come from the source, and the old 300px pick was being
 * upscaled on screen.
 */
function pickArt(images: Array<{ url: string; width: number }> | undefined): string | null {
  if (!images?.length) return null;
  return images.reduce((best, i) => (i.width > best.width ? i : best)).url;
}

/** Handles tracks, podcast episodes and ads without special-casing at the call site. */
function toSnapshot(body: any): PlayerSnapshot | null {
  const item = body?.item;
  if (!item?.name) return null;

  const artists: string | undefined = item.artists?.map((a: any) => a.name).join(', ');
  return {
    is_playing: Boolean(body.is_playing),
    track_name: item.name,
    artist_name: artists || item.show?.name || '',
    album_name: item.album?.name ?? item.show?.name ?? null,
    album_art_url: pickArt(item.album?.images ?? item.images),
    track_uri: item.uri,
    album_uri: item.album?.uri ?? item.show?.uri ?? null,
    device_name: body.device?.name ?? null,
    device_type: body.device?.type ?? null,
    progress_ms: body.progress_ms ?? 0,
    duration_ms: item.duration_ms ?? 0,
    played_at: null,
  };
}

/** GET /me/player. Returns null when nothing is playing (204) or on a transient error. */
export async function fetchPlayer(accessToken: string): Promise<PlayerSnapshot | null> {
  const res = await fetch(`${API}/me/player`, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (res.status === 204 || !res.ok) return null;
  return toSnapshot(await res.json());
}

/**
 * Authoritative source for what a user last played, and when. Consulted
 * whenever there is no active session -- with poll-on-read there may have been
 * a long unobserved gap in which they played and stopped, so the stored track
 * cannot be trusted and `played_at` is the only accurate "how long ago".
 */
export async function fetchRecentlyPlayed(accessToken: string): Promise<PlayerSnapshot | null> {
  const res = await fetch(`${API}/me/player/recently-played?limit=1`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) return null;

  const body = await res.json<any>();
  const item = body?.items?.[0];
  const track = item?.track;
  if (!track?.name) return null;

  const playedAt = item.played_at ? Date.parse(item.played_at) : NaN;

  return {
    is_playing: false,
    track_name: track.name,
    artist_name: track.artists?.map((a: any) => a.name).join(', ') ?? '',
    album_name: track.album?.name ?? null,
    album_art_url: pickArt(track.album?.images),
    track_uri: track.uri,
    album_uri: track.album?.uri ?? null,
    device_name: null,
    device_type: null,
    progress_ms: 0,
    duration_ms: track.duration_ms ?? 0,
    played_at: Number.isNaN(playedAt) ? null : playedAt,
  };
}
