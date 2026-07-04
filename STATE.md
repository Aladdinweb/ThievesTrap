# THIEVES TRAP — Project State

> **Purpose of this file:** Paste this entire file at the start of a new Claude
> conversation to restore full project context instantly, without re-explaining
> architecture or history. Update it after every version push.

---

## 1. Project Overview

**App:** Thieves Trap — Android anti-theft and remote monitoring app
**Package:** `com.thievestrap`
**Repo:** `github.com/Aladdinweb/ThievesTrap` (branch: `main`)
**Dev environment:** 100% mobile — Samsung phone, Android 13, Termux + GitHub Actions CI. No computer involved.
**Project dir on phone:** `~/ThievesTrapV18`
**Current version:** `v2.8.3` (versionCode 131)

**Core concept:** When phone is armed and a thief tries to unlock it (or removes the SIM, disconnects a paired smartwatch, etc.), the app sends emergency SMS/Telegram alerts with GPS location, takes intruder selfies, and supports a remote SMS command system to track/control the phone from any other phone.

---

## 2. Build & Release Pipeline

- **Build:** GitHub Actions (`.github/workflows/build.yml`) — `assembleRelease`, no local Android Studio.
- **Signing:** Production keystore `~/thieves_trap_release.jks` (JKS format — **critical**: PKCS12 from Java 17 default breaks Android's signer with "Tag number over 30"). Stored as base64 in GitHub Secret `KEYSTORE_BASE64`, with `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` also as secrets.
- **APK naming:** `build.gradle`'s `outputFileName` block auto-names output as `Thieves_Trap_v${versionName}_Final.apk`.
- **Artifact discovery in build.yml:** dynamic (`find app/build/outputs/apk/release -name "*.apk"`) — never hardcode the filename/version in build.yml again, it broke multiple times.
- **OTA delivery mechanism:** GitHub **Releases** (not Actions artifacts!). `UpdateManager.kt` hits `api.github.com/repos/Aladdinweb/ThievesTrap/releases/latest`. Actions artifacts are NOT visible to this endpoint — every version bump requires manually publishing a Release with the signed APK attached as an asset.
- **Release publishing tool:** `release_tool/create_release.sh <version> <apk_path>` — automates Release creation + asset upload via GitHub API from Termux.
- **GitHub PAT:** `ghp_REDACTED_rotate_at_github.com/settings/tokens` — rotate periodically at `github.com/settings/tokens`.
- **IMPORTANT CAVEAT discovered:** Once a device has a build installed signed with the *old debug keystore*, OTA updates signed with the *new release keystore* will fail to install ("problem parsing the package" = signature mismatch, not corruption). Must uninstall once and side-load manually to switch keystores; all subsequent OTA updates work fine since the keystore is now consistent.

### Standard release workflow (every version bump):
```bash
# 1. Push code changes (via the relevant push_vX.sh script)
bash push_vXXX.sh

# 2. Wait for GitHub Actions to go green (~3-5 min)
# 3. Download artifact zip from github.com/Aladdinweb/ThievesTrap/actions
cd /sdcard/Download
unzip -o Thieves_Trap_vX.X.X_Final.apk.zip
ls -lh Thieves_Trap_vX.X.X_Final.apk   # must be several MB, not KB

# 4. Delete old GitHub Release of same version if re-publishing
# (github.com/Aladdinweb/ThievesTrap/releases -> delete release + tag)

# 5. Publish release
cd /sdcard/Download/release_tool
bash create_release.sh X.X.X /sdcard/Download/Thieves_Trap_vX.X.X_Final.apk

# 6. Test in-app: sidebar -> Check for Update
```

---

## 3. File Map — What Each File Does

### Kotlin source (`app/src/main/java/com/thievestrap/`)

| File | Purpose |
|---|---|
| `MainActivity.kt` | Main screen: ARM/DISARM button, shield status UI, nav drawer (Settings, Premium, PIN change, Survival Timer, Watch Tether, Check for Update, Language). First-ARM guidance dialog (`isFirstArm` pref). Watch Tether ℹ️ badge wired to info dialog. |
| `MonitorService.kt` | Core foreground service. Handles SMS commands (`SMS_COMMAND` action from static receiver), SIM swap trap state machine, silent-mode grace period, theft mode toggles, location updates, all SMS template building (`buildFullInfo`, IMEI injection, PING_NOTE footer). Uses `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` so SMS processing and SIM-trap timer never block each other. |
| `SmsCommandReceiver.kt` | **Static**, manifest-registered (`priority=999`, `exported=true`) SMS receiver — survives process death, unlike a dynamic receiver. Forwards `SMS_RECEIVED` to `MonitorService` via explicit intent + `startForegroundService`. Has `@Volatile lastProcessedTime` dedup filter (5s window) to prevent double-processing. **This is now the SOLE SMS entry point** — `MonitorService`'s old dynamic `smsReceiver` was removed in v2.7.9b to fix duplicate SMS replies. |
| `SmartwatchMonitorService.kt` | Bluetooth ACL connection monitor for paired smartwatch ("Watch Tether" feature). On disconnect: vibrate + `DevicePolicyManager.lockNow()` + 5-min countdown notification with **🚨 TRIGGER ALARM** / **🔕 DISARM/MUTE** / **I'm Safe** (no PIN) actions. On expiry: one emergency SMS with GPS. Auto-aborts on reconnect. **Premium only since v2.8.6**. |
| `RemoteGuideActivity.kt` | "Remote Control Guide" screen — lists all SMS commands using `item_command.xml` rows. Each row's real IDs are `tv_cmd_text` / `tv_cmd_desc` / `btn_copy_cmd` (NOT `tv_command_name`/`tv_command_desc` — that was a v2.7.9b bug, fixed in v2.8.0). Has ℹ️ badge (`btn_plan_b_info`) explaining the Plan B dynamic-PIN mechanism. |
| `UpdateManager.kt` | OTA update logic. `checkForUpdate()` → GitHub Releases API → compares `tag_name` (strips "v") against `BuildConfig.VERSION_NAME` → shows AlertDialog if newer → `DownloadManager` streams APK to `Downloads/` → `FileProvider` + `ACTION_VIEW` launches installer. Validates downloaded file size (`MIN_APK_BYTES = 1MB`) to catch corrupt/wrapper downloads before attempting install. |
| `SettingsActivity.kt` | Settings screen (IMEI, emergency contacts, Telegram bot setup, theft alert toggles, SMS test). Airplane Mode row fully removed (v2.7.6). |
| `PremiumActivity.kt` | Premium screen. Annual subscription plan ($4.99/yr). Contact Support button opens Facebook page (`https://www.facebook.com/share/1MRdfCnNoY/`) — tries FB app first, falls back to browser. License key entry, Device ID copy, Revoke license. All UI text via `@string/` refs for full EN/FR/AR localization. |
| `AboutActivity.kt` | About screen. All rows (Rate, Share, Terms, Privacy) and info sections (Privacy Commitment, Modern Efficiency, Our Mission) use `@string/` refs — fully localized EN/FR/AR. |
| `SelfieService.kt` / `SelfiesActivity.kt` | Intruder selfie capture (Device Admin triggered, on failed unlock) + gallery viewer. |
| `SurvivalTimerService.kt` | "I'm still safe" dead-man's-switch timer — sends emergency SMS if not cancelled in time. |
| `SafeConfirmReceiver.kt` / `SafeConfirmActivity.kt` | "I'm Safe" button handling — stops `SurvivalTimerService` directly via `PendingIntent.getService`, **no PIN required**. |
| `AlarmService.kt` | Max-volume siren service, controlled via `START_ALARM`/`STOP_ALARM` actions — used by remote ALARM command, Watch Tether TRIGGER ALARM, and Plan B ALARM. |
| `LicenseManager.kt` | Free/Premium gating logic. |
| `FaceCaptureService.kt` | **v2.8.7** Foreground service: ML Kit face detection on `ACTION_SCREEN_ON`, Camera2 front-camera preview, captures JPEG on face detected. Dual delivery: `TelegramUploader.sendPhoto()` + GitHub Pages upload → self-destruct SMS link. Activated by SMS `FACE ON`, stopped by `FACE OFF`. Premium only. |
| `TelegramUploader.kt` | Sends messages/photos to Telegram bot (@ThievesTrap_Alert_bot) — premium feature. |
| `BootReceiver.kt` | Restarts monitoring on device boot if `running=true`. |
| `DeviceAdminReceiver.kt` | Device Admin callbacks — failed/succeeded password attempts trigger selfie + alert. |
| `LocaleHelper.kt` / `Strings.kt` | EN/FR/AR localization. |

### Layouts (`app/src/main/res/layout/`)

| File | Notes |
|---|---|
| `activity_main.xml` | Main screen + right nav drawer (`DrawerLayout`, `gravity="end"`). Drawer order: Settings → Premium → Fingerprint/Change PIN → **PERSONAL SAFETY** section (Survival Timer with ℹ️ badge `nav_survival_info` → Watch Tether with ℹ️ badge `nav_watch_tether_info` → Check for Update `nav_check_update`, crimson `#FF1A1A`) → spacer → Language at very bottom. |
| `activity_settings.xml` | DEVICE STATUS ALERTS section — Airplane Mode row permanently deleted. |
| `activity_remote_guide.xml` | Header has `btn_plan_b_info` ℹ️ badge next to title. Body is a list of `<include layout="@layout/item_command">` rows with unique `android:id`s. |
| `activity_about.xml` | All text via `@string/` refs — rate_app_sub, share_this_app, share_this_app_sub, terms_of_use, terms_of_use_sub, privacy_policy, privacy_policy_sub, about_privacy_commitment_title/body, about_modern_efficiency_title/body, about_our_mission_title/body. Fully localized. |
| `activity_premium.xml` | Annual plan with `/yr` suffix on price. Contact Support row: blue enabled button → Facebook. All feature rows use `@string/prem_feat*` keys. All section headers via `@string/` refs. |
| `item_command.xml` | **Real IDs**: `tv_cmd_text`, `tv_cmd_desc`, `btn_copy_cmd`. Default placeholder must be overwritten per-row in `RemoteGuideActivity.setupCommandRows()`. |
| `file_paths.xml` | FileProvider paths — `pictures`, `external_pictures`, `external_dcim`, `downloads`. |

### Manifest & Build

| File | Key points |
|---|---|
| `AndroidManifest.xml` | `SmsCommandReceiver` static receiver, `priority="999"`. `REQUEST_INSTALL_PACKAGES` permission (OTA). Bluetooth permissions (Watch Tether). FileProvider authority `${applicationId}.fileprovider`. |
| `app/build.gradle` | `versionCode 131`, `versionName "2.8.7"`. Release `minifyEnabled true`. `outputFileName` block names APK by version. Release `minifyEnabled true`. |
| `.github/workflows/build.yml` | `assembleRelease` with signing via GitHub Secrets. Dynamic APK discovery (no hardcoded filename). |

---

## 4. SMS Remote Command Reference (current, v2.8.3)

**Free (no premium, no registration check):**
- `WHERE` / `LOCATION` / `LOC` / `FIND` — GPS + Maps link, exactly ONE SMS reply, includes IMEI + PING_NOTE footer.
- `HELP`, `STATUS` (basic)

**Premium (requires registered sender OR Plan B PIN):**
- `INFO`/`DEVICE`, `BATTERY`/`BAT`, `SIM`, `IMEI`, `HISTORY`
- `ALARM`/`RING`, `STOP ALARM`/`SILENCE`, `LOCK`, `SELFIE`/`PHOTO`/`PICTURE`
- `PING <mins>`, `STOP PING`
- `ACTIVE`/`ACTIVATE`, `DEACTIVATE`, `DISARM <pin>`

**FACE capture (Premium, v2.8.7):**
- `FACE ON` — starts `FaceCaptureService`, ML Kit face detection active, Telegram+SMS delivery on capture
- `FACE OFF` — stops service, closes camera

**Plan B (dynamic PIN, works from ANY unknown phone number):**
- Pattern: `COMMAND PIN` e.g. `WHERE 2026`, `ALARM 2026`
- PIN = live value of `password` in SharedPreferences (the security PIN)
- Bypasses registration entirely — replies directly to the unknown sender
- Implemented in `MonitorService.matchPlanBCommand()` + `handlePlanBCommand()`

**Loop guards (all in `MonitorService.processSmsPdus()`):**
1. Ignore sender if it matches own SIM number
2. Multi-part PDU concatenation per sender before parsing
3. Per-sender 5-second cooldown (`last_cmd_<digits>` pref)

---

## 5. Smart SIM Trap (lightweight, v2.7.8+)

1. SIM change/removal detected → `SIM_CHANGED_PENDING_ALERT=true` in prefs.
2. Register `ConnectivityManager.NetworkCallback` for `TRANSPORT_CELLULAR`, **`onAvailable()` only** — do NOT wait for `NET_CAPABILITY_VALIDATED` (caused freezes in v2.7.7, fixed in v2.7.8).
3. On signal: non-blocking 10s coroutine `delay()` (GPS warm-up), then send ONE emergency SMS via `SmsManager.getDefault()` with new carrier/line/IMEI/location.
4. Clear `SIM_CHANGED_PENDING_ALERT` — one-shot, survives process restart (re-registers listener in `onCreate()` if flag still true).

---

## 6. Watch Tether Mechanism (v2.7.6+, UX polished v2.7.9b/v2.8.0)

1. User pairs Bluetooth smartwatch, enables switch in sidebar (requires BT on + a bonded device).
2. `SmartwatchMonitorService` monitors `ACTION_ACL_DISCONNECTED`/`ACTION_ACL_CONNECTED`.
3. On disconnect: vibration pattern + `lockNow()` + notification with 3 actions: **I'm Safe** (no PIN), **🚨 TRIGGER ALARM**, **🔕 DISARM/MUTE**.
4. 5-minute countdown; reconnect auto-cancels.
5. On expiry: one emergency SMS with location to Emergency Contact.
6. Sidebar ℹ️ badge (`nav_watch_tether_info`) → explains full mechanism in AlertDialog.

---

## 7. Known Issues / Gotchas (don't repeat these mistakes)

- **Keystore format:** Always generate with `-storetype JKS` explicitly. Java 17's `keytool` default (PKCS12) breaks AGP's signer.
- **Signature continuity:** Never change keystores once a version is in the wild without planning for users to uninstall/reinstall once.
- **OTA source:** Releases, not Actions artifacts. `releases/latest` API is blind to artifacts.
- **R.id compile-time refs:** If adding a new XML id reference in Kotlin, the id must ALREADY exist in the XML before that Kotlin file compiles, or the release build fails outright.
- **`build.yml` artifact step:** Always use dynamic `find ... -name "*.apk"` discovery — hardcoding breaks on every version bump.
- **GitHub PAT exposure:** Rotate periodically at `github.com/settings/tokens`.
- **`web_fetch` limitation:** Claude cannot fetch raw GitHub URLs for this repo. Must paste file contents manually — STATE.md is the mitigation.
- **Contact Support:** Wired to Facebook page `https://www.facebook.com/share/1MRdfCnNoY/` — tries FB app URI first, falls back to browser.
- **Annual plan:** `lifetime_plan` string key removed; replaced by `annual_plan` + `price_per_year_suffix`. Do not re-add lifetime_plan.

---

## 8. Version History Summary

| Version | Highlights |
|---|---|
| v2.7.4 → v2.7.5 | SMS priority hardening, intruder selfie via Device Admin, Telegram deep link, Airplane Mode removed, silent grace period, "I'm Safe" no-PIN, language icon moved to sidebar |
| v2.7.6 | Smartwatch Tether feature (full), UI cleanup, IMEI injected into SMS templates |
| v2.7.7 | Static `SmsCommandReceiver`, Smart SIM Trap v1 (had NET_CAPABILITY_VALIDATED freeze bug) |
| v2.7.8 | Coroutine isolation (`SupervisorJob`), lightweight SIM trap (fixed freeze), Plan B PIN commands, build.yml artifact naming fix |
| v2.7.9 | OTA Update feature — `UpdateManager.kt`, sidebar "Check for Update", GitHub Releases API integration |
| v2.7.9b | Fixed duplicate SMS (removed dynamic receiver), restored WHERE footer note, Watch Tether TRIGGER ALARM/DISARM buttons, Plan B info badge, ARM first-time guidance dialog |
| v2.7.10 | OTA test version bump; discovered + fixed debug-vs-release signing mismatch blocking OTA installs |
| v2.8.0 | Watch Tether ℹ️ badge (sidebar symmetry), Remote Guide command-label bug fixed, copy-to-clipboard on each command row |
| v2.8.1 | Full EN/FR/AR localization for About screen (Share/Terms/Privacy/Commitment/Efficiency/Mission), lifetime → annual subscription plan ($4.99/yr), Contact Support wired to Facebook page |
| v2.8.2 | Version bump (OTA delivery attempt — content changes not yet applied) |
| v2.8.3 | Full EN/FR/AR i18n: About screen + Premium screen, lifetime→annual plan ($4.99/yr), Contact Support→Facebook, all pushed directly via GitHub API |
| v2.8.4 | Fixed Contact Support label display bug, free features list layout (selfie removed from free tier), Telegram section rebuilt with 3 buttons, all via API pushes |
| v2.8.5 | Fixed OTA corrupt file error (MIN_APK_BYTES 1MB→500KB), background OTA notification, Telegram bot /start polling fixed (JSONObject parser), 3-button Telegram UI, version.json updated |
| v2.8.6 | Watch Tether: premium-gated + real BT enable/scan/pair flow (btEnableLauncher, btPermLauncher, device picker dialog); Telegram: simplified to 2 buttons (Connect Bot + Share Bot Link), removed instruction card + Share My Chat ID; 7 new BT string keys in EN/FR/AR |
| v2.8.6b | Watch Tether switch color fixed: removed hardcoded red thumbTint/trackTint from XML; `updateWatchTetherStatus()` now sets green thumb+track when ON, grey when OFF — instant visual feedback without reopen. BT crash fixed: permission check before `isEnabled`, switch reverts to OFF immediately before any async dialog. |
| v2.8.7 | **FACE ON/OFF**: `FaceCaptureService.kt` — ML Kit face detection on screen wake, dual delivery (Telegram photo + GitHub Pages self-destruct SMS link), premium-gated UI switch in Settings, Plan B PIN supported. 7 files in one combined commit. |: removed hardcoded red thumbTint/trackTint from XML; `updateWatchTetherStatus()` now sets green thumb+track when ON, grey when OFF — instant visual feedback without reopen. BT crash fixed: permission check before `isEnabled`, switch reverts to OFF immediately before any async dialog. |

---

## 9. Pending / Not Yet Done

- [x] v2.8.3 Release published with APK asset — OTA live
- [ ] MainActivity UI-lag/coroutine optimization for instant shield-color updates on toggle (mentioned once, never delivered — original v2.7.8 request item 4)
- [ ] Verify `AboutActivity.kt` has no hardcoded version strings (uses `BuildConfig.VERSION_NAME`, should be fine, not re-verified since v2.7.6)
- [ ] Rotate GitHub PAT (exposed across many session scripts)

---

## 10. How to Resume Work With Claude

1. Paste this entire STATE.md at the start of the new conversation.
2. State which version you're moving to and what specifically needs to change.
3. If Claude needs to see a specific file's current content (e.g. to make a precise edit), it will ask — paste via `cat path/to/file`.
4. After Claude delivers a patch zip + push script, run it in Termux, then update **Section 8 (Version History)** and **Section 9 (Pending)** in this file before committing it back to the repo.
