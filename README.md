# Spotify Playback Tracker

A shared home-screen widget for two people. Each phone shows what the other is currently
playing on Spotify — track, artist, album art and progress — with a swipeable second card
for your own playback. When someone isn't listening, their card grays out and shows what
they last played.

Built for two people specifically, on free tiers only, with no hardware and no card on file
anywhere.

## Status

| Phase | State |
|---|---|
| **0 — Spotify API access** | ✅ Verified end-to-end on both accounts |
| **1 — Cloudflare Worker backend** | 🟡 Code complete; typechecks, bundles, schema seeds. Not yet deployed |
| **2 — Android app + widget** | ⬜ Not started |
| **3 — Visual design pass** | ⬜ Not started |

## How it works

```
Spotify Web API
      ▲  cron polls both accounts every minute
      │
Cloudflare Worker ── D1 (playback state + refresh tokens)
      │
      │  GET /state  — each phone pulls every ~4 min, only while the screen is on
      ▼
  Phone A  ◄──►  Phone B
```

Worst-case staleness is **≤5 minutes**: the server is at most 1 minute behind Spotify, and a
phone is at most 4 minutes behind the server. A manual refresh button collapses that to ~0.

### Why a server, rather than phone-to-phone

Two reasons, both learned the hard way:

- **Honesty about staleness.** If each phone reported its own playback, then a phone that is
  off, dead or out of signal would leave the other person's widget showing stale data that
  looks identical to live data. Polling server-side makes that failure mode impossible.
- **Desktop listening.** A purely on-device approach (reading Spotify's media notification)
  cannot see playback on a laptop. During testing one of us was found listening via
  `Web Player (Microsoft Edge)` — an on-device design would have reported them as idle.

It also avoids a 24/7 foreground service, which Android 15 caps at 6 hours per 24 for the
`dataSync` type.

## Setup

Requires a Spotify Developer app in Development Mode. **The app owner must have Spotify
Premium** (Premium Student qualifies — verified). Add both listeners to the app's 5-user
allowlist.

```bash
cd worker
npm install
npx wrangler login
```

Create the database and paste the returned `database_id` into `wrangler.toml`:

```bash
npx wrangler d1 create spotify-duo
```

Apply the schema and deploy:

```bash
npm run db:remote && npm run deploy
```

Set the Spotify client secret. **This is the only place it goes** — it is stored encrypted at
Cloudflare, never written to disk and never committed:

```bash
npx wrangler secret put SPOTIFY_CLIENT_SECRET
```

Add `https://<your-worker>.workers.dev/auth/callback` to the redirect URIs in the Spotify
dashboard. Then visit `/auth/login?u=anirudh` and `/auth/login?u=divya` once each. Each login
returns a **device token** — those go into the Android app.

For local development, put `SPOTIFY_CLIENT_SECRET=...` in `worker/.dev.vars` (gitignored).

## API

| Route | Purpose |
|---|---|
| `GET /auth/login?u=<id>` | Starts the Spotify OAuth flow for that user |
| `GET /auth/callback` | Completes it and displays that user's device token once |
| `GET /state` | Both users' playback as JSON. Requires `Authorization: Bearer <device_token>`. Supports `If-None-Match` → `304` |
| `POST /refresh` | Forces an immediate poll of both accounts, then returns state. Rate-limited to once per 5s per caller |

Each user has their own device token, so one phone can be revoked without re-keying the other.

## Layout

```
worker/
├── wrangler.toml    cron trigger, D1 binding, client ID (public)
├── schema.sql       users + playback tables
└── src/
    ├── index.ts     routes and the cron poller
    ├── spotify.ts   token refresh, /me/player, recently-played seeding
    ├── db.ts        D1 queries
    └── types.ts
```

`GET /me/player` is used rather than `/me/player/currently-playing` — the same single request,
but it also returns device name and type for the "listening on…" chip.

## Free-tier usage

| | Per day | Free limit |
|---|---|---|
| Worker requests | ~1,600 | 100,000 |
| D1 rows written | ~2,900 | 100,000 |
| D1 rows read | ~1,600 | 5,000,000 |
| Spotify API calls | ~2,900 | ~180/min |

Comfortably inside every limit, with no payment method required anywhere.
