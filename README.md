# Curbox

Curbox is an Android utility for reducing compulsive phone use. It interposes on
app launches and on-screen content through Android's accessibility framework,
and pairs the blocking mechanisms with self-binding controls that resist being
disabled in a moment of weakness.

This is a fork of [nethical6/curbox](https://github.com/nethical6/curbox) with
substantial additions and reworks beyond upstream. It is licensed under
[GPL-3.0-or-later](LICENSE).

---

## Goals

- **Reduce friction-free distraction.** Make distracting apps and in-app
  surfaces (short-form video feeds, comment sections, infinite scroll) harder to
  reach than the deliberate act of reaching for them.
- **Be resistant to impulse, not just convenient.** Settings that the user might
  want to disable in the moment can be locked behind delays, cooldowns, or
  passwords the user themselves set up while motivated.
- **Stay private and offline.** The app holds no network permission. All data is
  stored on-device.
- **Be configurable, not opinionated.** Every blocker, schedule, and limit is
  user-defined. There is no built-in "correct" usage policy.

## Scope and non-goals

- Curbox targets **Android only** (API 26 / Android 8.0 and up).
- It is a **single-user, on-device** tool. There is no account, sync, server, or
  telemetry, and no network code.
- It is **not a parental-control or MDM product.** The locking mechanisms are
  designed for self-binding by a cooperating user, not for adversarial
  enforcement against a determined third party.

---

## Features

### Blockers

- **App blocker.** Block apps per user-defined group, either by a daily **usage
  limit** or by a **time schedule** (per-day intervals). Groups can auto-enroll
  newly installed apps, optionally kill the blocked app's background audio, and
  carry their own warning-screen configuration. A group can also be gated by an
  optional **geofence**: it only applies while the device is *inside* (or
  *outside*) a radius around a saved point. The point is captured from the
  current location or entered as latitude/longitude — there is no map, since the
  app has no network access. Location is read on-device only and never leaves the
  phone. A global option controls what geofenced groups do when the location is
  unavailable (keep blocking vs. let through), and that option can itself be
  locked behind an Anti-Modifications group.
- **Focus mode.** Group-based focus sessions in two modes — *block selected
  apps* or *block everything except selected apps*. Supports manual sessions and
  **automatic scheduled** sessions (per-day intervals), optional do-not-disturb
  activation, exit cooldowns, and an exit ETA shown on the active session card.
- **Reel blocker.** Blocks short-form video feeds (Reels/Shorts/TikTok-style
  surfaces) by time schedule, usage limit, or a per-day **reel count**.
- **Keyword blocker.** Blocks the screen when user-defined keywords appear,
  optionally searching the view tree recursively, with a configurable redirect
  URL and per-app ignore list.
- **View blocker.** A general accessibility-node rule engine that covers or
  dismisses specific on-screen elements (e.g. the like button, comments, a
  recommendation shelf). Rules are expressed in a NodeMatcher DSL
  (`type:value;…`, pipe-separated alternatives) matching on view id, text,
  content description, class, regex, presence/absence of sibling nodes, and
  layout regions. An on-device **element picker** helps author rules against a
  live screen. Each rule chooses an action (overlay cover or Back press) and can
  exclude regions from a covered layout. See
  [`view_blocker_rule.md`](view_blocker_rule.md) for the rule format.
- **Browser blocker.** Keyword/URL interception within browsers.

### Anti-stimulant ("make it boring") tools

- **Mindful messages.** Overlays a configurable reminder on selected apps, with
  placeholders for live session duration, today's app usage, and today's screen
  time.
- **Reel counter.** Counts short-form videos scrolled per day.

### Anti-circumvention (self-binding)

All locking systems share three unlock modes: **password**, **timed** (locked
until a chosen timestamp), and **cooldown** (a waiting period that must elapse
after a removal is requested).

- **Anti-uninstall.** Uses a device-admin receiver plus accessibility
  interception to block routes to uninstall / app-info / device-admin /
  accessibility settings while active.
- **Anti-modifications.** Locks specific blocker configurations — app-pause
  schedules, auto-focus schedules, keywords, view-blocker rules, and the
  essential-apps list — so they cannot be weakened without passing the unlock
  challenge. Modeled as independent groups, each with its own unlock mode and
  its own protected items. *Tightening* (adding items or groups) is always free;
  *loosening* (removing items, deleting groups) is gated. Whole-feature master
  toggles refuse to turn off while any item in their domain is locked, closing
  the obvious bypass.
- **Essential apps.** A never-blocked allowlist (launcher, keyboard, system UI,
  and Curbox itself) plus a user-customizable list, so the blocking mechanisms
  cannot lock the user out of the device. The essential list is itself
  lockable.

### Tracking and analytics

- **Usage statistics** via the system usage-stats API and a dedicated
  accessibility service, with a per-app ignore list.
- **On-device time-series logging** (Room): reel counts, scroll patterns, focus
  sessions, website usage, intent-statement logs, and a blocked-app log viewable
  under Reducer Analytics.
- **Charts** rendered with MPAndroidChart.
- **Home-screen widgets** for reels-scrolled-today and screen time.

### Warning screen

The screen shown when a blocked surface is opened is highly configurable:
custom message, cooldown interval, proceed delay, optional proceed limit within
a time window, optional unlock requirements (scan a QR code, type a sentence, or
state an intent before continuing), and vibrate/brightness cues. It can also be
hidden entirely to perform the Back/Home action immediately.

### Backup and restore

Versioned JSON backup/restore over the Storage Access Framework. The format
uses an envelope with a schema version and per-section versions, a registry of
backup sections (adding a persisted area is a one-line registration), and
forward/backward-compatible handling of unknown or absent sections. Lock state
is redacted on export and import is refused while locks are active.

---

## Architecture

**Language / UI.** Kotlin, single-module Android app. View-based UI
(AppCompat + Material + Fragments), one launcher activity (`FragmentActivity`)
hosting onboarding and the main reducer/usage/focus fragment tree.

**SDK levels.** `minSdk 26`, `compile/targetSdk 34`. Build flavors append
`-fdroid` / `-playstore` version suffixes.

### Processes and system services

| Component | Type | Role |
|---|---|---|
| `AppBlockerService` | AccessibilityService (own `:app_blocker_service` process) | Runs all interception blockers |
| `UsageTrackingService` | AccessibilityService | Foreground-app/usage tracking |
| `MediaNotifSilencer` | NotificationListenerService | Silences background audio of blocked apps (opt-in) |
| `AdminReceiver` | DeviceAdminReceiver | Backs anti-uninstall |
| `PackageInstallReceiver` | BroadcastReceiver (runtime-registered) | Auto-enrolls newly installed apps into opted-in groups |

The app holds **no `INTERNET` permission.** Key permissions are accessibility
(two services), `SYSTEM_ALERT_WINDOW` (overlays/warning screen),
`PACKAGE_USAGE_STATS`, `POST_NOTIFICATIONS`, device admin (optional),
notification-listener (optional), and `QUERY_ALL_PACKAGES` (to enumerate
installable targets).

### Event pipeline

`AppBlockerService` is the hot path. To keep accessibility-event handling
responsive it splits work in two:

- **Inline (synchronous):** latency-sensitive blockers that must act before the
  user sees the blocked surface — app blocker, grayscale filter, focus mode,
  anti-uninstall — run directly in `onAccessibilityEvent`.
- **Offloaded (asynchronous):** heavier full-tree content scanners — reel
  blocker, keyword blocker, view blocker — receive events over a **conflated
  channel** drained by a background coroutine worker, so a slow scan never
  stalls event delivery. Events are copied into the channel and recycled after
  processing.

Blockers extend a common `BaseBlocker` / `BaseBlockingService` providing shared
helpers (delay/debounce gating, and global actions including a
`pressBackThenHome` that pops a blocked activity off its host task's back stack
before going Home).

### Data and persistence

- **Configuration:** a single `Settings` data class persisted via Jetpack
  **DataStore**. It aggregates all blocker groups, focus groups, grayscale
  groups, blocker configs, anti-uninstall/anti-modifications config, and the
  custom essential-apps list.
- **Time-series data:** **Room** database `curbox_db` (version 6) with DAOs for
  reel stats, scroll patterns, focus stats, website stats, intent logs, and
  blocked-app logs.
- **Backup:** registry-based, versioned JSON over SAF (see above).

### Source layout

```
app/src/main/java/neth/iecal/curbox/
├── services/        accessibility + notification-listener services, event pipeline
├── blockers/        AppBlocker, FocusModeBlocker, ReelBlocker, KeywordBlocker,
│   └── viewblocker/    BrowserBlocker, AntiUninstallBlocker; view-blocker rule engine + picker
├── anti_stimulants/ grayscale filter, mindful-message tracker
├── trackers/        reel-count and website-usage trackers
├── data/
│   ├── models/         DataStore-backed config data classes
│   └── db/             Room entities + DAOs
├── receivers/       device-admin + package-install receivers
├── utils/           essential packages,
│   └── backup/         hashing, usage stats, permissions; backup section registry
├── ui/              activities, fragments (onboarding + main), overlays, widgets
└── hardcoded/       built-in reel-app definitions
```

---

## Building

Standard Gradle Android build:

```sh
./gradlew assembleFdroidDebug      # or assemblePlaystoreDebug
```

CI publishes the `fdroidDebug` APK as a pre-release on every push.

---

## Permissions and safety

Curbox functions almost entirely through accessibility services and therefore
reads on-screen content to decide what to block. Because of the sensitivity of
those permissions, install only from a source you trust. On Android 13+ the
"restricted settings" toggle must be allowed before the accessibility services
can be enabled.

The app contains no network code and requests no `INTERNET` permission; nothing
it observes leaves the device.

---

## Credits

This fork builds on [nethical6/curbox](https://github.com/nethical6/curbox).
Upstream and its dependencies in turn credit:

- [Usage Direct](https://codeberg.org/fynngodau/usageDirect) — app usage stats.
- [Redd Focus](https://github.com/kasnder/redd-focus-android/) — basis of the
  view blocker.
- [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) — charts.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). You may use, modify, and distribute
this software under the terms of that license.
