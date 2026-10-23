# CallShield

A whitelist-first call blocker for Android 10+ (API 29+), built as "KnownCalls, but more advanced": by default, **only numbers in your Contacts can ring you**. Everything else is rejected silently by Android's `CallScreeningService` — plus a full rule system: profiles with schedules, emergency contacts, prefix/wildcard rules, time windows, SMS muting, statistics, an app lock, encrypted backup, and an optional opt-in online lookup layer.

**Privacy:** the blocking core is **fully offline** and sends nothing anywhere. The only network use is the optional online reputation layer (features 46–47), which is **off by default** and only ever sends a **SHA-256 hash** of a number to a Supabase project **you** configure.

- Working name: **CallShield** · Package: `com.mthtamim.callshield`
- Version: **1.1.1 (versionCode 5)** — the 47 features of v1.0.0, plus 12 more local-only features (48–59, below), plus a bottom-navigation home (5 tabs: Dashboard / Filtering / Smart Allow / Log / Settings) that replaces both the v1.0.0 long home and the short-lived v1.0.1 grouped menu. v1.1.1 is the on-device fix pass: the setup wizard now covers every grant, a cold start always opens Dashboard, the Log is a real tab page, duplicate screens are removed, and the Filtering/Settings tabs are descriptive menus with live status
- Full spec (Bangla): `~/workspace/your_files/advanced-call-blocker-feature-list.md` — all 59 features implemented or honestly marked (best-effort / plumbing-only)
- Manual (Bangla): `docs/manual-bn.md` · Feature report (Bangla): `docs/feature-report-bn.md` · Validation notes: `VALIDATION.md`

## The master rule

**Only Contacts ring.** Modes refine it:

- **CONTACTS_ONLY** (default) — Contacts ring, everyone else is rejected.
- **FAVORITES_ONLY** — stricter: only favourite Contacts ring.
- **WHITELIST_ONLY** — only the explicit in-app whitelist rings.

Above every mode sit, in order: your blacklist (always wins, even over emergency contacts), emergency contacts, the built-in hotline/short-code list, prefix and wildcard rules, the international block, per-number time windows, contact-group allows, and then the mode itself — refined by Smart Allow (temporary allow, expected calls, repeat-caller bypass, one-shot "allow next call", hidden-number handling) and, finally, the active profile.

## Profiles & scheduling

Five profiles — **Normal, Strict, Focus, Night, Off** — each with its own mode and hidden-number rule. Schedules switch profiles by time and weekday (evaluated live at call time; an AlarmManager alarm keeps the tile, widget and summaries in sync — exact when the phone allows it, inexact otherwise; decisions never depend on the alarm). Emergency contacts bypass **every** profile and mode. Quick Settings tiles (profile cycle, Temporary Allow) and a home-screen widget give one-tap control. Per-SIM rules are **best-effort only**: Android's screening API does not reveal which SIM an incoming call arrived on, so the SIM screen stores a preference per SIM account but screening always falls back to the global profile.

## SMS

Android gives third-party apps no way to truly block an SMS — CallShield **mutes** instead: a notification listener silences the default SMS app's notifications for non-contact senders (muted messages land in an in-app silent inbox). OTP and banking/transactional messages are **never** muted, and custom keyword rules add extra muting.

## Insights & safety nets

Full call log with search, filters and CSV export; statistics (totals, trend, top blocked numbers); daily/weekly summary notifications; heuristic warnings for rapid-fire and persistent repeat callers (shown even when a call is allowed); silence tracking for blocked numbers; abuse caps on the repeat-caller bypass. A rule **test mode** shows ALLOW/BLOCK + reason for any number without a real call, and **smart suggestions** (frequently dialled numbers, repeat bypass users) are suggest-only — nothing is ever whitelisted automatically.

## Security

Optional app lock (salted SHA-256 PIN hash + biometric unlock) gates the app's screens; blocking keeps working while locked. Lists and settings live in app-private storage; the backup file is **AES-GCM encrypted** with an Android Keystore key and exported/imported through the system file picker. The local database itself is **not** separately encrypted — stated plainly, not hidden.

## What's new in v1.2.0

- **Full visual redesign — no feature changes.** Every screen now uses the approved reference design language: a deep-navy dark theme (`#0A1120` background, `#131C2E` cards with a 1dp `#1E2A44` border, 19dp radius) with emerald `#22C55E` as the single accent, plus a matching light theme (`#F2F5FA` / white cards). All 59 features, every screen's controls and the database are exactly as in v1.1.1 — only the look moved.
- **Dashboard hero card** — the status card is now a green-to-blue gradient hero: shield-in-ring graphic, "Shield active / off" with an ON/OFF pill, then the same master switch, status, role and profile lines. The app name is a two-tone "Call**Shield**" header.
- **Coloured icon tiles everywhere** — stat tiles carry tinted icons (red block, amber warning, green check) over big coloured numbers; the Filtering/Settings menu rows lead with a 40dp tinted icon tile instead of a plain dot; the dashboard's recent-block rows are cards with a red block tile.
- **Lists & Log rows are cards** — entries sit on individual bordered cards, and the Lists screen's Add button is a tinted outline that recolours per section (green whitelist, red blacklist, blue prefixes), like the reference design.
- **Bottom bar** — deep-navy bar; the active tab is accent-coloured, bold, and marked with a small accent dot under its label.
- **Wizard progress bar** — the setup wizard's step card is restyled and its "step x of y" text gains a progress bar.
- This release also carries everything from v1.1.1 (wizard covering all grants, cold-start Dashboard, Log as a tab page, dedupe, the new launcher icon) — v1.1.1 was never pushed separately.

## What's new in v1.1.1

- **New app icon** — a minimal two-colour launcher icon (emerald shield outline with a white handset on white), chosen by Tamim; shipped as PNG mipmaps plus an adaptive icon, replacing the old vector foreground.
- **Wizard covers every grant** — all eleven grants the Permissions screen lists now get their own wizard step with a one-line reason and live status (role, contacts, call log, phone state, SMS read, notifications, notification access, battery exemption, exact alarms), plus a clearly tagged optional section for SEND_SMS (auto-SMS replies) and READ_CALENDAR (calendar Focus), which are otherwise only requested when their feature is enabled.
- **Cold start = Dashboard** — the app no longer reopens on the last-used tab. The current tab survives configuration changes only (a process-lifetime holder); an explicit deep link can still open the Log page directly.
- **Log is a tab page** — the full log (search, filter, entry actions with the callback guard, CSV export) now lives on the Log tab inside the main screen; the bottom bar never disappears. The separate Log screen is gone.
- **Duplicates removed** — the Battery guide screen is folded into the Permissions screen (its live status lines duplicated the Permissions cards; the OEM/XOS steps are now a card at the bottom of Permissions). One home per control; see VALIDATION.md for the full dedupe map.
- **Menus that explain themselves** — the Filtering and Settings tabs are now compact menu rows in one card each: title, a one-line description of what the feature does, and a live status (list counts, active profile, total blocked, missing grants, …), refreshed every time the tab is shown.

## What's new in v1.1.0

- **Bottom navigation home** — five tabs (Dashboard / Filtering / Smart Allow / Log / Settings). The Smart Allow controls live on their own tab page (the separate Smart Allow screen is gone; no duplicates). The Log tab opened the separate log screen (v1.1.1: the log is a page of the tab itself). Back from any tab returns to Dashboard first.
- **Richer dashboard** — the Dashboard page now carries more than the status card: stat tiles (blocked today — moved out of the status card into the tile, so it shows once — blocked this week, and the last blocked number with its time), a quick-actions card (Allow 15 minutes / cancel temp allow, the allow-next toggle, and Strict / Normal / Open profile chips — all writing the same store the Smart Allow page and Profiles screen use), the last 3 blocked calls (tap the card to open the Log), and a protection check card (role, battery exemption, missing runtime permissions — computed live, tap to open Permissions). Everything is recomputed when the Dashboard is shown; no new permissions.
- **Per-rule block action (48)** — every profile, blacklist entry, prefix rule and wildcard rule can REJECT (default), SILENCE (voicemail, no ring) or WARN_ONLY (rings, with a warning).
- **Auto-SMS reply (49)** — your own text sent to rejected numbers, off by default, at most once per number per day, from your own SIM (normal SMS charges apply).
- **Softer while the screen is on (50)** — optional: blocks become warnings while you are using the phone.
- **Post-call quick decision (51)** — after an allowed unknown call ends unanswered, one notification offers Whitelist / Block / Dismiss (once per number per day).
- **SMS correspondents count as known (52)** — optional: numbers you exchange SMS with are allowed.
- **Wangiri / one-ring detection (53)** — repeated one-ring attempts from the app's own log earn a distinct warning; no external data.
- **Calendar Focus (54)** — optional: an ongoing event on a calendar you pick applies the Focus profile.
- **Urgent missed-call reminder (55)** — a missed repeat-bypass call reminds you about 15 minutes later (once per number per day).
- **Widget (56)** — now also shows blocked-today count and the most recent blocked number, refreshed the moment a block happens.
- **Log auto-clean (57)** — optionally keep only 30 or 90 days of the internal log.
- **Silent notification mode (58)** — no per-block notifications; the daily summary reports the total.
- **Callback guard (59)** — calling back a blocked number from the log asks for confirmation first.

## Build

Open in Android Studio, or push to GitHub and let `.github/workflows/build-apk.yml` build the debug APK (it also publishes release **v1.1.1** with the APK attached).
Toolchain: AGP 8.5.2 · Kotlin 1.9.24 · Gradle 8.7 · compileSdk/targetSdk 34 · minSdk 29.
Dependencies: AndroidX appcompat/core-ktx + `androidx.biometric:biometric:1.1.0` (app lock only).

## Permissions (12)

| Permission | Why |
|---|---|
| `READ_CONTACTS` | The whitelist itself + contact groups |
| `READ_CALL_LOG` | Repeat-caller bypass, statistics, suggestions |
| `POST_NOTIFICATIONS` | Blocked-call, escalation, warning and summary notifications |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep screening alive on aggressive OEM skins (XOS etc.) |
| `INTERNET` | **Only** the optional online layer (46–47), off by default |
| `READ_SMS` | Silent SMS inbox preview + mute preview (optional) |
| `RECEIVE_BOOT_COMPLETED` | Re-arm schedule/summary alarms after reboot |
| `SCHEDULE_EXACT_ALARM` | Precise schedule/summary timing where granted |
| `USE_BIOMETRIC` | App-lock biometric unlock |
| `READ_PHONE_STATE` | Listing SIM accounts for the best-effort per-SIM screen |
| `SEND_SMS` | **Only** the auto-SMS reply (49), off by default; requested only when you enable it; replies go from your own SIM at your own cost |
| `READ_CALENDAR` | **Only** calendar-based Focus (54), off by default; requested only when you enable it |

The Caller ID & spam **role** (not a permission) is what lets Android route calls to the screening service; the setup wizard walks through it.

## Honest limits

- No Truecaller-style name database in the offline core — unknown numbers are shown as numbers. (The optional online layer can *suggest* a name from your own backend.)
- WhatsApp/Messenger/VoIP calls cannot be screened — Android exposes only carrier calls.
- SMS cannot be truly blocked by a third-party app — only muted, as above.
- Per-SIM screening is not possible through the public API — best-effort preference only.
- App lock gates the app's screens, not the blocking engine (blocking must keep working).
- Backup files decrypt only while the same device Keystore key survives (factory reset / key invalidation loses them).
- Features 46–47 are plumbing only: no real backend exists yet, so lookups will report "not configured / unreachable" until you create the Supabase table described in `docs/manual-bn.md`.
- Wangiri detection (53) is a heuristic over the app's own log — repeated short-interval attempts; Android exposes no reliable ring duration to a screening app, so no duration claim is made.
- Post-call detection (51, 55) polls the call log on a short alarm (~90 s steps, 10-minute fallback); without READ_CALL_LOG it falls back to treating the call as ended after the window.
