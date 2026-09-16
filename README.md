# Spotify Playback Tracker

A shared home-screen widget for two people. Each phone shows what the other is currently
playing on Spotify — track, artist, album art and live progress — with a tap to flip to your
own card. When someone isn't listening, their card greys out and shows what they last played.

Built for two people specifically, on free tiers only, with no hardware and no payment method
on file anywhere.

## Status

| Phase | State |
|---|---|
| **0 — Spotify API access** | ✅ Verified end-to-end on both accounts |
| **1 — Cloudflare Worker backend** | ✅ Deployed and serving live data |
| **2 — Android app + widget** | ✅ Installed and working on device |
| **3 — Visual design pass** | ⬜ Not started |

The second account still needs to complete its one-time Spotify login, so two-way symmetry is
the one thing not yet exercised against real data.

## How it works

```
Spotify Web API
      ▲  polled by the Worker: on read when stale, plus a 1-minute cron
      │
Cloudflare Worker ── D1 (playback state + refresh tokens)
      │
      │  GET /state  — each phone, every 15s while playing
      ▼
  Phone A  ◄──►  Phone B
```

Latency has two independent halves, and both have to be small:

- the Worker re-polls Spotify when its stored rows are older than **15s**
- each phone polls the Worker every **15s** while someone is playing

so the worst case is about **30s**, and typically far less. A refresh button forces it to zero.

### When the widget refreshes

Three situations need a refresh, and they are not equivalent:

| Situation | Handling |
|---|---|
| **The track finished** | *Predictable.* The end is computed from the duration, and the widget wakes exactly then — so the next song appears as it starts |
| **Playback stopped** | *Unpredictable.* Nothing on the phone can know; found by the 15s poll |
| **Skipped to another track** | *Unpredictable.* Same — found by the 15s poll |

The widget also redraws every 15s while playing for a separate reason: **a `ProgressBar` in a
widget cannot advance by itself.** Widget views are drawn by the launcher, not by this app, so
nothing in them executes. `Chronometer` is special-cased by the system and does tick on its
own; `ProgressBar` has no equivalent, so the bar only moves when redrawn. Polling on that same
tick therefore costs no extra wakeups.

If the alarm is delayed and a track runs past its own duration, the card renders as finished —
full bar, stopped clock, *"track ended · checking…"* — rather than presenting stale data as live.

### Why a server, rather than phone-to-phone

- **Honesty about staleness.** If each phone reported only its own playback, a phone that is
  off or offline would leave the other person's widget showing stale data that looks live.
  Polling server-side makes that failure mode impossible.
- **Desktop listening.** A purely on-device approach (reading Spotify's media notification)
  cannot see playback on a laptop. During testing one of us was found listening via
  `Web Player (Microsoft Edge)` — an on-device design would have reported them as idle.

It also avoids a 24/7 foreground service, which Android 15 caps at 6 hours per 24 for the
`dataSync` type.

## Setup

### Spotify

Requires a Developer app in Development Mode. **The app owner must have Spotify Premium** —
Premium Student qualifies, verified. Add both listeners to the app's 5-user allowlist, and
register `https://<your-worker>.workers.dev/auth/callback` as a redirect URI.

Scopes: `user-read-currently-playing`, `user-read-playback-state`, `user-read-recently-played`.
The third is easy to forget and yields `403 "Insufficient client scope"`, which is *not* the
same error as a Premium problem.

### Worker

```bash
cd worker
npm install
npx wrangler login
```

Create the database and paste the returned `database_id` into `wrangler.toml`:

```bash
npx wrangler d1 create spotify-duo
```

```bash
npm run db:remote
```

```bash
npm run deploy
```

Set the Spotify client secret. **This is the only place it goes** — stored encrypted at
Cloudflare, never written to disk, never committed:

```bash
npx wrangler secret put SPOTIFY_CLIENT_SECRET
```

For local development, put `SPOTIFY_CLIENT_SECRET=...` in `worker/.dev.vars` (gitignored).

Then visit `/auth/login?u=anirudh` and `/auth/login?u=divya` once each. Each returns a
**device token** for that person's phone.

### Android

Needs a JDK 17. **Android Studio bundles JDK 25, which AGP 8.7 rejects**, so point the build at
a separate one:

```bash
cd android
```

```bash
JAVA_HOME="/path/to/jdk-17" ./gradlew assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/`, open the app, pick which person the phone
belongs to, sign in, paste the device token, then save. Grant **Alarms & reminders**, set
Battery to **Unrestricted**, and turn off **Pause app activity if unused** — Android will
otherwise stop a widget app it decides is idle.

`local.properties` is gitignored because it hardcodes a machine-specific SDK path.

## API

| Route | Purpose |
|---|---|
| `GET /auth/login?u=<id>` | Starts the Spotify OAuth flow for that user |
| `GET /auth/callback` | Completes it and displays that user's device token once |
| `GET /state` | Both users' playback as JSON. Requires `Authorization: Bearer <device_token>`. Supports `If-None-Match` → `304` |
| `POST /refresh` | Forces an immediate poll of both accounts. Rate-limited to once per 5s per caller |

Each user has their own device token, so one phone can be revoked without re-keying the other.

## Layout

```
worker/
├── wrangler.toml    cron trigger, D1 binding, client ID (public)
├── schema.sql       users + playback tables
└── src/
    ├── index.ts     routes, poll-on-read, cron handler
    ├── spotify.ts   token refresh, /me/player, recently-played
    ├── db.ts        D1 queries
    └── types.ts

android/app/src/main/
├── java/dev/anriudh/spotifyduo/
│   ├── PlaybackWidget.kt     provider, rendering, refresh actions
│   ├── RefreshScheduler.kt   playback-aware alarm chain
│   ├── StateRepository.kt    /state and /refresh, with offline cache
│   ├── PlaybackState.kt      model and JSON parsing
│   ├── ArtCache.kt           album art download, downsample, Palette colour
│   ├── OpenTrackActivity.kt  trampoline so card taps open Spotify
│   ├── MainActivity.kt       one-time setup
│   └── Prefs.kt / BootReceiver.kt
└── res/layout/widget_card.xml
```

`GET /me/player` is used rather than `/me/player/currently-playing` — the same single request,
but it also returns device name and type for the "on …" line, and a paused session still
returns 200 there, so pause is distinguishable from stopped.

Album art is capped at **200px RGB_565 (~80KB)**. RemoteViews cross a Binder transaction capped
near 1MB, and exceeding it throws at runtime rather than failing the build — raising that size
or switching to `ARGB_8888` quadruples it.

## Free-tier usage

| | Per day | Free limit |
|---|---|---|
| Worker requests | ~4,000 | 100,000 |
| D1 rows written | ~6,000 | 100,000 |
| D1 rows read | ~10,000 | 5,000,000 |
| Spotify API calls | ~6,000 | ~180/min |

Comfortably inside every limit, with no payment method required anywhere.
