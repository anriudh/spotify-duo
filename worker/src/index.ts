import * as db from './db';
import { SCOPES, exchangeCode, fetchPlayer, fetchRecentlyPlayed, getFreshAccessToken } from './spotify';
import type { Env, PlaybackRow, UserRow } from './types';

const FORCED_REFRESH_COOLDOWN_MS = 5_000;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    switch (url.pathname) {
      case '/auth/login':
        return handleLogin(env, url);
      case '/auth/callback':
        return handleCallback(env, url);
      case '/state':
        return handleState(req, env);
      case '/refresh':
        return handleForcedRefresh(req, env);
      default:
        return new Response('Not found', { status: 404 });
    }
  },

  async scheduled(_c: ScheduledController, env: Env): Promise<void> {
    await pollAll(env);
  },
};

async function handleLogin(env: Env, url: URL): Promise<Response> {
  const userId = url.searchParams.get('u');
  if (!userId) return new Response('Missing ?u=', { status: 400 });

  const user = await db.getUser(env, userId);
  if (!user) return new Response('Unknown user', { status: 404 });

  const state = crypto.randomUUID();
  await db.setPendingState(env, user.id, state);

  const authorize = new URL('https://accounts.spotify.com/authorize');
  authorize.searchParams.set('client_id', env.SPOTIFY_CLIENT_ID);
  authorize.searchParams.set('response_type', 'code');
  authorize.searchParams.set('redirect_uri', `${url.origin}/auth/callback`);
  authorize.searchParams.set('scope', SCOPES);
  authorize.searchParams.set('state', state);
  return Response.redirect(authorize.toString(), 302);
}

async function handleCallback(env: Env, url: URL): Promise<Response> {
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  if (!code || !state) return new Response('Missing code or state', { status: 400 });

  const user = await db.getUserByPendingState(env, state);
  if (!user) return new Response('Unrecognised state', { status: 400 });

  let tok;
  try {
    tok = await exchangeCode(env, code, `${url.origin}/auth/callback`);
  } catch (err) {
    // Surfaced rather than thrown: an opaque 1101 here is indistinguishable
    // from the Worker being broken, and Spotify's own error names the cause.
    return errorPage('Could not complete Spotify login', String(err));
  }
  if (!tok.refresh_token) return errorPage('Spotify returned no refresh token', 'Re-run the login flow.');

  const deviceToken = user.device_token ?? crypto.randomUUID().replace(/-/g, '');
  await db.saveLinkedTokens(
    env,
    user.id,
    { access_token: tok.access_token, refresh_token: tok.refresh_token, expires_at: Date.now() + tok.expires_in * 1000 },
    deviceToken,
  );

  return new Response(
    `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
     <body style="font-family:system-ui;padding:2rem;line-height:1.5">
       <h2>${escapeHtml(user.display_name)} is linked</h2>
       <p>Paste this device token into the Android app. It is shown once per login:</p>
       <p style="font-family:ui-monospace,monospace;font-size:1.1rem;word-break:break-all;
                 background:#f4f4f5;padding:.75rem;border-radius:.5rem">${deviceToken}</p>
     </body>`,
    { headers: { 'Content-Type': 'text/html; charset=utf-8' } },
  );
}

async function requireUser(req: Request, env: Env): Promise<UserRow | null> {
  const auth = req.headers.get('Authorization');
  if (!auth?.startsWith('Bearer ')) return null;
  return db.getUserByDeviceToken(env, auth.slice(7));
}

async function handleState(req: Request, env: Env): Promise<Response> {
  const caller = await requireUser(req, env);
  if (!caller) return new Response('Unauthorized', { status: 401 });
  return stateResponse(env, req.headers.get('If-None-Match'));
}

async function handleForcedRefresh(req: Request, env: Env): Promise<Response> {
  if (req.method !== 'POST') return new Response('Method not allowed', { status: 405 });

  const caller = await requireUser(req, env);
  if (!caller) return new Response('Unauthorized', { status: 401 });

  const now = Date.now();
  if (now - caller.last_forced_at >= FORCED_REFRESH_COOLDOWN_MS) {
    await db.markForced(env, caller.id, now);
    await pollAll(env);
  }
  return stateResponse(env, null);
}

async function stateResponse(env: Env, ifNoneMatch: string | null): Promise<Response> {
  const [users, playback] = await Promise.all([db.listUsers(env), db.listPlayback(env)]);
  const byId = new Map(playback.map((p) => [p.user_id, p]));

  const payload = users.map((u) => {
    const p = byId.get(u.id);
    return {
      id: u.id,
      display_name: u.display_name,
      needs_login: !u.refresh_token,
      is_playing: p ? p.is_playing === 1 : false,
      track_name: p?.track_name ?? null,
      artist_name: p?.artist_name ?? null,
      album_name: p?.album_name ?? null,
      album_art_url: p?.album_art_url ?? null,
      track_uri: p?.track_uri ?? null,
      device_name: p?.device_name ?? null,
      device_type: p?.device_type ?? null,
      progress_ms: p?.progress_ms ?? null,
      duration_ms: p?.duration_ms ?? null,
      polled_at: p?.polled_at ?? 0,
      last_active_at: p?.last_active_at ?? null,
    };
  });

  const etag = etagFor(playback, users);
  if (ifNoneMatch === etag) {
    return new Response(null, { status: 304, headers: { ETag: etag } });
  }

  // server_time lets the client correct for phone clock skew when ticking progress.
  return new Response(JSON.stringify({ server_time: Date.now(), users: payload }), {
    headers: { 'Content-Type': 'application/json', ETag: etag, 'Cache-Control': 'no-cache' },
  });
}

/**
 * Deliberately excludes polled_at: while a user is idle nothing else changes, so
 * repeat pulls collapse to 304. While playing, progress_ms moves and the client
 * gets a fresh 200.
 */
function etagFor(playback: PlaybackRow[], users: UserRow[]): string {
  const linked = new Set(users.filter((u) => u.refresh_token).map((u) => u.id));
  const basis = playback
    .map((r) => [r.user_id, linked.has(r.user_id), r.is_playing, r.track_uri, r.progress_ms, r.device_name].join('|'))
    .sort()
    .join('~');

  let h = 5381;
  for (let i = 0; i < basis.length; i++) h = (((h << 5) + h + basis.charCodeAt(i)) | 0);
  return `W/"${(h >>> 0).toString(36)}"`;
}

async function pollAll(env: Env): Promise<void> {
  const users = await db.listUsers(env);
  await Promise.all(users.map((u) => pollUser(env, u).catch(() => {})));
}

async function pollUser(env: Env, user: UserRow): Promise<void> {
  const token = await getFreshAccessToken(env, user);
  if (!token) return;

  const now = Date.now();
  const snapshot = await fetchPlayer(token);
  if (snapshot) {
    await db.writePlayback(env, user.id, snapshot, now);
    return;
  }

  const prev = await db.getPlayback(env, user.id);
  if (!prev?.track_uri) {
    const seed = await fetchRecentlyPlayed(token);
    if (seed) await db.writePlayback(env, user.id, seed, now);
    return;
  }
  await db.markStopped(env, user.id, now, prev.is_playing === 1);
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function errorPage(title: string, detail: string): Response {
  return new Response(
    `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
     <body style="font-family:system-ui;padding:2rem;line-height:1.5">
       <h2>${escapeHtml(title)}</h2>
       <pre style="white-space:pre-wrap;word-break:break-word;background:#f4f4f5;
                   padding:.75rem;border-radius:.5rem">${escapeHtml(detail)}</pre>
     </body>`,
    { status: 502, headers: { 'Content-Type': 'text/html; charset=utf-8' } },
  );
}
