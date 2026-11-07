# THIEVES TRAP — Project State

> **Purpose of this file:** Paste this entire file at the start of a new Claude
> conversation to restore full project context instantly, without re-explaining
> architecture or history. Update it after every version push.
> **Rule: always update this file with every change** (see Section 1).
> **Never put secrets in this file or paste them into chat:** no signing key, no admin token, no license keys, no PAT.

---

## 1. Project Overview

**App:** Thieves Trap — Android anti-theft and remote monitoring app
**Package:** `com.thievestrap`
**Repo:** `github.com/Aladdinweb/ThievesTrap` (branch: `main`)
**Dev environment:** 100% mobile — Samsung phone, Android 13, Termux + GitHub Actions CI. No computer involved.
**Project dir on phone:** `~/ThievesTrapV18`
**Current version:** `v2.9.10.2` (versionCode 159), 2026-11-07 -- pushed and tagged; the green Actions build and the two-phone strict-lock test are still to be confirmed (Section 9). Built on the v2.8.6 baseline (see history below for the full chain). No Face Capture, no FACE ON/FACE OFF -- that feature was reverted at the user's explicit request 2026-08-28 and has not been reattempted.
**Version numbering rule (set 2026-11-07):** continue sequentially from the production baseline `v2.9.10.2`: the next releases are `v2.9.11`, then `v2.9.12`, and so on (`versionName "2.9.11"`, `versionCode` +1 each time: next is 160). No more four-part names. Every release also gets the matching `vX.Y.Z` git tag (that is what triggers `release.yml`).
**STATE.md rule (set 2026-11-07):** STATE.md is updated in the same step as EVERY code or feature change (version row in Section 8, Pending in Section 9, architecture notes where they apply) and pushed with it.
**Licensing (since v2.9.9.9):** server-validated by a Cloudflare Worker + KV with a web admin dashboard -- see **Section 11**. Prices are in EUR (5.99 / 10.99 / 24.99).

**Core concept:** When phone is armed and a thief tries to unlock it (or removes the SIM, disconnects a paired smartwatch, etc.), the app sends emergency SMS/Telegram alerts with GPS location, takes intruder selfies, and supports a remote SMS command system to track/control the phone from any other phone.

---

## 2. Build & Release Pipeline

- **Build:** GitHub Actions (`.github/workflows/build.yml`) — `assembleRelease`, no local Android Studio.
- **Signing:** Production keystore `~/thieves_trap_release.jks` (JKS format — **critical**: PKCS12 from Java 17 default breaks Android's signer with "Tag number over 30"). Stored as base64 in GitHub Secret `KEYSTORE_BASE64`, with `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` also as secrets.
- **APK naming:** `build.gradle`'s `outputFileName` block auto-names output as `Thieves_Trap_v${versionName}_Final.apk`.
- **Artifact discovery in build.yml:** dynamic (`find app/build/outputs/apk/release -name "*.apk"`) — never hardcode the filename/version in build.yml again, it broke multiple times.
- **OTA delivery mechanism — CORRECTED 2026-08-28 (this entry was wrong before):** `UpdateManager.kt` does NOT check the GitHub Releases API. It fetches `version.json` directly from `raw.githubusercontent.com/Aladdinweb/ThievesTrap/main/version.json`, compares its `version_code` field against `BuildConfig.VERSION_CODE`, and downloads from the `download_url` field inside that same JSON. A GitHub Release is still needed as the file host (the `download_url` conventionally points at a Release asset), but publishing a Release alone does nothing -- `version.json` on `main` is what actually triggers the update prompt. Every version bump requires updating `version.json`'s three fields.
- **Release is now TAG-DRIVEN (since v2.9.2):** `.github/workflows/release.yml` builds the signed APK on a `vX.Y.Z` tag, creates the Release with the APK, then commits `version.json` (keys `version_code`/`version_name`/`download_url`) to `main`. **Consequence: after every tag push, `main` has a new commit from the bot, so the next Termux push is rejected until you `git pull --rebase origin main` first.** Always pull --rebase before pushing.
- **Release publishing tool (legacy, no longer used):** `release_tool/create_release.sh <version> <apk_path>`.
- **License Worker deploy:** the Worker source lives in `license_worker/` (`worker.js`, `wrangler.toml`). `.github/workflows/deploy-worker.yml` deploys it on any push touching `license_worker/**` (needs GitHub secrets `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID`; KV namespace id is in `wrangler.toml`). **Status: confirm in Pending whether the pipeline is actually set up; the v2 Worker was verified live on 2026-11-07 (deploy method not recorded).** Editing the Worker in the Cloudflare dashboard is overwritten by the next pipeline deploy, so the repo copy must always be the newest.
- **GitHub PAT:** `ghp_REDACTED_rotate_at_github.com/settings/tokens` — rotate periodically at `github.com/settings/tokens`. Needs the `workflow` scope to push `.github/workflows/` changes.
- **IMPORTANT CAVEAT discovered:** Once a device has a build installed signed with the *old debug keystore*, OTA updates signed with the *new release keystore* will fail to install ("problem parsing the package" = signature mismatch, not corruption). Must uninstall once and side-load manually to switch keystores; all subsequent OTA updates work fine since the keystore is now consistent.

### Standard release workflow (every version bump, tag-driven):
```bash
cd ~/ThievesTrapV18 && git pull --rebase origin main      # ALWAYS first (bot commits version.json)
# copy files, bump versionCode/versionName in app/build.gradle, then:
git add -A && git commit -m "vX.Y.Z: ..." && git push origin main
git tag vX.Y.Z && git push origin vX.Y.Z                  # triggers release.yml
# wait for green Actions run (~3-5 min), then test in-app: sidebar -> Check for Update
```

---

## 3. File Map — What Each File Does

### Kotlin source (`app/src/main/java/com/thievestrap/`)

| File | Purpose |
|---|---|
| `MainActivity.kt` | Main screen: ARM/DISARM button, shield status UI, nav drawer (Settings, Premium, PIN change, Survival Timer, Watch Tether, Check for Update, Language). First-ARM guidance dialog (`isFirstArm` pref). Watch Tether ℹ️ badge wired to info dialog. |
| `MonitorService.kt` | Core foreground service. Handles SMS commands (`SMS_COMMAND` action from static receiver), SIM swap trap state machine, silent-mode grace period, theft mode toggles, location updates, all SMS template building (`buildFullInfo`, IMEI injection, PING_NOTE footer). Uses `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` so SMS processing and SIM-trap timer never block each other. |
| `SmsCommandReceiver.kt` | **Static**, manifest-registered (`priority=999`, `exported=true`) SMS receiver — survives process death, unlike a dynamic receiver. Forwards `SMS_RECEIVED` to `MonitorService` via explicit intent + `startForegroundService`. Has `@Volatile lastProcessedTime` dedup filter (5s window) to prevent double-processing. **This is now the SOLE SMS entry point** — `MonitorService`'s old dynamic `smsReceiver` was removed in v2.7.9b to fix duplicate SMS replies. |
| `SmartwatchMonitorService.kt` | Bluetooth ACL connection monitor for paired smartwatch ("Watch Tether" feature). On disconnect: vibrate + `DevicePolicyManager.lockNow()` + 5-min countdown notification with **🚨 TRIGGER ALARM** / **🔕 DISARM/MUTE** / **I'm Safe** (no PIN) actions. On expiry: one emergency SMS with GPS. Auto-aborts on reconnect. |
| `RemoteGuideActivity.kt` | "Remote Control Guide" screen — lists all SMS commands using `item_command.xml` rows. Each row's real IDs are `tv_cmd_text` / `tv_cmd_desc` / `btn_copy_cmd` (NOT `tv_command_name`/`tv_command_desc` — that was a v2.7.9b bug, fixed in v2.8.0). Has ℹ️ badge (`btn_plan_b_info`) explaining the Plan B dynamic-PIN mechanism. |
| `UpdateManager.kt` | OTA update logic (see Section 2 for the real mechanism: `version.json` on `main`). Validates downloaded file size (`MIN_APK_BYTES`) to catch corrupt/wrapper downloads before attempting install. `checkForUpdateAutoPopup()` runs once per process from `MainActivity.onResume()`. |
| `SettingsActivity.kt` | Settings screen (IMEI, emergency contacts auto-save, Telegram bot setup, theft alert toggles, SMS test). Airplane Mode row fully removed (v2.7.6). |
| `PremiumActivity.kt` | **Rewritten v2.9.9.6 → v2.9.10.1.** Dark/gold FULL PROTECTION screen. Three plan cards in a container forced `layoutDirection="ltr"` (Monthly 5.99 € / 3 Months 10.99 € "Save 16%" / Annual 24.99 € with solid gold `#D4AF37` BEST VALUE badge, dark text `#0A0A0B`, badge sits INSIDE the card because a negative margin got clipped). Tapping a card copies a localized (EN/FR/AR) subscription request (plan, price, Device ID) to the clipboard and opens `https://m.me/61576473907901?text=...` (Messenger prefill is ignored on some versions, hence the clipboard copy); falls back to the Facebook page `https://www.facebook.com/share/1D2RrP4ey2/`. Duration labels ("1 month/3 months/1 year") set from code per language. License key entry runs `LicenseManager.activate()` on a background thread with localized error toasts including a bracketed diagnostic reason (e.g. `[signature]`, `(http 500 signing_failed)`). Line under the Device ID: expiry date + days left, or **Lifetime / À vie / مدى الحياة**, or "Online check required" when the lease ran out; orange in the last 7 days, red when expired. Second line under Device ID: `Last check: dd/MM HH:mm - <result>` (see Section 11). On open it forces a server check and `recreate()`s if premium state changed. Copy Device ID, Revoke License (local reset only), Technical Support (Facebook) kept. Success dialog `dialog_subscription_success.xml` (not cancelable, OK -> MainActivity with cleared stack). |
| `AboutActivity.kt` | About screen. All rows (Rate, Share, Terms, Privacy) and info sections (Privacy Commitment, Modern Efficiency, Our Mission) use `@string/` refs — fully localized EN/FR/AR. |
| `SelfieService.kt` / `SelfiesActivity.kt` | Intruder selfie capture (Device Admin triggered, on failed unlock) + gallery viewer. |
| `SurvivalTimerService.kt` | "I'm still safe" dead-man's-switch timer — sends emergency SMS if not cancelled in time. |
| `SafeConfirmReceiver.kt` / `SafeConfirmActivity.kt` | "I'm Safe" button handling — stops `SurvivalTimerService` directly via `PendingIntent.getService`, **no PIN required**. |
| `AlarmService.kt` | Max-volume siren service, controlled via `START_ALARM`/`STOP_ALARM` actions — used by remote ALARM command, Watch Tether TRIGGER ALARM, and Plan B ALARM. |
| `LicenseManager.kt` | **Rewritten v2.9.9.9 → v2.9.10.1.** Server-validated licensing client (full design in Section 11). `activate()` (blocking, call off the main thread), `isPremium()`, `getLicenseInfo()` (plan, expiry, legacy, expired, leaseExpired), `refreshAsync()`, `lastCheckSummary()`, `revokePremium()` (local reset), plus kept-for-compat `setPremium()`/`reconcileAfterUpdate()` for the grandfathered legacy flag. Holds only the PUBLIC key and the Worker URL (`PUBLIC_KEY_B64`, `API_URL` constants, patched in by the push command). Device key in Android Keystore (alias `tt_license_dev_v1`), signed requests, signed-answer verification, 72 h lease, 15-min online re-check + WorkManager periodic job (`tt_license_refresh`). **No master/admin key exists in the app any more.** |
| `TelegramUploader.kt` | Sends messages/photos to Telegram bot — premium feature. Bot token comes from `BuildConfig.TELEGRAM_BOT_TOKEN` (GitHub Secret). Photo upload via WorkManager `TelegramPhotoWorker`. |
| `BootReceiver.kt` | Restarts monitoring on device boot if `running=true`. |
| `DeviceAdminReceiver.kt` | Device Admin callbacks — failed/succeeded password attempts trigger selfie + alert. |
| `LocaleHelper.kt` / `Strings.kt` | EN/FR/AR localization. Two string systems exist: XML `@string/` resources and the Kotlin `Strings.kt` map -- a key must exist in the one actually used by the call site (`getString(R.string.x)` vs `s("x")`). |

### Layouts (`app/src/main/res/layout/`)

| File | Notes |
|---|---|
| `activity_main.xml` | Main screen + right nav drawer (`DrawerLayout`, `gravity="end"`). Drawer order: Settings → Premium → Fingerprint/Change PIN → **PERSONAL SAFETY** section (Survival Timer with ℹ️ badge `nav_survival_info` → Watch Tether with ℹ️ badge `nav_watch_tether_info` → Check for Update `nav_check_update`, crimson `#FF1A1A`) → spacer → Language at very bottom. |
| `activity_settings.xml` | DEVICE STATUS ALERTS section — Airplane Mode row permanently deleted. |
| `activity_remote_guide.xml` | Header has `btn_plan_b_info` ℹ️ badge next to title. Body is a list of `<include layout="@layout/item_command">` rows with unique `android:id`s. |
| `activity_about.xml` | All text via `@string/` refs. Fully localized. |
| `activity_premium.xml` | Plan cards container has `android:layoutDirection="ltr"`; price TextViews `tv_price_monthly/3month/annual` contain the EUR text ("5.99 €" with a no-break space); sub-labels `tv_sub_monthly/3month/annual`; BEST VALUE ribbon inside the Annual `FrameLayout`; `tv_device_id` then `tv_expiry` (gone unless a license exists); feature glass card; license box `et_license_key`; CTA `btn_buy`; `btn_copy_device_id`, `btn_revoke_license`, `btn_pay_support`. Cards use `layout_height="match_parent"` so they have equal height. **Inline `style=` attributes do not exist in layouts; XML comments must never contain `--`.** |
| `item_command.xml` | **Real IDs**: `tv_cmd_text`, `tv_cmd_desc`, `btn_copy_cmd`. Default placeholder must be overwritten per-row in `RemoteGuideActivity.setupCommandRows()`. |
| `file_paths.xml` | FileProvider paths — `pictures`, `external_pictures`, `external_dcim`, `downloads`. |

### Drawables of note
`bg_badge_best_value.xml` (solid `#D4AF37`, 8dp corners), `bg_plan_card.xml`, `bg_plan_card_selected.xml`, `bg_glass_card.xml`, `bg_status_active.xml`/`bg_status_free.xml`, `bg_btn_activate.xml`, `bg_license_input_box.xml`.

### License Worker (`license_worker/`, not part of the APK)

| File | Purpose |
|---|---|
| `worker.js` | Cloudflare Worker: `/activate`, `/check`, admin API and the `/admin` dashboard page. See Section 11. |
| `wrangler.toml` | Worker name `thievestrap-license`, `main = "worker.js"`, KV binding `LICENSES` (the binding MUST stay listed here, a deploy removes dashboard-only bindings). Secrets are NOT here. |

### Manifest & Build

| File | Key points |
|---|---|
| `AndroidManifest.xml` | `SmsCommandReceiver` static receiver, `priority="999"`. `REQUEST_INSTALL_PACKAGES` permission (OTA). Bluetooth permissions (Watch Tether). `INTERNET` (needed by licensing, Telegram, OTA). FileProvider authority `${applicationId}.fileprovider`. |
| `app/build.gradle` | `versionCode 158`, `versionName "2.9.10.1"`. `outputFileName` block names APK by version. Release `minifyEnabled true`. Dependencies include `androidx.work:work-runtime-ktx:2.8.1` and the (still inert) Play review/app-update libraries. |
| `.github/workflows/build.yml` | `assembleRelease` with signing via GitHub Secrets. Dynamic APK discovery (no hardcoded filename). |
| `.github/workflows/release.yml` | Tag-driven release (see Section 2). |
| `.github/workflows/deploy-worker.yml` | Deploys `license_worker/` with `npx wrangler@4 deploy` on push to `main` touching that folder (and manual dispatch). |

---

## 4. SMS Remote Command Reference (current, v2.8.3 baseline; later changes in Section 8)

**Free (no premium, no registration check):**
- `WHERE` / `LOCATION` / `LOC` / `FIND` — GPS + Maps link, exactly ONE SMS reply, includes IMEI + PING_NOTE footer. (Since v2.9.1 the reply goes to the registered emergency contact(s) unless the sender is registered or uses the correct Plan-B PIN.)
- `HELP`, `STATUS` (basic)

**Premium (requires registered sender OR Plan B PIN):**
- `INFO`/`DEVICE`, `BATTERY`/`BAT`, `SIM`, `IMEI`, `HISTORY`
- `ALARM`/`RING`, `STOP ALARM`/`SILENCE`, `LOCK`
- `PING <mins>`, `STOP PING`
- `ACTIVE`/`ACTIVATE`, `DEACTIVATE`, `DISARM <pin>`
- (`SELFIE`/`PHOTO`/`PICTURE` was retired in v2.9.9.3: it now only replies that the command is retired.)

**Plan B (dynamic PIN, works from ANY unknown phone number):**
- Pattern: `COMMAND PIN` e.g. `WHERE 2026`, `ALARM 2026`
- PIN = live value of `password` in SharedPreferences (the security PIN)
- Bypasses registration entirely — replies directly to the unknown sender
- Implemented in `MonitorService.matchPlanBCommand()` + `handlePlanBCommand()`

**Loop guards (all in `MonitorService.processSmsPdus()`):**
1. Ignore sender if it matches own SIM number
2. Multi-part PDU concatenation per sender before parsing
3. Per-sender+command 3-second cooldown

**Premium gating** everywhere goes through `LicenseManager.isPremium(context)`. It is now false when the license is revoked, expired, tampered, or its 72 h lease ran out without an online re-check (Section 11).

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
- **OTA source:** `version.json` on `main`, fetched raw (not via any GitHub API) -- see the corrected mechanism note in Section 2. Release is tag-driven now, never hand-edit `version.json`.
- **R.id compile-time refs:** If adding a new XML id reference in Kotlin, the id must ALREADY exist in the XML before that Kotlin file compiles, or the release build fails outright.
- **`build.yml` artifact step:** Always use dynamic `find ... -name "*.apk"` discovery — hardcoding breaks on every version bump.
- **GitHub PAT exposure:** Rotate periodically at `github.com/settings/tokens`.
- **`web_fetch` limitation:** Claude cannot fetch raw GitHub URLs for this repo, nor Facebook pages. Must paste file contents manually — STATE.md is the mitigation.
- **Contact Support:** Wired to the Facebook page (current link `https://www.facebook.com/share/1D2RrP4ey2/`) — tries FB app URI first, falls back to browser. A `share/...` link cannot be turned into an `m.me` link, hence the separate Messenger page ID.
- **Annual plan strings:** `lifetime_plan` string key removed; do not re-add. Pricing is now EUR and lives in `activity_premium.xml` + `PremiumActivity.planMessage()` (change both together).
- **CRITICAL: the `v2.8.6` git tag itself does not build via CI as committed.** Discovered 2026-08-28 when reverting to it directly. `MainActivity.kt`'s `findWatchSwitch()` referenced a nonexistent `R.id.nav_drawer_content` AND used an illegal bare `return` inside an expression-bodied function (`= try { ... }` instead of a block body) -- two separate compile errors. **Fixed on top of the revert** by replacing `findWatchSwitch()` with a block-bodied version that searches `window.decorView`. If anyone ever needs to check out the literal `v2.8.6` tag again, apply this same fix first or the build will fail immediately with the exact same two errors.
- **GitHub Actions log/artifact downloads are unreachable from Claude's sandboxed environment** (they redirect through `productionresultssa0.blob.core.windows.net`, outside the network allowlist). Workaround when a build fails and annotations don't help: temporarily add a workflow step that redirects Gradle's output to a file and, `if: failure()`, POSTs it to `api.github.com/repos/.../issues` using `${{ secrets.GITHUB_TOKEN }}` -- requires `default_workflow_permissions` = `write` (defaults to `read` on this repo; restore afterwards). Simplest alternative: paste the red error text from the Actions page into chat.
- **Fixed reliability bugs (2026-08-28 / 09-04), all in the current code:** `SmsCommandReceiver` async race (call `startForegroundService` synchronously), `SelfieService.takePhoto()` must use `startForegroundService`, `MonitorService.onStartCommand()` must call `startForeground()` unconditionally first, Premium state survives updates via a plain backup + self-heal (`PackageReplacedReceiver`, `reconcileAfterUpdate`), Watch Tether `BLUETOOTH_CONNECT` permission check must come before any `BluetoothAdapter` property read, wrong-PIN capture respects the threshold, `WHERE` no longer leaks coordinates to unverified senders, `setup-android@v3` CI step removed. Details are in the version history rows below.
- **XML comments must never contain `--`** anywhere in the body (breaks manifest/layout merging with a cryptic error; happened 3 times in one session). Check: `python3 -c "import re; content=open(path).read(); print([m.group(1)[:60] for m in re.finditer(r'<!--(.*?)-->', content, re.DOTALL) if '--' in m.group(1)])"` should print `[]`.
- **Missing-string-key pitfall:** a patch zip that omitted `Strings.kt` shipped a build that sent the literal key text. Before packaging, verify every `s("...")` key used in Kotlin exists in `Strings.kt`, and every `@string/...` used in XML exists in `strings.xml`.
- **SMS error code 32 (Infinix/XOS dual-SIM):** it is `RESULT_NO_DEFAULT_SMS_APP`, caused by a dual-SIM default-subscription conflict, fixed field-side by removing the 2nd SIM. The app never changes SMS/SIM configuration; it shows a one-time dialog after such a failure (see v2.9.9.5).

### Licensing / Cloudflare gotchas (2026-11 sessions) -- read before touching licensing

- **Stale copies in `/sdcard/Download`:** the phone kept old copies of `worker.js`, `PremiumActivity.kt`, `activity_premium.xml` (even a 50 KB `worker.js` from Oct 28). Always `ls -l` the files and `grep` for a marker string before copying (the push commands do this). Expected sizes drift, markers are reliable.
- **Pasting a command onto the end of a previous line** (`...activity_premium.xmlcd ~/...`) silently ran nothing. Paste each command as its own block, and run big `&&` chains only after the prompt is clean.
- **Bot commits break plain `git push`:** `release.yml` commits `version.json` after each tag, so run `git pull --rebase origin main` first (or the push is rejected). A half-applied chain leaves unstaged changes; recover with `git reset --hard HEAD && git clean -fd <dir>` then retry.
- **Placeholder guards:** `LicenseManager.kt` legitimately contains the text `YOUR-WORKER` inside `API_URL.contains("YOUR-WORKER")`. A guard must search for `YOUR-WORKER\.workers\.dev` and `PASTE_PUBLIC_KEY_BASE64` only, otherwise it always fails.
- **Old Worker bound a key BEFORE signing.** If signing failed (bad `SIGNING_KEY`), the dashboard showed the key Active/bound while the app said "License server error". Fixed in the Worker (sign first, then persist). "Active in dashboard + server error in app" = signing-key/public-key mismatch or malformed `SIGNING_KEY` secret.
- **App/Worker version skew:** a new app against an OLD Worker gets `not_found (404)` on `/check` (shown on the Premium screen as `Last check ... not_found (404)`), never learns about revocation, and its license dies after the 72 h lease. Always deploy the Worker first. Quick test: `curl -s -X POST https://thievestrap-license.ferakalaaeddine.workers.dev/check -H 'content-type: application/json' -d '{}'` -> new Worker answers `{"error":"update_required"}`, old one `{"error":"not_found"}`.
- **Secrets were exposed in chat screenshots (2026-11-06):** the signing key (`signing_key.b64` printed on screen) and an admin token that was overwritten with the OLD master key text `ADMIN_ILINE_2024`. Treat both as compromised until rotated (Pending). Never `cat` a secret on screen while screenshotting; never type your own admin token, generate it with `openssl rand -hex 32`. The old master key `ADMIN-ILINE-2024` and the old `ILINE2026`+DeviceID derivation are burned in every APK shipped before v2.9.9.9 and cannot be revoked there.
- **Dashboard page uses a nonce CSP:** inline `style="..."` attributes and inline event handlers are blocked silently; use classes and `addEventListener`. Page text is built with `textContent` (never `innerHTML`) so keys/notes cannot inject markup.
- **KV free tier:** about 1,000 writes/day. `/activate` costs 2 writes (record + replay nonce), `/check` costs 0 (read-only), admin actions 1 each, failed admin logins 1 each. List uses per-key metadata so the dashboard never reads every record.
- **Android ID is only a label** since v2.9.10; the binding identity is the Keystore public key. An uninstall/reinstall creates a new key, so the server sees a different device and refuses it (`key_in_use`) until the admin presses Unbind (strict lock, v2.9.10.2). `ALLOW_REINSTALL_SAME_ANDROID_ID` in `worker.js` (default false) would let the same Android ID re-bind by itself, at the cost of trusting a spoofable value.
- **Termux files (paths only, contents are secret):** `~/.ttlicense_keys/` holds `ec.pem` (private key, BACK IT UP, losing it forces a new app build), `signing_key.b64` (-> Cloudflare secret `SIGNING_KEY`), `public_key.b64` (-> embedded in the APK by the push `sed`), `admin_token.txt` (-> Cloudflare secret `ADMIN_TOKEN`).

---

## 8. Version History Summary

| Version | Highlights |
|---|---|
| v2.7.4 → v2.7.5 | SMS priority hardening, intruder selfie via Device Admin, Telegram deep link, Airplane Mode removed, silent grace period, "I'm Safe" no-PIN, language icon moved to sidebar |
| v2.7.6 | Smartwatch Tether feature (full), UI cleanup, IMEI injected into SMS templates |
| v2.7.7 | Static `SmsCommandReceiver`, Smart SIM Trap v1 (had NET_CAPABILITY_VALIDATED freeze bug) |
| v2.7.8 | Coroutine isolation (`SupervisorJob`), lightweight SIM trap (fixed freeze), Plan B PIN commands, build.yml artifact naming fix |
| v2.7.9 | OTA Update feature — `UpdateManager.kt`, sidebar "Check for Update" |
| v2.7.9b | Fixed duplicate SMS (removed dynamic receiver), restored WHERE footer note, Watch Tether TRIGGER ALARM/DISARM buttons, Plan B info badge, ARM first-time guidance dialog |
| v2.7.10 | OTA test version bump; discovered + fixed debug-vs-release signing mismatch blocking OTA installs |
| v2.8.0 | Watch Tether ℹ️ badge (sidebar symmetry), Remote Guide command-label bug fixed, copy-to-clipboard on each command row |
| v2.8.1 | Full EN/FR/AR localization for About screen, lifetime → annual subscription plan, Contact Support wired to Facebook page |
| v2.8.2 | Version bump (OTA delivery attempt — content changes not yet applied) |
| v2.8.3 | Full EN/FR/AR i18n: About screen + Premium screen, lifetime→annual plan, Contact Support→Facebook, all pushed directly via GitHub API |
| v2.8.4 | Fixed Contact Support label display bug, free features list layout (selfie removed from free tier), Telegram section rebuilt with 3 buttons, all via API pushes |
| v2.8.5 | Fixed OTA corrupt file error (MIN_APK_BYTES 1MB→500KB), background OTA notification, Telegram bot /start polling fixed (JSONObject parser), 3-button Telegram UI, version.json updated |
| v2.8.6 | Watch Tether: premium-gated + real BT enable/scan/pair flow (btEnableLauncher, btPermLauncher, device picker dialog); Telegram: simplified to 2 buttons (Connect Bot + Share Bot Link), removed instruction card + Share My Chat ID; 7 new BT string keys in EN/FR/AR |
| v2.8.7 (2026-08-27/28, abandoned) | Face Capture built from scratch (ML Kit `FaceDetection`, headless Camera2 service, Telegram + GPS-SMS delivery) plus fixes for two pre-existing build breakers and four diagnosed runtime bugs. User reported ongoing instability after multiple rounds of fixes; **reverted in full back to the exact v2.8.6 commit** at explicit user request. All Face Capture code, the dormant duplicate self-destruct-viewer pipeline and the four runtime-bug fixes were removed by the revert; the four bugs were later re-fixed individually (see Section 7). |
| v2.8.6 (patched, 2026-08-28) | The literal `v2.8.6` git tag does not build as committed (see Section 7) -- current lineage is that tag plus the one necessary `MainActivity.kt` `findWatchSwitch()` fix. Functionally identical to the original shipped v2.8.6. |
| v2.8.6.1 → v2.8.6.6 (2026-08-28, same day) | Incremental re-fixes on the patched baseline: SmsCommandReceiver/SelfieService/MonitorService reliability fixes re-applied, intruder-photo storage-path fix (app-private `getExternalFilesDir`, Scoped Storage), capture-restart debounce fix, static `PersistentAlertReceiver` for SIM-change/ringer-mode detection surviving process kills, `SelfieService` `foregroundServiceType="camera"` + permission. |
| v2.9.0 (2026-09-03) | Wrong-PIN threshold respected (first pass), `SelfieService` wake lock for remote SELFIE on a locked screen (first pass), FREE vs FULL PROTECTION screen rewritten across EN/FR/AR. |
| v2.9.1 (2026-09-04) | Premium-state-survives-update fix (plain-prefs backup + self-heal + `MY_PACKAGE_REPLACED` receiver), remote SELFIE delivery fix (delayed wake-lock release), Watch Tether crash fixed (permission-check ordering) + switch styling regression re-fixed, wrong-PIN threshold gating tightened, `WHERE` privacy fix, Play Store review/app-update libraries added (inert), `android-actions/setup-android@v3` CI breakage fixed. |
| v2.9.2 (2026-10-05) | SELFIE now 1 photo; battery-optimization exemption prompt on ARM (max 3 prompts); SMS body sanitised; `PackageReplacedReceiver` also restarts `MonitorService` if `running=true`; `SmsCommandReceiver` dedup keyed on PDU content. New `.github/workflows/release.yml`: pushing a `vX.Y.Z` tag builds the signed APK, creates the Release with the APK, then updates `version.json` on `main`. |
| v2.9.3 (2026-10-05) | Family Security Network UI PREVIEW only (`FamilyNetworkActivity`, mock map, Coming Soon dialogs); NO network calls. Real service planned for v3.0.0. |
| v2.9.4 (2026-10-05) | Bottom navigation redesigned as a floating glass pill (3 tabs Gallery / Network / Commands), vector icons `ic_navbar_*`; click IDs unchanged (`btn_selfies`, `btn_family_network`, `btn_remote_guide_icon`). |
| v2.9.5 (2026-10-06) | Remote SMS command reliability: every premium command also replies by SMS to the sender; cooldown 3s per sender+command; commands tolerate trailing punctuation; `lockDeviceSafely()` checks `isAdminActive`; Plan B supports `STOP ALARM <PIN>` and `STATUS <PIN>`; STATUS shows Armed/Premium/Admin + last command outcomes; AlarmService START idempotent, STOP safe. Exact-match parsing kept deliberately (no `.contains()`). |
| v2.9.6 (2026-10-14) | `SelfieService` `PARTIAL_WAKE_LOCK` (15s cap); Telegram photo upload via WorkManager (`TelegramPhotoWorker`, `enqueuePhoto`, backoff, unique job per chat id); Settings SAVE buttons removed, IMEI/Contact fields auto-save; Contact 2 in Activity Log, Watch Tether and Survival Timer SMS; Survival recipient dialog RadioGroup fix; HOW TO USE text rewritten EN/FR/AR. |
| v2.9.7 (2026-10-16) | Telegram bot token moved out of source into the `TELEGRAM_BOT_TOKEN` GitHub Secret (`BuildConfig`); pre-flight lock-screen check before Full Theft Protection ON. A fake-shutdown power-menu screen, an AccessibilityService blocking Settings/admin-revocation, and auto-join of open Wi-Fi were requested and declined as stalkerware-pattern features. |
| v2.9.7.1 (2026-10-16) | SELFIE ON/OFF motion trap (later retired in v2.9.9.3); outgoing SMS wrapped in a brief `PARTIAL_WAKE_LOCK` (`SmsWake.kt`); one-time Transsion auto-start prompt; `UpdateManager.checkForUpdateAutoPopup()` once per process. A raw touch/volume/power-key interception trigger was declined (needs a covert AccessibilityService). |
| v2.9.8 (2026-10-17) | Root cause of the literal "sms_selfie_trap_on" text: `Strings.kt` missing from the patch zip. New persistent 20-entry `ActivityLog.kt` shared by MonitorService and MainActivity; home-screen Activity Log shows last 4 entries. Dual-contact SMS broadcasting audited and confirmed correct (per-sender command replies intentionally go only to the sender). |
| v2.9.9 (2026-10-18) | Data-driven `OEM_GUIDES` auto-start prompts for Transsion, MIUI/HyperOS, ColorOS, EMUI/MagicUI (once per device, `oem_autostart_shown`, fallback to App Info). |
| v2.9.9.1 (2026-10-20) | `SmsWake.send()` targets the default SMS subscription on Android 12+, falls back to plain SmsManager; `SmsSentReceiver` logs real per-part delivery outcomes to the Activity Log. |
| v2.9.9.2 (2026-10-21) | One-time retry through the plain SmsManager on subscription-path failure (deduplicated), PendingIntent request codes from an `AtomicInteger`. |
| v2.9.9.3 (2026-10-22) | SELFIE commands fully retired; new Premium toggle "Power-off attempt selfie" (`ACTION_SHUTDOWN` receiver `PowerOffSelfieReceiver`, best-effort, does not block shutdown). |
| v2.9.9.4 (2026-10-27) | Found the real "error code 32" = `RESULT_NO_DEFAULT_SMS_APP`; added a default-SMS-app prompt (removed again in v2.9.9.5). |
| v2.9.9.5 (2026-10-28) | Field-confirmed: removing the 2nd SIM fixed it (dual-SIM conflict). Removed the prompt; added reactive one-time multi-SIM warning dialog; Activity Log failure lines written once per logical message. |
| v2.9.9.6 (2026-10-30) | Full dark/gold Premium screen redesign (crown header, status pill, 3 plan cards with placeholder USD prices, glass feature list, license activation, success dialog `dialog_subscription_success.xml`). Fixed two string-system bugs before packaging (Strings.kt map vs XML resources). |
| v2.9.9.7 (2026-11-06) | Premium pricing in EUR: Monthly 5.99 €, 3 Months 10.99 € ("Save 16%"), Annual 24.99 € with solid gold `#D4AF37` BEST VALUE badge and dark text; plan cards wrapped in a `layoutDirection="ltr"` container so the order never flips in Arabic; tapping a card copies a ready-made subscription request (plan, price, Device ID) and opens Messenger. Note: 10.99 € is really ~39% off 3 x 5.99 € and 24.99 € ~65% off 12 x 5.99 €; the "Save 16%" label was kept as requested. |
| v2.9.9.8 (2026-11-06) | Subscription request message localized EN/FR/AR (`planMessage()`), `MESSENGER_PAGE` set to Page ID `61576473907901`, BEST VALUE badge moved INSIDE the Annual card (a -9dp margin was clipped by the parent). |
| v2.9.9.9 (2026-11-06) | **Licensing moved to a Cloudflare Worker + KV.** `LicenseManager` rewritten: key + Device ID + nonce over HTTPS, answer signed by the Worker (ECDSA P-256 / SHA-256), app embeds only the public key, signed payload stored and re-verified on every read, expiry shown under the Device ID (date + days left, or Lifetime / À vie / مدى الحياة), master key and the `ILINE2026` derivation REMOVED from the code (legacy activations grandfathered as Lifetime while `ALLOW_LEGACY` is true). Worker + admin dashboard (`/admin`: generate keys per plan, list, search/filter, copy, Unbind, Revoke/Restore) added. |
| v2.9.9.10 (2026-11-06/07) | Precise activation failure reasons on screen: `ServerError(detail)` (e.g. `http 500 signing_failed`) and `BadResponse(reason)` (`signature`/`device`/`nonce`/`version`/`decode:...`) so a failing check is diagnosable. Worker: permanent key DELETE (per key and "Delete all revoked"), Diagnostics button showing the Worker's signing-key fingerprint, and sign-before-bind (a signing failure no longer binds the key). |
| v2.9.10 (2026-11-07) | **Hybrid online licensing.** Worker answers carry a 72 h lease; the app re-checks via read-only `/check` every ~15 min while in use plus a WorkManager job (`tt_license_refresh`, 15 min, network required) so revoke/delete/move reach the phone within minutes; definitive `revoked`/`invalid_key`/`key_in_use` clear the license immediately, 5xx/offline never do. Automatic device migration: entering the key on a new phone moves the binding (3 moves per key per 30 days, expiry unchanged, old phone locked out at its next check, admin Unbind resets). Binding identity = non-exportable Android Keystore EC key (StrongBox first), every request signed with it (`TT2|action|key|device|ts|nonce`), timestamp window 10 min, replay nonce stored 20 min on `/activate`; Android ID demoted to a label. Dashboard shows "n device move(s)". Old clients (<= v2.9.9.10) get `update_required` on `/activate`. Tested with a 29-case simulated suite (migration, limit, replay, tamper, legacy upgrade, revoke/restore/delete, signing failure); Kotlin not compiled outside CI. **The auto-migration part was removed again in v2.9.10.2.** |
| v2.9.10.1 (2026-11-07) | Premium screen asks the server on open and `recreate()`s when the answer changes (it never redrew itself before, which made revocation look broken); new "Last check: dd/MM HH:mm - <result>" line under the Device ID. This line exposed that the deployed Worker was still the old one (`not_found (404)`); after deploying the v2 Worker it showed `ok`; the user then reported the revoke test successful and the key restored with `ok`. |
| v2.9.10.2 (2026-11-07) | **Strict lock-to-device, no auto-migration.** A key binds to the first phone that activates it; any other phone gets `key_in_use` (403) on `/activate` and `/check`, and the app shows "This key is already active on another device. Please contact support." in EN/FR/AR (`ActivationResult.KeyInUse`). Removed: the 3-moves/30-days logic, `migration_limit`, `ActivationResult.MigrationLimit`, the move counter in KV metadata and the dashboard. Only the admin's "Unbind device" frees a key: it clears `device` + `dev_pub` but keeps `activated_at`/`expires_at`, so a transfer never extends a subscription; the old phone is locked out at its next check and the next phone to activate binds. `/check` now also refuses an UNBOUND key (otherwise a leaked unbound key could renew leases from any number of phones). A rejected attempt writes nothing to KV (identity check runs before the replay-nonce write). New constant `ALLOW_REINSTALL_SAME_ANDROID_ID` (default false). Worker tested with a 25-case simulated suite; Kotlin compiled only by CI. |

---

## 9. Pending / Not Yet Done

**Security (do before real customers):**
- [ ] **Rotate the license signing key AND the Worker `ADMIN_TOKEN`** -- both were exposed in chat screenshots on 2026-11-06 (see Section 7). Order matters: generate new files (`openssl`, see Section 11) -> set BOTH Cloudflare secrets -> deploy -> rebuild the app (the build embeds the NEW `public_key.b64`) -> re-enter the license key once on each phone. Do it while only the owner uses the app.
- [ ] **Back up `~/.ttlicense_keys/ec.pem` somewhere safe** (offline). Never paste it or `signing_key.b64` anywhere.
- [ ] **Rotate the GitHub PAT** -- pasted in plaintext in chat many times (2026-08-27/28/09-02/09-04 and later). Treat every occurrence as compromised; rotate at github.com/settings/tokens. Needs `repo` + `workflow` scopes. Still not done.
- [ ] Delete the test keys in `/admin` ("Delete all revoked"); never post keys or screenshots showing keys.
- [ ] Add a Cloudflare WAF rate-limiting rule on `/activate` and `/check` (suggested, not confirmed). Optional extra lock: Cloudflare Zero Trust Access on `/admin*`.
- [ ] Later release: set `ALLOW_LEGACY = false` in `LicenseManager.kt` once customers have moved to server keys (until then pre-server activations stay premium regardless of the server).

**Licensing follow-ups:**
- [ ] **Confirm the v2.9.10.2 build is green and test strict lock on TWO phones:** key bound on phone A; same key on phone B -> "already active on another device, contact support" and A stays premium; admin Unbind -> A loses premium at its next check (instantly when its Premium screen is opened); then B can activate with the same expiry.
- [ ] Confirm the Worker deploy pipeline is really set up (GitHub secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, KV id in `license_worker/wrangler.toml`, a green "Deploy license Worker" run) and that `license_worker/worker.js` in the repo equals the deployed v2 code. If not, the repo copy may be older than what is live.
- [ ] Decide the lease length: `LEASE_SECONDS` in `worker.js` is 72 h. A thief can cut internet to a stolen phone, so a longer lease favours the victim, a shorter one favours anti-sharing. Changing it needs only a Worker deploy.
- [ ] Hardware ATTESTATION is NOT implemented: the Keystore key proves a non-exportable key exists, not that it lives in real hardware (an emulator can make its own key; the move limit contains it). Real attestation = Play Integrity or Keystore key attestation verified on the Worker (large job).
- [ ] Verify the "Save 16%" label: real saving at 10.99 € for 3 months is ~39% and annual ~65% versus monthly.
- [ ] The `price_placeholder_note` text under the Activate button may still say prices are temporary; update it now that EUR prices are final.

**Roadmap / feature pipeline (requested 2026-11-07, design notes in Section 12; none implemented yet):**
- [ ] **Custom Trial Plan (flexible duration):** admin generator + Worker support short-term trial keys with custom durations (e.g. 3 days, 7 days / 1 week) for store demos and testing.
- [ ] **Batch print / export of license tickets:** multi-select checkboxes next to keys in the dashboard + a "Print Selected Tickets" button producing a professional ticket layout (app name, license key, validity) for stores and resellers.
- [ ] **Force Update mechanism:** mandatory, blocking update dialog when the installed build is older than the required version (previously planned).
- Suggested order (NOT decided, confirm first): `v2.9.11` = trial plan + batch print (Worker/dashboard only, almost no app change); `v2.9.12` = force update (app + `version.json` + optional Worker gate).

**Older items (still open):**
- [x] v2.9.1 (versionCode 137): all 9 fixes applied and build-confirmed green
- [x] Removed dormant duplicate Face Capture pipeline and its supporting cruft (gone since the v2.8.6 revert)
- [x] Release flow is tag-driven (`release.yml`): bump `build.gradle`, pull --rebase, push `main`, `git tag vX.Y.Z && git push origin vX.Y.Z`. Do NOT hand-edit `version.json` or run `create_release.sh`.
- [ ] `com.google.android.play:review` and `com.google.android.play:app-update` are dependencies but not wired into any in-app flow.
- [ ] **No Face Capture in this codebase at all currently.** If wanted again, treat it as a fresh implementation and re-apply fixes one at a time with a real device retest after each.
- [ ] MainActivity UI-lag/coroutine optimization for instant shield-color updates on toggle (mentioned once, never delivered -- original v2.7.8 request item 4)
- [ ] Verify `AboutActivity.kt` has no hardcoded version strings (uses `BuildConfig.VERSION_NAME`, not re-verified since v2.7.6)
- [ ] **v2.9.3 Family Network is a UI shell.** Pair code shown is a local random placeholder, not registered anywhere. v3.0.0 must add: relay (Cloudflare is now already in use for licensing and could host it), real pairing, 2-5s live tracking while target ARMED, remote ALARM, geocoded location history, SELFIE delivery via the bridge.
- [ ] Battery-optimization exemption permission may need Play Store policy review before any Play submission.
- [ ] `LicenseManager`'s old silent EncryptedSharedPreferences fallback only matters for the grandfathered legacy flag now; signed licenses live in plain prefs and need no Keystore-wrapped storage.

---

## 10. How to Resume Work With Claude

1. Paste this entire STATE.md at the start of the new conversation.
2. State which version you're moving to and what specifically needs to change.
3. If Claude needs to see a specific file's current content (e.g. to make a precise edit), it will ask — paste via `cat path/to/file`.
4. Claude delivers the changed files (download to `/sdcard/Download`) plus ONE Termux command that verifies the files, copies them in, patches placeholders, bumps the version, commits, pushes and tags. Run it as its own paste, on a clean prompt. Claude does not push to GitHub for you.
5. For licensing work also tell Claude: current Worker URL, whether the Worker deploy pipeline is set up, and the exact text of the Premium screen's "Last check" line. Never paste the signing key, admin token, license keys or PAT.
6. Claude ALWAYS ships an updated STATE.md together with every code or feature change (standing rule): new row in **Section 8**, updated **Section 9**, and architecture notes in **Section 11/12** when they apply. Push it in the same commit. Next version numbers: `v2.9.11`, `v2.9.12`, ... (Section 1).

---

## 11. Licensing Architecture (Cloudflare Worker + KV)

**Worker:** `thievestrap-license`, URL `https://thievestrap-license.ferakalaaeddine.workers.dev`. Source `license_worker/worker.js`. KV namespace bound as `LICENSES`. Secrets (set in Cloudflare, never in the repo): `SIGNING_KEY` (base64 PKCS8 DER of a P-256 private key), `ADMIN_TOKEN` (also the `/admin` password).

**Plans and key format:** `monthly` = 30 days, `3months` = 90, `annual` = 365, `lifetime` (also used for admin keys) = no expiry. Keys look like `TT-XXXXX-XXXXX-XXXXX-XXXXX` (20 chars from a 31-letter alphabet without 0/O/1/I/L, about 99 bits). The clock starts at FIRST activation and is kept across device moves.

**Endpoints**
- `POST /activate` -- user typed a key: binds an UNBOUND key to this phone (or re-confirms the phone it is already bound to); a key bound to another phone gets `key_in_use` (403). Authenticated by a signed request (below), proof of possession of the device key.
- `POST /check` -- read-only periodic re-validation. Can confirm or deny, NEVER changes the binding (a stale old phone cannot take the license back).
- `GET /admin` -- dashboard page (useless without the token). `POST /admin/{ping,generate,list,info,revoke,restore,unbind,delete,pubkey}` -- Bearer `ADMIN_TOKEN`. 10 wrong tokens from one IP block it for 15 minutes.
- Errors: `invalid_key` (404, also after Delete), `revoked`, `key_in_use` (403, moved to another phone), `expired`, `bad_time` (clock off by more than 10 min), `bad_signature`, `update_required` (old client), `replay` (409), `signing_failed` (500, nothing is persisted), `bad_request`.

**Request (v2 client):** `{key, device (Android ID label), nonce, ts, pub (SPKI base64 of the Keystore key), sig (raw r||s base64)}`; signature over `TT2|<action>|<key>|<device>|<ts>|<nonce>` with ECDSA P-256 / SHA-256. The Worker verifies it with the supplied `pub`. `dpk` = first 16 hex chars of SHA-256(SPKI).

**Signed answer:** `{payload, sig}`; payload JSON `{v:2, device, dpk, plan, exp (0 = lifetime), iat, lease, nonce}`, signed by `SIGNING_KEY`. The app verifies the signature with the embedded public key, then `device`, `dpk` (must equal its own key) and `nonce`; the license counts only while `now < exp` (or lifetime) AND `now < lease` (72 h from the last successful answer). v1 payloads from v2.9.9.x are accepted with `iat + 72 h` as the lease and upgrade themselves on the first `/check`.

**Record (KV key `k:<KEY>`):** `plan, created_at, note, device, dev_pub, activated_at, expires_at, revoked`. KV metadata holds a summary (plan, device, dates, revoked, note) for the dashboard list. `n:<nonce>` (replay guard, 20 min TTL) and `rl:<ip>` (admin brute-force counter, 15 min TTL) share the namespace.

**Device lock rules (strict, since v2.9.10.2):** a key is locked to ONE device (Keystore public key `dev_pub`, Android ID `device` as a label). No automatic migration of any kind. Another phone is refused with `key_in_use` (403) on both `/activate` and `/check`; the app tells the user to contact support. Only the admin's Unbind clears `device` + `dev_pub` (the activation date and expiry are kept, so a transfer never extends the license); after Unbind the old phone's `/check` returns `key_in_use` (it is locked out at its next check) and the first phone to activate binds. Unbound keys also fail `/check`. Legacy records bound only by Android ID are accepted for that Android ID and upgraded with the device key on first contact. `ALLOW_REINSTALL_SAME_ANDROID_ID` (default false) is the only opt-in relaxation.

**App behaviour:** `isPremium()` = signed license verified AND not expired AND lease valid (legacy flag only while `ALLOW_LEGACY` and no signed license stored). It triggers a throttled background check (15 min, retry 5 min) and keeps the WorkManager job scheduled. `refresh()` outcomes are written to the "Last check" line: `ok`, `offline`, `revoked (403)`, `key_in_use (403)`, `invalid_key (404)`, `expired (403)`, `bad_signature (400)`, `bad_time (400)`, `not_found (404)` (OLD Worker), `bad reply: <reason>` (signature/key mismatch), `no device key`. Only `revoked`, `invalid_key`, `key_in_use` clear the license; network or 5xx errors never do. Local clock rollback is blunted by a "latest time seen" value that also follows the server `iat`.

**Admin dashboard:** generate (plan, count 1-50, note) with Copy / Copy all, license list with search + status filter (Unused/Active/Expired/Revoked), per key Copy, Unbind device, Revoke / Restore, Delete (permanent; warns on Active keys), "Delete all revoked", Diagnostics (Worker signing-key fingerprint), Sign out. Token kept in `sessionStorage` only.

**Key pair setup / rotation (Termux, contents secret):**
```bash
cd ~/.ttlicense_keys && rm -f ec.pem signing_key.b64 public_key.b64 admin_token.txt
openssl ecparam -name prime256v1 -genkey -noout -out ec.pem
openssl pkcs8 -topk8 -nocrypt -in ec.pem -outform DER | base64 -w0 > signing_key.b64
openssl ec -in ec.pem -pubout -outform DER 2>/dev/null | base64 -w0 > public_key.b64
openssl rand -hex 32 > admin_token.txt
```
Then paste `signing_key.b64` into Cloudflare secret `SIGNING_KEY` and `admin_token.txt` into `ADMIN_TOKEN` (copy by selection, do not screenshot), redeploy, rebuild the app. **Fingerprint check when activation fails with a server error:** `base64 -d ~/.ttlicense_keys/public_key.b64 | sha256sum | cut -c1-12` (key file), the same on the `PUBLIC_KEY_B64` value inside `LicenseManager.kt`, and the dashboard Diagnostics value -- all three must match.

**Honest limits:** nobody can stop someone who patches their own APK to skip the checks; the Keystore proof is not attestation; revocation reaches an online phone within about 15 minutes (instantly when its Premium screen is opened), an offline phone when it reconnects or when its 72 h lease ends.

**Verified on the real phone (2026-11-07):** activation, `Last check ... ok`, revoke then restore in `/admin` (user-reported successful), expiry line "Expires Dec 7, 2026 - 30 days left" for the Monthly key.

---

## 12. Roadmap Design Notes (requested 2026-11-07 -- NOT implemented yet)

Numbering: next releases are `v2.9.11`, `v2.9.12`, ... (Section 1). Everything below is a proposal to confirm before coding; items marked **[decide]** need an answer from the owner.

### 12.1 Custom Trial Plan (flexible duration)
**Goal:** short-term trial keys with a custom duration (3 days, 7 days / 1 week, ...) for store demos and testing, like SaaS trials.
- **Worker:** add a plan `custom` (shown as "Trial"). `/admin/generate` accepts a `days` value and stores it on the record (`rec.days`). On first activation: `days = rec.days ?? PLAN_DAYS[rec.plan]`, `expires_at = now + days*86400`. `PLAN_DAYS` stays for the fixed plans. Validate the value (integer, suggested range **[decide]** 1-90 days; half-day/hours only if wanted).
- **KV metadata** gets `days` so the dashboard list can show "Trial 7d"; no change to the signed payload format (`plan`, `exp` already carry everything the app needs).
- **Dashboard:** plan dropdown gains "Custom trial..." with a number field and quick presets (1 / 3 / 7 / 14 days); same Count and Note fields.
- **App:** essentially unchanged: the Premium screen already shows "Expires <date> - N days left" for any `exp`. Optional: show "Trial" next to it using `plan == "custom"`.
- **Rules that already hold:** strict lock to one device, expiry clock starts at FIRST activation **[decide: keep]**, Unbind keeps the expiry so a demo key cannot be reset into a new trial, revoke/delete work as for any key. Extending a trial = generate a new key (or a future admin "extend" action **[decide]**).
- **Tests to add** to the simulated Worker suite: custom days honoured, out-of-range rejected, expiry exact, `expired` after the time passes, Unbind keeps expiry.

### 12.2 Multi-select + "Print Selected Tickets" (batch export)
**Goal:** bulk printing of license keys for physical distribution to stores and resellers.
- **Dashboard only (client-side), no Worker change needed** apart from `days` metadata from 12.1. A checkbox next to every key in the list, "Select all shown" (respects the search/status filter), a selection counter, and a **Print Selected Tickets** button (disabled with nothing selected). The selection survives filtering. A "Print these" shortcut after Generate is a natural addition.
- **Ticket layout (A4 grid with cut guides):** app name ("THIEVES TRAP by iLine Tech"), the license key in large monospace, validity ("30 days from activation" / "90 days ..." / "1 year ..." / "7-day trial" / "Lifetime"), a short how-to ("Open the app > Premium > enter the key"), and a support line (Facebook page). Optional QR code of the key: needs an inline QR encoder because the page CSP blocks external scripts **[decide]**. Ticket language EN / FR / AR **[decide]**.
- **Implementation notes:** build the tickets into a hidden `#printArea` and print with an `@media print` stylesheet inside the existing nonce'd `<style>`; no inline `style=` attributes or inline handlers (CSP); render text with `textContent`.
- **Safeguards:** warn when selected keys are already Active/bound/Revoked (printing them is pointless); default selection = Unused keys only. A printed key is a bearer secret: the only recall is Revoke/Delete, and the strict device lock limits damage.

### 12.3 Force Update (mandatory blocking dialog)
**Goal:** users on an old build must update before they can use the app UI.
- **Existing pieces:** `version.json` on `main` (`version_code`, `version_name`, `download_url`, rewritten by `release.yml` on every tag) and `UpdateManager` (`checkForUpdateAutoPopup()` once per process, DownloadManager + FileProvider install, `MIN_APK_BYTES` check). Today's dialog is dismissible.
- **Minimum version source:** add `min_version_code` (and an optional message). Warning: `release.yml` rewrites `version.json` with only the three keys, so a `min_version_code` stored there would be wiped on the next release. Either teach `release.yml` to carry it forward, or keep it out of that file (a separate JSON, or a Worker endpoint editable from the dashboard so it can be raised without a release) **[decide]**.
- **App behaviour:** on resume, if `BuildConfig.VERSION_CODE < min_version_code`, show a blocking `AlertDialog` (not cancelable, back disabled, only "Update now" that reuses the existing download/install flow). **Fail open:** if the check cannot be made (offline), do not block; cache the last known minimum.
- **Do NOT block protection:** only the UI is blocked. `MonitorService`, SMS commands, Watch Tether, the SIM trap and alerts must keep running on the old build, because this is an anti-theft app.
- **Optional server gate:** the app could send its `versionCode` in `/activate` and `/check`, and the Worker could refuse builds below a minimum (it already answers `update_required` to the old protocol) **[decide]**.
- **Caveat:** the debug-vs-release keystore signature mismatch (Section 2) still breaks installs; force update only works between release-signed builds.

### Open questions before building
1. Trial: keep "clock starts at first activation"? Allowed range of days?
2. Tickets: QR code? Which language(s)? Include a price/support line?
3. Force update: where does the minimum version live, and is the Worker gate wanted?
4. Order: is `v2.9.11` = trial + tickets and `v2.9.12` = force update acceptable?
