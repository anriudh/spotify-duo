# Spotify Duo

A home-screen widget that shows what one other person is listening to on Spotify, right now,
with album art, live progress and the device they're on — and flips to show your own card with a
tap. When they aren't listening it greys out and says what they last played and how long ago.

It's built for exactly two people. Each person has a colour, chooses the name the other sees,
and gets their own device token so either phone can be revoked alone. Everything runs on free
tiers with no card on file: a Cloudflare Worker, a D1 database and a small Android app.

## Status

Finished and in daily use on both phones (Moto G73 / Android 14, Moto G86 Power / Android 15).

Verified against real data: two-way symmetry, boot recovery, screen-off polling, sub-1% CPU,
~3.5MB storage, no Binder-size crashes at the largest widget size.

## How it works

```
Spotify Web API
      ▲  polled by the Worker: on read when older than 15s, plus a 1-minute cron
      │
Cloudflare Worker ── D1 (playback rows, refresh tokens, display names)
      │
      │  GET /state with ETag — each phone, every 15s while someone is playing
      ▼
  Phone A  ◄──►  Phone B
```

Latency has two halves, each capped at 15s, so the worst case is ~30s and typically much less.
The refresh button forces both to zero.

### Why a server at all

- **Honesty about staleness.** If each phone reported its own playback, a phone that's off would
  leave the other person's widget showing stale data that looks live. Polling Spotify server-side
  makes that impossible: you see your partner's real state even when their phone is dead.
- **Desktop listening.** Reading Spotify's media notification on-device can't see a laptop.
  During testing one of us was on `Web Player (Microsoft Edge)`.
- **No foreground service.** Android 15 caps `dataSync` services at 6h/day.

### What "last played" means

Spotify's recently-played history isn't the same as "what you were last listening to": a track
only enters it after ~30s of play, it lags by minutes, and the song that was on when you closed
the app is often missing. Taken naively that regresses the widget to an older song the moment a
session ends.

So the Worker treats its own observations as ground truth. If it saw a track playing more
recently than history's timestamp, it keeps that track and only marks it stopped; history wins
only when it's genuinely newer (you listened while nobody was looking). The 1-minute cron is what
makes those observations exist even when neither phone is polling.

### When the widget refreshes

| Situation | Handling |
|---|---|
| **The track finished** | Predictable: the widget wakes exactly when the duration runs out, so the next song appears as it starts |
| **Playback stopped or skipped** | Unpredictable: caught by the 15s poll |
| **Nobody listening** | A lazy 4-minute alarm, which Doze stretches further on an idle phone |

The 15s redraw while playing exists for a second reason: a `ProgressBar` in a widget cannot
advance on its own. Widget views are drawn by the launcher, not by this app; `Chronometer` is
special-cased by the system, `ProgressBar` isn't. Polling on that same tick costs no extra
wakeups. If an alarm is late and a track runs past its duration, the card renders as finished —
full bar, stopped clock, *"track ended · checking…"* — rather than presenting stale data as live.

### The card

- **Frosted glass behind the text only.** The region the title and artist occupy is measured
  from the same font metrics the TextViews use, mapped through the `centerCrop` transform into
  bitmap space, blurred and feathered there, and baked into the art. One bitmap crosses Binder.
- **Art is Spotify's 640px source decoded down to 400px RGB_565 (~320KB).** RemoteViews cross a
  ~1MB Binder transaction; exceeding it throws at runtime, not at build. 640px RGB_565 is 820KB —
  too close.
- **Compact mode under 150dp.** One launcher row reports ~134dp on a 6.5" phone, which clips the
  status line. Below the threshold the card tightens its padding and folds the status into the
  artist line: *"Dua Lipa · last played 4 hours ago"*. The bar stays.
- **Resize floor is 40dp**, not the 110dp default. Launcher3 takes the largest minimum span across
  every orientation and adds its own padding first; landscape rows are ~65dp, so anything above
  ~50dp silently rounds one-row widgets back up to two.
- **Tap opens the album, not the track.** Measured: both `spotify:track:` and
  `open.spotify.com/track/` links are play commands and restart the song. The album page opens
  without touching playback.
- **Two `ProgressBar`s, one per colour.** RemoteViews can't swap a `progressDrawable` below
  API 31, so visibility is toggled instead.

## Setup

### Spotify

A Developer app in Development Mode. **The app owner must have Premium** (Student qualifies).
Add both listeners to the 5-user allowlist and register
`https://<your-worker>.workers.dev/auth/callback` as a redirect URI.

Scopes: `user-read-currently-playing user-read-playback-state user-read-recently-played`. The
third is easy to forget and yields `403 Insufficient client scope`, which is not a Premium error.

### Worker

```bash
cd worker && npm install && npx wrangler login
```

Create the database, paste its `database_id` into `wrangler.toml`, then load the schema and deploy:

```bash
npx wrangler d1 create spotify-duo
```

```bash
npm run db:remote && npm run deploy
```

The client secret lives only in Cloudflare — never on disk, never committed:

```bash
npx wrangler secret put SPOTIFY_CLIENT_SECRET
```

(`worker/.dev.vars`, gitignored, holds it for `wrangler dev`.) Then each person visits
`/auth/login?u=<id>` once and gets a device token for their phone.

### Android

Needs JDK 17 — Android Studio's bundled JDK 25 is rejected by AGP 8.7:

```bash
cd android && JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`, open the app, pick who you are, sign in,
paste the device token, type the name the other person should see, save. Then grant
**Alarms & reminders**, set Battery to **Unrestricted**, and turn off **Pause app activity if
unused** — otherwise Android stops a widget app it decides is idle. Add the widget and resize
it to taste; 3×1 and 4×1 use the compact layout.

## API

| Route | Purpose |
|---|---|
| `GET /auth/login?u=<id>` | Starts Spotify OAuth for that user |
| `GET /auth/callback` | Completes it and shows the device token once |
| `GET /state` | Both users as JSON. `Authorization: Bearer <device_token>`. Honours `If-None-Match` → `304` while nothing changes |
| `POST /refresh` | Polls both accounts now. Once per 5s per caller |
| `POST /me` | `{"display_name": "…"}`, 1–20 chars — how the caller appears on the *other* phone |

## Layout

```
worker/
├── wrangler.toml     cron, D1 binding, client ID (public)
├── schema.sql        users + playback
└── src/
    ├── index.ts      routes, poll-on-read, history guard, cron
    ├── spotify.ts    token refresh, /me/player, recently-played
    ├── db.ts         D1 queries
    └── types.ts

android/app/src/main/
├── java/dev/anriudh/spotifyduo/
│   ├── PlaybackWidget.kt     provider, rendering, compact mode, frost geometry
│   ├── ArtCache.kt           download, downsample, region blur, desaturate
│   ├── RefreshScheduler.kt   playback-aware alarm chain
│   ├── StateRepository.kt    Worker client with offline cache + ETag
│   ├── PlaybackState.kt      model, JSON, clock-skew correction
│   ├── OpenTrackActivity.kt  trampoline so card taps open Spotify (Android 14 PendingIntent rule)
│   ├── Person.kt             per-person colour
│   ├── MainActivity.kt       one-time setup screen
│   └── Prefs.kt · BootReceiver.kt
└── res/layout/widget_card.xml · res/xml/widget_info.xml
```

## Free-tier usage

| | Per day | Free limit |
|---|---|---|
| Worker requests | ~4,000 | 100,000 |
| D1 rows written | ~6,000 | 100,000 |
| D1 rows read | ~10,000 | 5,000,000 |
| Spotify API calls | ~6,000 | ~180/min |
