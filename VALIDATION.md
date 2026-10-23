# CallShield Part 1 — Local Validation Report

Version: v0.1.0-part1 (versionCode 1) · Date: 2026-10-23
Toolchain mirrored from PhoneLink: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7 (setup-gradle), JDK 17, compileSdk/targetSdk 34, minSdk 29.

## Checked locally — all passed

- **XML well-formedness:** all 21 XML files parse (manifest, 8 layouts, item row, values/values-night, drawables, mipmap adaptive icons).
- **Dotted-style parent rule:** every `<style>` whose name contains a dot (`Theme.CallShield`, `CS.Card`, `CS.Heading`, `CS.Body`, `CS.Caption`, `CS.Button`, `CS.ButtonSecondary`) carries an explicit `parent` attribute — the PhoneLink build #50 AAPT trap.
- **String parity:** EN = 169 keys, BN = 169 keys, identical key sets; format placeholders (%1$d / %1$s / %2$d) match between locales for every key.
- **Apostrophe/quote scan:** no unescaped apostrophes or quotes in any EN or BN string (all copy avoids apostrophes entirely).
- **Resource references:** every `@string/@color/@drawable/@style/@layout/@mipmap` reference in layouts resolves to a definition; colors referenced by layouts exist in both `values` and `values-night`.
- **Kotlin references:** every `R.string.*`, `R.layout.*`, `R.id.*`, `R.drawable.*` used in code exists (framework `android.R.*` items excluded — they are platform resources).
- **Imports both ways:** a project symbol index (28 classes/objects/enums/data classes) was built from source; every project import resolves to a declared symbol, no file uses a cross-package project type without importing it, and no unused imports were found.
- **Manifest components:** all 10 declared components (7 activities, the call-screening service, the notification-action receiver) resolve to real Kotlin classes. Permissions are declared once each: READ_CONTACTS, READ_CALL_LOG, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
- **Feature 37 (offline core):** the manifest declares NO internet permission and no network-state permission.
- **Gradle flags:** `android.useAndroidX=true` and `android.nonTransitiveRClass=true` are consistent with the single AndroidX dependency (appcompat 1.7.0) plus kotlin-stdlib 1.9.24 — the same versions PhoneLink builds with.
- **Settings repositories:** Google Maven Central mirror is first in `settings.gradle`, mavenCentral() as fallback (the repo.maven.apache.org 403 lesson from PhoneLink v2.18.0).

## Hand-reviewed compile risks (no compiler available locally)

- `CallScreeningService.onScreenCall` is overridden with the exact parameter type `android.telecom.Call.Details`, and `CallResponse` is imported from `CallScreeningService` — CORRECTION (CI run 1, 2026-10-23): an earlier note here claimed the opposite; the official API reference shows `onScreenCall(Call.Details)`, and the first CI build failed with "Unresolved reference: CallDetails" because the code imported the non-existent `CallScreeningService.CallDetails`. Fixed in the v1.0.0 fix commit.
- `ContactsHelper` reads PhoneLookup columns by their provider-contract column names ("contact_id", "display_name") instead of relying on nested-interface constants.
- AppCompat night-mode is set in `BaseActivity.onCreate` after `super.onCreate` and before any `setContentView` call.

## NOT checkable locally

- **Kotlin compilation** and **AAPT resource linking** can only run in CI (GitHub Actions). This report does not claim CI will pass; it records that every locally-checkable class of error from the PhoneLink history has been scanned for.
- The pinned appcompat 1.7.0 AAR itself could not be inspected locally; only public, long-stable appcompat APIs are used (AppCompatActivity, AppCompatDelegate, AlertDialog, ActivityResultContracts via androidx.activity, ContextCompat via androidx.core — both are appcompat transitives that PhoneLink already builds against).
- On-device behaviour (role grant flow, XOS battery behaviour, actual call rejection) requires Tamim's phone.

---

# CallShield Part 2 — Local Validation Report (v1.0.0, versionCode 2)

Date: 2026-10-23 · Validator: `/tmp/validate_p2.py` (same battery as Part 1, extended for the new files) · **Result: 0 errors, 0 warnings.**

## Before → after counts (preservation proof)

| Metric | Part 1 | Part 2 (now) |
|---|---|---|
| Kotlin files | 18 | **42** |
| Layouts | 8 | **19** |
| Strings EN | 169 | **366** |
| Strings BN | 169 | **366** |
| Manifest permissions | 4 | **10** (6 added, see below) |
| Manifest components | 9 | **26** |
| Features | 19/47 | **47/47 mapped** |

Part 1 compatibility: no Part 1 table was altered (DB v1 → v2 migration only **adds** tables in `onUpgrade`); Part 1 preference keys are unchanged (the Normal profile reads/writes the original Part 1 keys); the Part 1 decision order is preserved, with Part 2 layers inserted around it (exact-number blacklist still outranks everything; exact whitelist/blacklist still outrank wildcard rules; blocked log → notification → escalation flow untouched).

## Checks run and passed

1. **All 33 XML files parse** (manifest, 19 layouts, values/values-bn strings, colors, themes, widget info, file paths, adaptive icon): `xml.etree` strict parse, 0 errors.
2. **Dotted-style parent scan** (the PhoneLink AAPT trap): no new styles were added in Part 2; scan across all `res/values*` files reports no dotted style without an explicit `parent`. Rules screens reuse `Widget.CallShield.*` styles only.
3. **String parity:** EN = BN = **366 keys**, key sets identical, per-key `%1$s/%1$d` placeholder multisets identical in both locales.
4. **Apostrophe/quote scan:** no unescaped `'` or `"` in any string value in either locale.
5. **Reference resolution:** every `R.string/layout/drawable/xml/id` reference in all 42 Kotlin files resolves; every `@string/@color/@drawable/@style/@layout/@xml` reference in all 19 layouts + `widget_info.xml` resolves; no hardcoded `#RRGGBB` colours in layouts.
6. **Import↔use check:** project symbol index built from all Kotlin declarations (71 types); every cross-package type used is imported; no unused project imports. (Fully-qualified usages in `Backup.kt`/`TestModeActivity.kt` were converted to proper imports during this pass.)
7. **Manifest components:** all 26 declared components (activities, screening service, SMS listener service, 2 tile services, widget provider, 3 receivers) resolve to real Kotlin classes in the right packages.
8. **Permissions:** exactly 10, each declared once. New in Part 2 (6): `INTERNET` (optional online layer only, default off), `READ_SMS` (silent inbox preview), `RECEIVE_BOOT_COMPLETED` (re-arm alarms), `SCHEDULE_EXACT_ALARM` (schedule/summary precision), `USE_BIOMETRIC` (app lock), `READ_PHONE_STATE` (SIM account listing). Each is justified in README's permission table.
9. **Gradle/workflow:** `versionCode 2`, `versionName "1.0.0"`; release workflow tag/name **v1.0.0**; `android.useAndroidX=true` (required by appcompat + biometric); exactly one new dependency: `androidx.biometric:biometric:1.1.0`.
10. **New-database safety:** `DB_VERSION = 2`; `onUpgrade` creates only the five new tables (`schedules`, `adv_rules`, `hotlines`, `keywords`, `sms_log`) and never touches the four Part 1 tables. Emergency contacts reuse `list_entries` with a new type value (2), no schema change needed.
11. **Lint-ish scans:** no TODO/FIXME left in Kotlin sources; no emoji in Kotlin or layouts; crude brace balance per file; no `break`/`continue` inside inline `run {}` blocks.
12. **Stale-text grep:** `0.1.0-part1` survives only as the historical Part 1 label in the feature report's header line and in Part 1's own section above (both intentional history); every version field, workflow tag, README and manual now reads v1.0.0, and the feature report maps all 47 items.

## Specifically re-audited by hand (Part 2 risk points)

- `Call.Details` used for facts — the `onScreenCall` parameter type per the official API (see the Part 1 correction above). Per-SIM: the API exposes **no** SIM/phone-account for incoming calls — confirmed by API surface; the per-SIM screen therefore documents best-effort only and the screening service gathers no SIM fact.
- Notification-listener: only the default SMS package's notifications are considered; OTP/transactional guard sits **above** keyword and unknown-sender rules in the pure `SmsFilter`.
- Alarm precision: exact alarms only when `canScheduleExactAlarms()` is true (Android 12+), inexact fallback otherwise; screening decisions are evaluated live in the service and never depend on the alarms.
- App lock gates only `MainActivity` entry (5-minute in-memory grace); the screening service is not gated — blocking continues while locked.
- Backup crypto: AES/GCM/NoPadding, 12-byte random IV prepended, Keystore key alias `callshield_backup_key`; call log deliberately excluded from export.

## What could NOT be checked locally (explicit)

- **Kotlin compilation** and **AAPT resource linking** — no Android SDK/Gradle in this environment; CI (GitHub Actions) is the first real compile, exactly as in Part 1.
- **The pinned `androidx.biometric:biometric:1.1.0` AAR** could not be downloaded/inspected here; the API surface used (`BiometricPrompt`, `BiometricManager.canAuthenticate(int)`, `PromptInfo.Builder.setAllowedAuthenticators`, `Authenticators.BIOMETRIC_STRONG`) is the stable, documented 1.1.0 surface, but this is asserted from documentation, not from the artifact.
- **On-device behaviour is untested**: role grant flow, tile/widget updates, notification-listener suppression on the real SMS app, schedule boundaries at midnight/overnight windows, heuristic thresholds with a real call log, and XOS battery behaviour all need Tamim's phone.
- **Features 46–47 are plumbing-only-unverified by design**: no Supabase backend exists yet, so the REST lookup path has never run against a real server.

---

# CallShield v1.0.1 — Local Validation Report (menu-based home)

Date: 2026-10-23 · Version: **v1.0.1 (versionCode 3)** · Validator: `/tmp/validate_v101.py` (same battery as Part 1/2) · **Result: 0 errors, 0 warnings.**

## What changed (and only this)

- `activity_main.xml` restructured to a menu: header + ONE compact status card + 3 grouped menu cards (Protection / Tools / Setup) with the 15 navigation buttons in two-column rows. **287 → 228 lines**; vertical space roughly halved (7 button rows instead of 15 stacked buttons, one whole card removed). Same styles/drawables/themes — arrangement only.
- The Smart Allow quick card was removed from home. Its controls already existed on the Smart Allow screen (temp 15/30/60 start, cancel, status text, expected call) — verified line by line against `SmartAllowActivity`. One gap was closed there: allow-next arming on home was one-tap (immediate `setAllowNext(true)`), while the Smart Allow screen needed switch + Save; `switchAllowNext` now applies immediately on toggle (Save still writes the same value).
- `MainActivity.kt`: removed the temp/allow-next fields, handlers, `startTemp()` and the two refresh blocks (181 → 151 lines); the now-unused `SimpleDateFormat`/`Date`/`Locale` imports went with them.

## Counts before → after

| Metric | v1.0.0 | v1.0.1 |
|---|---|---|
| Strings EN / BN | 366 / 366 | **369 / 369** (+3 group headers: `menu_group_protection/tools/setup`) |
| activity_main.xml | 287 lines | **228 lines** |
| MainActivity.kt | 181 lines | **151 lines** |
| Kotlin files / layouts / XML | 42 / 19 / 33 | 42 / 19 / 33 (unchanged) |

## Home ids — removed vs kept

- **Removed from home:** `btnTemp15`, `btnTemp30`, `btnTemp60`, `textTempStatus`, `btnAllowNext`, `textAllowNextStatus`. Behaviour now: temp start/cancel + temp status on the Smart Allow screen (`btnT15/T30/T60`, `btnTempCancel`, `textTempStatus2`); allow-next arm/status on the same screen (`switchAllowNext`, now immediate).
- **Kept with identical ids and behaviour:** `switchMaster`, `textStatus`, `textRoleStatus`, `textProfile`, `textBlockedToday`, and all 15 navigation buttons (`btnLists`, `btnLog`, `btnSmart`, `btnProfiles`, `btnRules`, `btnSms`, `btnStats`, `btnSuggestions`, `btnTest`, `btnSecurity`, `btnBattery`, `btnOnline`, `btnSettings`, `btnPermissions`, `btnWizard`) — their click handlers are byte-identical.

## Checks run and passed

- All **33 XML** files parse; dotted-style parent scan clean (7 styles).
- **EN = BN = 369** keys, identical key sets, placeholders consistent per key; apostrophe/quote scan clean in both locales.
- Every `R.*` reference in all 42 Kotlin files resolves; every layout `@string/@color/@style/@layout/@drawable/@mipmap` reference resolves; every id used by `MainActivity` exists in the new `activity_main.xml`, and every id used by `SmartAllowActivity` exists in `activity_smart_allow.xml`.
- **Preservation proof (md5 over all Kotlin sources + manifest):** exactly two Kotlin files changed — `ui/MainActivity.kt` and `ui/SmartAllowActivity.kt`. `AndroidManifest.xml` is byte-identical; engine, screening, data, security, notify, sms, tile, widget, online and contacts code is untouched.
- Workflow file updated in the same pass: release step now publishes tag **v1.0.1** / "CallShield v1.0.1" with matching notes.

## What could NOT be checked locally (explicit)

- **Kotlin compilation and AAPT linking remain CI-only**, exactly as before — this report does not claim CI will pass.
- On-device the new home should fit one typical screen with at most minimal scroll; that is a layout-size judgement from the structure (status card + 7 menu rows), not a device measurement.
- **Tamim must re-upload the updated `.github/workflows/build-apk.yml` himself** (the connector cannot push workflow files — same as v1.0.0); until he does, a push would still publish to the old v1.0.0 tag.

---

# v1.1.0 (versionCode 4) — 12 features (48–59) + bottom-navigation home

Date: 2026-10-23. Two changes in one pass: the 12 adopted local-only
features, and Tamim's design correction replacing the v1.0.1 grouped-menu
home with a bottom navigation bar (5 tabs). The v1.0.1 home shipped in no
release; v1.1.0 supersedes it.

## Counts (before → after)

| Metric | v1.0.1 | v1.1.0 |
|---|---|---|
| Strings EN / BN | 369 / 369 | **418 / 418** (+51 feature strings; then −5 removed `menu_group_*`/`nav_smart`/`nav_log`, +3 tab labels `tab_dashboard/filtering/log`) |
| Kotlin files | 42 | **45** (+4 new: `engine/Wangiri.kt`, `calendar/CalendarHelper.kt`, `notify/AutoSms.kt`, `notify/PostCall.kt`; −1 `SmartAllowActivity.kt` removed) |
| Layouts | 19 | **18** (−`activity_smart_allow.xml`, folded into `activity_main.xml`) |
| XML total | 33 | **32** |
| Total project files | 86 | **88** |
| Manifest permissions | 10 | **12** (+`SEND_SMS`, +`READ_CALENDAR`, each declared once, lazy-requested) |
| Manifest components | 26 (17 act / 4 svc / 5 rcv) | **26 (16 act / 4 svc / 6 rcv)** (−SmartAllowActivity, +PostCallReceiver) |
| SQLite schema | v2 | **v3** (additive only: `block_action` column on `list_entries`, `prefix_rules`, `adv_rules`) |
| activity_main.xml | 228 lines | **715 lines** (4 pages + bottom bar) |
| MainActivity.kt | 151 lines | **461 lines** (tabs + folded Smart Allow) |

## Home restructure — preservation

- Dashboard page keeps the status card **exactly**: same ids
  (`switchMaster`, `textStatus`, `textRoleStatus`, `textProfile`,
  `textBlockedToday`) and the same `refresh()` logic.
- All 13 surviving navigation buttons keep their ids and handlers:
  Filtering page — `btnLists`, `btnProfiles`, `btnRules`, `btnSms`;
  Settings page — `btnStats`, `btnSuggestions`, `btnTest`, `btnSecurity`,
  `btnBattery`, `btnOnline`, `btnSettings`, `btnPermissions`, `btnWizard`.
- Removed ids: `btnSmart` and `btnLog` (replaced by the Smart Allow and
  Log tabs) and the three group-header TextViews.
- Smart Allow page = the former SmartAllowActivity layout and logic moved
  verbatim (all control ids unchanged: `textTempStatus2`, `btnT15/T30/T60`,
  `btnTempCancel`, `switchAllowNext`, `editExpected`, `textExpectedStatus`,
  `btnExpectedSet/Clear`, `spinnerCallback`, `switchRepeat`,
  `spinnerRepeatCount/Window`, `btnSaveSmart`, plus the v1.1 cards
  `switchAutoSms`, `editAutoSmsText`, `switchSmsKnown`). Manifest activity
  entries: 17 → 16; no other reference to SmartAllowActivity remains
  (grep-verified across Kotlin, layouts and the manifest).
- Log tab launches LogActivity (single home for the log; not duplicated).
- Back: any tab → Dashboard; Dashboard → exit (OnBackPressedCallback).

## Feature implementation notes (48–59)

- Engine: `Decision` gained an `action` field (`RuleAction` REJECT /
  SILENCE / WARN_ONLY, default REJECT); `decide()` wraps the original
  chain in `decideBase()` + post-processing (per-rule action conversion,
  screen-on softening, wangiri relabel). TestModeActivity still compiles
  against the same named-argument surface (all new fields defaulted).
- Store: per-profile actions follow the existing profileMode/profileHidden
  storage pattern; throttles mirror `claimEscalation`; post-call marker,
  silent-mode counters, log purge and wangiri attempt probe added.
- Screening service: silenced blocks answer `setSilenceCall(true)` with
  `setSkipNotification(true)`; auto-SMS fires only on the REJECT branch;
  widget refresh rides the shared blocked-call path; purge runs after
  each screened call.
- Backup (feature 40) extended in the same pass: per-rule block actions
  and the v1.1 settings are exported/restored; format version unchanged,
  all new keys optional on restore (Part 2 backups still restore).

## Checks run and passed

- All **32 XML** files parse; dotted-style parent scan clean.
- **EN = BN = 418** keys, identical key sets; per-key format placeholders
  match; apostrophe scan clean (EN copy still avoids apostrophes).
- Every `R.string` / `R.id` / `R.layout` reference in all 45 Kotlin files
  resolves (framework `android.R.*` excluded); manifest components all
  resolve to real classes; 12 permissions declared exactly once each.
- Import↔use heuristic: no unused imports in new/edited files.
- `versionCode 4` / `versionName "1.1.0"`; DB_VERSION 3 with an
  onUpgrade path that only adds columns (v1/v2 rows and columns untouched).
- Workflow file (staged copy) updated in the same pass: release step now
  publishes tag **v1.1.0** / "CallShield v1.1.0" with matching notes.

## What could NOT be checked locally (explicit)

- **Kotlin compilation and AAPT linking remain CI-only** — hand-reviewed
  API risks: `CallResponse.Builder.setSilenceCall` (API 29+, matches
  minSdk), `SmsManager` via the class-token `getSystemService` (API 23+;
  the send itself is wrapped in try/catch), `CalendarContract.Instances`
  URI building, `OnBackPressedCallback` (androidx.activity, an appcompat
  transitive PhoneLink already builds against).
- On-device behaviour is untested: silence → voicemail behaviour depends
  on carrier/OEM; auto-SMS delivery and charging; post-call alarm timing
  under XOS battery management; calendar Focus against real calendars;
  widget refresh on block; the bottom-bar navigation itself.
- **Tamim must re-upload the updated `.github/workflows/build-apk.yml`
  himself** (the connector cannot push workflow files); until he does, a
  push would still publish to the old v1.0.1 tag.

# v1.1.0 dashboard enrichments (same version, post-build — Tamim adopted all 4)

Four dashboard items added to the Dashboard tab page only, at Tamim's
request (the page was just the status card). No new permissions, no new
activities, version stays **versionCode 4 / versionName "1.1.0"**, DB v3
unchanged.

## What was added

1. **Stat tiles** — Blocked today / Blocked this week (since Monday
   00:00) / Last blocked (number + time). The "Blocked today" line was
   **removed from the status card** and the `textBlockedToday` id now
   sits on the tile value, so the number appears exactly once on the
   page. Sources: the internal call log only — new
   `Store.blockedCountSince(since)` (the old `blockedTodayCount` now
   delegates to it; the widget keeps calling it) and
   `Store.logEntries(100)` filtered to blocked rows.
2. **Quick actions** — "Allow 15 minutes" button (while a temp allow is
   active it shows the until-time and tapping cancels), an allow-next
   switch, and Strict / Normal / Open profile chips ("Open" = the OFF
   profile). All three write the **same** Store state the Smart Allow
   page and ProfilesActivity use (`startTempAllow`/`cancelTempAllow`,
   `setAllowNext`, `setActiveProfile` + the same TileUpdater /
   WidgetUpdater refresh ProfilesActivity runs) — no forked state; both
   allow-next switches re-sync from the store on every refresh.
3. **Recent blocks** — last 3 blocked log rows (number, time, reason
   label via the same `BlockActions.reasonLabelRes` the Log screen
   uses); tapping the card opens LogActivity, the Log tab's route.
4. **Protection check** — role held, battery exemption, and a
   missing-runtime-permissions count, computed live from `Perms` on
   every dashboard show; green/amber status dots (a tinted oval
   drawable, no emoji); tapping the card opens PermissionsActivity.
   The count covers the runtime permissions the Permissions screen
   offers (contacts, call log, notifications, phone state, SMS read,
   SMS send, calendar); the lazily requested ones stay optional — the
   count is a status readout, stated in a code comment.

Refresh points: `onResume` and every Dashboard tab show (`showPage`),
in addition to the existing state-change refreshes.

## Counts (before → after)

- Kotlin files: **45 → 45** (MainActivity and Store edited, none added)
- Layouts: **18 → 18** (activity_main.xml extended)
- XML total: **32 → 33** (+1 new drawable `dot_status.xml`)
- Strings: **EN 418 → 434, BN 418 → 434** (+16 `dash_*` keys, both locales)
- Permissions: **12 → 12** (unchanged); manifest components **26 → 26**
- Total files: **88 → 89**

## Checks run and passed

- All **33 XML** files parse; dotted-style parent scan clean.
- **EN = BN = 434** keys, identical key sets; per-key format
  placeholders match; unescaped-apostrophe scan clean.
- Every `R.string` / `R.id` / `R.layout` / `R.drawable` / `R.color`
  reference in all 45 Kotlin files resolves (framework `android.R.*`
  excluded); manifest components all resolve to real classes
  (including top-level classes declared inside other files);
  12 permissions declared exactly once each.
- Import↔use heuristic over the whole project: **0 unused imports**;
  brace balance clean in every Kotlin file; no labelled flow constructs
  in the edited files.
- Build config re-checked: `android.useAndroidX=true`, Kotlin 1.9.24,
  appcompat 1.7.0, compileSdk 34 / minSdk 29 — the new code uses only
  `Context.getColor` (API 23+) and `View.backgroundTintList` (API 21+),
  both covered by minSdk 29. No external library calls were added, so
  there is nothing new to inspect in a pinned artifact.
- Docs updated in the same pass: README (v1.1.0 "Richer dashboard"
  bullet), `docs/manual-bn.md` (Dashboard bullet), and
  `docs/feature-report-bn.md` (intro, item (গ)).

## What could NOT be checked locally (explicit)

- **Kotlin compilation and AAPT linking remain CI-only**, as with every
  earlier section of this report.
- On-device behaviour is untested: tile/week boundaries, chip
  highlighting against a schedule-driven effective profile (chips show
  and set the manual active profile, exactly like the Profiles radios;
  the status card still shows the effective one), dot tinting on XOS,
  and the dashboard's total scroll length on a small screen (four
  compact cards; target was ~1.3 screens).

## CI fix — v1.1.0 build #3–#6 (2026-10-23)

- First CI runs of v1.1.0 failed at `:app:compileDebugKotlin` with exactly
  two errors, both in `calendar/CalendarHelper.kt` (lines 55–56):
  "Unresolved reference: appendId". The code called
  `CalendarContract.Instances.appendId(builder, …)` — `Instances` has no
  such method; `appendId(Uri.Builder, long)` is a static method of
  `android.content.ContentUris` (the class the official Instances query
  sample uses). Fixed by importing `ContentUris` and calling
  `ContentUris.appendId(...)`. Whole project grepped: no other `appendId`
  call sites and no other `CalendarContract.Instances.` member uses beyond
  its real constants. Resources/AAPT and all other Kotlin compiled — the
  failure was confined to these two lines.
- Lesson (same family as the v1.0.0 CallDetails fix): a static helper that
  "belongs" to a contract class in memory may actually live on a different
  class entirely; hand-review must check the owning class, not just the
  method name.

## v1.1.1 — on-device fix pass (2026-10-23)

Tamim's phone verdict on v1.1.0 raised five complaints; each is fixed
here, with no new features and no new permissions. Version
**v1.1.1 / versionCode 5**; DB stays v3; workflow release tag v1.1.1.

### How each complaint was resolved

1. **Wizard did not ask for every permission.** WizardActivity is
   rewritten around a step table: all 11 grants the Permissions screen
   lists get a step with a one-line reason and a live status — role,
   READ_CONTACTS, READ_CALL_LOG, READ_PHONE_STATE (new), READ_SMS
   (new), POST_NOTIFICATIONS, notification access (new),
   battery-exemption intent, exact-alarm intent (new) — plus a
   clearly tagged OPTIONAL section (a "wizard_optional" tag line in
   the step card) for SEND_SMS (auto-SMS replies) and READ_CALENDAR
   (calendar Focus), which are otherwise only requested lazily on
   enable. 13 steps total (welcome + done frame the grants);
   already-granted steps show "Granted"; Next/Back unchanged.
2. **App opened on the Smart Allow tab.** The tab was restored from
   savedInstanceState, which survives process death. Now the start
   tab is: deep link (EXTRA_OPEN_LOG) if present, else a
   process-lifetime in-memory holder (companion `sessionPage`,
   default Dashboard). savedInstanceState is no longer written or
   read for the tab. A configuration change keeps the process, so
   the tab survives rotation only; a cold relaunch starts on
   Dashboard. Back behaviour unchanged.
3. **Log tab killed the navigation.** The Log is now a real page of
   MainActivity (pageLog in activity_main.xml) with the bottom bar
   always visible. LogActivity's functionality moved wholesale —
   search, filter spinner (all/blocked/allowed/warned), entry list
   with whitelist/blacklist/call-back (feature 59 guard kept
   verbatim)/SMS/delete, CSV export via CreateDocument, clear log —
   layout and logic reused, not copied into a second place.
   LogActivity + activity_log.xml + manifest entry deleted. The
   Dashboard recent-blocks card now switches to the Log tab instead
   of launching an activity. Grep found no other LogActivity
   callers (notifications/widget never referenced it); the
   EXTRA_OPEN_LOG deep link ("com.mthtamim.callshield.OPEN_LOG",
   also handled in onNewIntent) is the supported route in.
4. **Duplicates removed.** See the dedupe map below.
5. **Tab pages looked empty and ugly.** Filtering and Settings pages
   no longer use giant full-width buttons. Both pages now use one
   shared row component (styles CS.CardMenu / CS.MenuRow /
   CS.MenuDot / CS.MenuStatus): dot, bold title, a one-line
   description of what the feature does, and a LIVE status on the
   right, all inside a single card with hairline dividers. Values
   are pulled from Store/Perms every time the page is shown (and on
   resume): Lists = whitelist/blacklist/prefix counts; Profiles =
   effective profile; Rules = adv-rule + hotline counts; SMS =
   mute on/off; Stats = total blocked; Suggestions = count computed
   with SuggestionsActivity's own rules; Test = effective profile;
   Security = app-lock on/off; Online = on/off; Settings = block
   mode; Permissions = "All granted" / "N missing" across all 11
   grants; Wizard = completed / not completed. The Filtering page
   header carries a summary line (blocking state + active
   profile). Existing navigation ids (btnLists … btnWizard) are
   kept; their handlers moved from Button to View.

### Dedupe map (control → its ONE home)

| Control | One home | Duplicate removed |
|---|---|---|
| Call log (search/filter/actions/CSV/clear) | Log tab page in MainActivity | LogActivity (deleted) |
| Battery exemption status + fix | PermissionsActivity battery card | BatteryGuideActivity status lines; Settings-tab "Battery guide" row |
| Battery OEM/XOS steps (explanatory text) | Bottom card of PermissionsActivity | BatteryGuideActivity screen |
| Exact-alarm status + fix | PermissionsActivity alarm card | BatteryGuideActivity alarm line |
| Role status + fix | PermissionsActivity role card | BatteryGuideActivity role line |
| Temp allow | Smart Allow tab page | — (Dashboard button is a quick-action mirror of the same Store flag; one state) |
| Allow next call | Smart Allow tab page switch | — (Dashboard switch mirrors the same Store flag) |
| Active profile | ProfilesActivity editor | — (Dashboard chips + QS tile are quick mirrors of the same Store value) |
| Block mode, hidden rule, escalation, soft-screen, silent-notif, autoclean, language, theme | SettingsActivity only | none found elsewhere |
| Summary settings (31/32/35) | StatsActivity | none (Dashboard tiles are an at-a-glance summary, Stats the full detail — distinct roles, kept) |
| First-run grants | WizardActivity (guided flow) | — PermissionsActivity is status/repair; the two menu rows now say so in their descriptions |
| Setup entry points | Settings-tab rows "Permissions" (status/repair) + "Setup wizard" (guided re-run) | kept both, roles made explicit in row descriptions |

Strings removed with the guide screen: nav_battery,
battery_status_label, battery_state_line, battery_role_line,
battery_alarm_line (battery_title / battery_xos_* are reused by the
Permissions card).

### Counts (before → after)

| Metric | v1.1.0 | v1.1.1 |
|---|---|---|
| Kotlin files | 45 | **43** (−LogActivity, −BatteryGuideActivity) |
| Layouts | 18 | **16** (−activity_log, −activity_battery) |
| XML total (res + manifest) | 33 | **31** |
| Strings EN / BN | 434 / 434 | **467 / 467** (+38 menu/wizard, −5 guide) |
| Permissions | 12 | **12** (unchanged, each exactly once) |
| Manifest components | 26 (16 act / 4 svc / 6 rcv) | **24 (14 / 4 / 6)** |
| Version | vc4 / 1.1.0 | **vc5 / 1.1.1** |
| DB | v3 | v3 (unchanged) |

### Validation run (this pass, whole project)

- All **31 XML parse** (minidom/ElementTree).
- **Dotted-style parent scan clean** — all 11 styles incl. the four
  new CS.CardMenu/CS.MenuRow/CS.MenuDot/CS.MenuStatus carry an
  explicit parent.
- **Strings EN=BN=467**: key sets identical, per-key placeholder
  sets identical, unescaped-apostrophe scan clean (2 hits in the
  first draft of the new wizard strings were caught and escaped).
- **All app R.string/R.id/R.layout/R.drawable/R.color references
  resolve** (218+ refs; android.R.* excluded); all @string/@color/
  @drawable/@style references in layouts resolve.
- **Manifest**: 24 components, each maps to a real class; 12
  permissions, each exactly once.
- **Import↔use** heuristic clean over all 43 Kotlin files; no
  unused imports introduced (MainActivity's import set re-audited
  after the merge); brace/paren balance clean.
- **Build flags**: versionCode 5 / versionName "1.1.1", AndroidX
  on, Kotlin 1.9.24, appcompat 1.7.0, biometric 1.1.0,
  compileSdk 34 / minSdk 29 — unchanged pins.
- Targeted greps: no KEY_PAGE / savedInstanceState tab restore, no
  LogActivity or BatteryGuideActivity references in code (comments
  aside), no Instances.appendId regression.

### NOT checkable locally (explicit)

- **Kotlin compile + AAPT = CI-only**, as in every earlier section.
  Hand-review risks in this pass: MainActivity grew the log page +
  menu binding (all findViewById ids resolve against the merged
  layout; the spinner listener fires refreshLog only after all log
  views are bound); WizardActivity's step table indices are
  data-driven (no parallel arrays left to drift).
- **On-device behaviour untested**: cold-start tab, warm config
  change tab retention, deep-link to Log, wizard settings-intent
  round trips (notification access, exact alarm), menu row ripple
  and status widths in Bangla, battery-card reading order.

### v1.1.1 icon update (2026-10-23)

- **Source art**: Tamim's chosen logo #13 (minimal round: white + green +
  other colours mixed, then minimal colours) —
  `media-generation-callshield-logo4-1-minimal-gre…webp`, 1600×1600 RGB:
  an emerald-green shield outline with a white telephone handset on a
  white background. The artwork was NOT redesigned; it was only
  converted into Android icon assets with Python/PIL.
- **Background colour**: sampled from the artwork itself, not guessed —
  corner pixels TL #FDFDFD, TR #FEFEFE, BL #FCFBFC, BR #FFFFFF (webp
  compression noise around white). New colour resource
  `ic_launcher_background` = **#FFFFFF**, used as the adaptive-icon
  background in both `mipmap-anydpi-v26/ic_launcher.xml` and
  `ic_launcher_round.xml`, replacing the old `@color/cs_accent`
  background.
- **Assets produced**:
  - Legacy PNGs: `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
    + `ic_launcher_round.png` at **48 / 72 / 96 / 144 / 192 px** (the art
    was already exactly square, so no crop was needed; the round variant
    is the same art circular-masked with transparent corners).
  - Adaptive foreground: `drawable-xxxhdpi/ic_launcher_fg.png`,
    **432×432 RGBA** — the art scaled to 311×311 (≈72%) and centred
    (60 px transparent margin) on a transparent canvas, so the shield
    stays inside the adaptive safe zone and no launcher mask clips it.
- **Removed**: `drawable/ic_launcher_foreground.xml` (the old vector
  foreground). A whole-project grep shows its only references were the
  two adaptive-icon XMLs, which now point at the PNG; no dangling
  reference remains. `ic_stat_shield` (notification icon) untouched.
  Manifest `android:icon="@mipmap/ic_launcher"` and
  `android:roundIcon="@mipmap/ic_launcher_round"` unchanged and resolve.
- **Validation**: all **30 XML** files parse (one fewer than v1.1.1's 31 —
  the removed vector); EN = BN = **467** strings, key sets identical,
  unescaped-apostrophe scan clean; dotted-style parent scan clean;
  whole-project `@type/name` reference resolution — none unresolved;
  every produced PNG verified at its exact expected pixel size.
  Version stays **versionCode 5 / versionName "1.1.1"**. Kotlin compile
  and the on-launcher appearance of the icon remain CI/on-device checks,
  as before.

## v1.2.0 — full visual redesign (reference style)

Look-only release on top of v1.1.1 (which was never pushed separately):
every feature, view id, handler, store and the DB (v3) is unchanged.
Tamim supplied reference screenshots of another call-blocker app and
approved a dashboard preview in that style before this pass.

### Tokens as implemented

- Dark (values-night/colors.xml): bg `#0A1120`, card `#131C2E`, border
  `#1E2A44`, bottom bar `#0D1526` with its own divider token `#1A2740`,
  headings `#EEF2F8`, body `#C9D4E4`, muted `#8B98AD`, accent `#22C55E`,
  on-accent `#06251A`. Light (values/colors.xml): bg `#F2F5FA`, card
  `#FFFFFF`, border `#DFE6F0`, same accent, darker text-grade variants
  (accent-dark `#0E8A44`, red `#E11D48`, amber `#D97706`, purple `#7C3AED`,
  blue `#0284C7`, pink `#DB2777`) so coloured text stays readable.
- Icon-tile system: six tint pairs (green/red/amber/purple/blue/pink) as
  `cs_tint_*` tile backgrounds + `bg_tile_*` drawables (12dp radius) and
  19 new vector glyphs (home, filter, clock, list, gear, shield,
  shield-check, shield-ring, block, warning, check-circle, sliders,
  wand, chat, chart, bulb, flask, lock, search) — Material-style paths,
  no emoji anywhere. The shield-check glyph is a single even-odd path so
  one tint colours the shield and the check stays cut out.
- Cards: `bg_card` radius 16 → 19dp; buttons 12 → 14dp radius; primary
  buttons are filled accent with dark text (`cs_on_accent` is now
  `#06251A` in both themes).

### Per-screen restyle checklist

- **Dashboard**: two-tone "Call/Shield" header; status card became the
  hero (`bg_hero` gradient `#0D3B2E → #123A5C`, border `#1E4638`, 20dp)
  holding the shield-ring graphic, `textHeroState` + `textHeroPill`
  (ON/OFF pill, mirrors the master flag in `refresh()`), then the same
  `switchMaster` / `textStatus` / `textRoleStatus` / `textProfile`.
  Stat tiles are three separate tiles with tinted icons (red block,
  amber warning, green check) and coloured numbers (`textBlockedToday`
  still in the today tile). Quick Actions unchanged in wiring; profile
  chips' unselected state is now `bg_chip` (pill outline). Recent-block
  rows built in code are now `bg_row` cards with a red block tile.
  Protection-check card inherits the card system (dots unchanged).
- **Filtering / Settings pages**: the v1.1.1 menu rows keep ids and
  handlers; the 10dp dot became a 40dp icon tile (tile + glyph + tint
  per row, `CS.MenuTile` replaces `CS.MenuDot`); row dividers re-indented
  to line up under the text (34 → 64dp).
- **Lists**: rows are cards via `item_row`; the Add button is the tinted
  outline (`bg_button_outline`) recoloured per section at runtime in
  `ListsActivity.tintAddButton` (green whitelist / red blacklist / blue
  prefixes, stroke + label), Pick contact became the filled primary.
- **Log page**: list rows are the same cards; divider became a 6dp
  transparent gap. NOTE: the Log filter in the shipped app is a Spinner,
  not chips — there were no chips to restyle; the pill-chip treatment
  (filled accent when selected, outline otherwise) was applied to the
  Dashboard profile chips instead, which are the app's actual chips.
- **Smart Allow, Profiles, Rules, Sms, Stats, Suggestions, TestMode,
  Security, Online, Settings, Permissions, Lock**: no layout surgery —
  they inherit the new palette through the shared theme, `CS.*` styles
  and the token colours (window bg navy, cards new surface, headings
  bold white, switches follow `colorAccent` = emerald).
- **Wizard**: step card inherits the new card style; a horizontal
  progress bar (`progressWizard`, accent-tinted) now sits under the
  "step x of y" text and is driven from `showStep()`.
- **Bottom navigation**: bar background is the new `cs_bottombar`
  token; tab icons are the new vectors (home/filter/clock/list/gear);
  the active tab keeps bold + accent colour and gains the accent dot
  (`dotTab*`, INVISIBLE when inactive so the bar height never changes).
- **Widget**: inherits `bg_card` (new radius/surface) unchanged in code.
- The new launcher icon from v1.1.1 is untouched. No fake elements from
  the reference (no "AI-Powered" claim, no PRO/subscription, no account
  card, no trial badge) were added; every displayed state is real.

### Counts (before → after)

| Metric | v1.1.1 | v1.2.0 |
|---|---|---|
| Kotlin files | 43 | 43 (3 edited: MainActivity, ListsActivity, WizardActivity) |
| Layouts | 16 | 16 (4 edited: activity_main, activity_lists, activity_wizard, item_row) |
| Drawable XML | 5 | 36 (+19 vectors, +6 shapes, +6 tiles) |
| Res XML total | 29 | 60 |
| Strings EN / BN | 467 / 467 | 473 / 473 (+6 hero strings, both locales) |
| Total files | 95 | 126 |
| Version | vc5 / 1.1.1 | vc6 / 1.2.0 |

### Validation

Whole-project pass per the skill: all 60 res XML + manifest parse;
dotted-style parent scan clean (new `CS.StatTile` / `CS.MenuTile` carry
explicit parents); EN = BN = 473 keys, placeholder parity, unescaped
apostrophe scan clean in both locales; all 319 `@type/name` references
in res XML resolve; all Kotlin `R.*` references resolve (the only
apparent misses are `android.R.*` framework refs); import↔use check
clean project-wide (0 unused imports); brace/paren balance on the three
edited Kotlin files; `android.useAndroidX=true` intact; pinned versions
unchanged (AGP 8.5.2, Kotlin 1.9.24, appcompat 1.7.0, biometric 1.1.0) —
no new library calls were introduced (ProgressBar tint, vector
drawables and shape gradients are all framework API ≤ API 29).
**Not checkable locally (unchanged from before): Kotlin compile and
AAPT linking are CI-only; the on-device look (gradient rendering, tile
tints on XOS, light-theme contrast) is unverified until Tamim installs
the CI build.** Staged workflow release step now publishes tag v1.2.0.
