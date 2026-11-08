// Thieves Trap license server (Cloudflare Worker + KV) with built-in admin dashboard
// Bindings required:  KV namespace  LICENSES
// Secrets required:   ADMIN_TOKEN (long random string, also the dashboard password)
//                     SIGNING_KEY (base64 of the PKCS8 DER of the P-256 private key)
//
// Public:   POST /activate        (user enters a key: binds it to the phone if unbound; a DIFFERENT phone gets key_in_use)
//           POST /check           (background re-validation; NEVER changes the binding)
// Admin:    GET  /admin           (dashboard page, useless without the token)
//           POST /admin/generate | list | info | revoke | restore | unbind | delete | pubkey   (Bearer ADMIN_TOKEN)

const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
const PLAN_DAYS = { monthly: 30, "3months": 90, annual: 365, lifetime: 0 };
const LEASE_SECONDS = 72 * 3600;       // how long the app may trust a signed answer without re-checking online
const TIME_TOLERANCE = 600;            // max clock difference (s) between app and server for a request
// STRICT lock-to-device: a key belongs to ONE device until the admin presses "Unbind" in the dashboard.
// There is no automatic migration. Set to true ONLY if you want a reinstall on the SAME phone (same
// Android ID, new Keystore key) to re-bind without admin help. Android ID is spoofable, so default is false.
const ALLOW_REINSTALL_SAME_ANDROID_ID = false;
const MAX_TRIAL_DAYS = 14;         // longest allowed trial key (1..14 days, set at generation)
const MAX_BAD_ATTEMPTS = 10;     // failed admin logins per IP ...
const BAD_ATTEMPT_WINDOW = 900;  // ... per 15 minutes

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const toB64 = (bytes) => btoa(String.fromCharCode(...bytes));
const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

function newKey() {
  let out = "";
  while (out.length < 20) {
    const b = crypto.getRandomValues(new Uint8Array(1))[0];
    if (b < 248) out += ALPHABET[b % 31]; // 248 = 31*8, avoids modulo bias
  }
  return "TT-" + out.match(/.{5}/g).join("-");
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// "ok" | "bad" | "limited"
async function checkAdmin(req, env) {
  const ip = req.headers.get("cf-connecting-ip") || "unknown";
  const rlKey = "rl:" + ip;
  const fails = parseInt((await env.LICENSES.get(rlKey)) || "0", 10);
  if (fails >= MAX_BAD_ATTEMPTS) return "limited";
  const h = req.headers.get("authorization") || "";
  const tok = h.startsWith("Bearer ") ? h.slice(7) : "";
  if (env.ADMIN_TOKEN && safeEqual(tok, env.ADMIN_TOKEN)) return "ok";
  await env.LICENSES.put(rlKey, String(fails + 1), { expirationTtl: BAD_ATTEMPT_WINDOW });
  return "bad";
}

// Length of a key in days: fixed plans from PLAN_DAYS, trial keys from their own stored value (clamped 1..14).
function planDays(rec) {
  if (rec.plan === "trial") return Math.min(Math.max(parseInt(rec.days, 10) || 1, 1), MAX_TRIAL_DAYS);
  return PLAN_DAYS[rec.plan] || 0;
}

const normKey = (k) => String(k || "").trim().toUpperCase();
const getRec = async (env, key) => {
  const raw = await env.LICENSES.get("k:" + key);
  return raw ? JSON.parse(raw) : null;
};
// small summary goes into KV metadata so the dashboard can list licenses without reading every record
const meta = (r) => ({
  plan: r.plan, days: r.days || 0, created_at: r.created_at, device: r.device || null, activated_at: r.activated_at || null,
  expires_at: r.expires_at || 0, revoked: !!r.revoked, note: String(r.note || "").slice(0, 120),
});
const putRec = (env, key, rec) =>
  env.LICENSES.put("k:" + key, JSON.stringify(rec), { metadata: meta(rec) });

async function signPayload(env, payloadObj) {
  const priv = await crypto.subtle.importKey(
    "pkcs8", fromB64(env.SIGNING_KEY), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const bytes = new TextEncoder().encode(JSON.stringify(payloadObj));
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, priv, bytes));
  return { payload: toB64(bytes), sig: toB64(sig) }; // sig = raw r||s (64 bytes)
}

// Public half of the Worker's signing key as SPKI base64 (the exact format the app embeds) + a short fingerprint.
async function pubInfo(env) {
  const priv = await crypto.subtle.importKey(
    "pkcs8", fromB64(env.SIGNING_KEY), { name: "ECDSA", namedCurve: "P-256" }, true, ["sign"]);
  const jwk = await crypto.subtle.exportKey("jwk", priv);
  const unb64u = (v) => fromB64(v.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (v.length % 4)) % 4));
  const prefix = fromB64("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"); // SPKI header for P-256 incl. 0x04
  const spki = new Uint8Array([...prefix, ...unb64u(jwk.x), ...unb64u(jwk.y)]);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", spki));
  const fp = [...hash].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 12);
  return { spki_b64: toB64(spki), fingerprint: fp };
}

const b64ok = (v, min, max) => typeof v === "string" && v.length >= min && v.length <= max && /^[A-Za-z0-9+/]+=*$/.test(v);

// Validates a v2 request: formats, clock window, and proof that the caller holds the private half of `pub`
// (a non-exportable Android Keystore key). Returns { r } on success or { err: Response }.
async function parseRequest(action, body) {
  const key = normKey(body.key);
  const device = String(body.device || "");
  const nonce = String(body.nonce || "");
  const ts = Number(body.ts);
  const pub = body.pub, sig = body.sig;
  if (!body.pub || !body.sig || !body.ts) return { err: json({ error: "update_required" }, 400) };
  if (key.length < 8 || key.length > 64 ||
      !/^[A-Za-z0-9_-]{3,64}$/.test(device) ||
      !/^[A-Za-z0-9_-]{8,64}$/.test(nonce) ||
      !Number.isInteger(ts) || !b64ok(pub, 100, 140) || !b64ok(sig, 80, 100)) return { err: json({ error: "bad_request" }, 400) };
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > TIME_TOLERANCE) return { err: json({ error: "bad_time", server_time: now }, 400) };
  let spki;
  try {
    spki = fromB64(pub);
    const pk = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const msg = new TextEncoder().encode(["TT2", action, key, device, ts, nonce].join("|"));
    const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pk, fromB64(sig), msg);
    if (!ok) return { err: json({ error: "bad_signature" }, 400) };
  } catch (e) { return { err: json({ error: "bad_request" }, 400) }; }
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", spki));
  const dpk = [...hash].map((x) => x.toString(16).padStart(2, "0")).join("").slice(0, 16);
  return { r: { key, device, nonce, pub, dpk, now } };
}

async function signedAnswer(env, rec, r) {
  return signPayload(env, {
    v: 2, device: r.device, dpk: r.dpk, plan: rec.plan, exp: rec.expires_at || 0,
    iat: r.now, lease: r.now + LEASE_SECONDS, nonce: r.nonce,
  });
}

// Is the caller the device this key is currently bound to?
function isBoundDevice(rec, r) {
  if (rec.dev_pub) return rec.dev_pub === r.pub;
  return !rec.device || rec.device === r.device; // older records bound by Android ID only
}

// ACTIVATE: binds an UNBOUND key to the calling phone. A key bound to another phone is refused with
// key_in_use (403): only the admin can free it ("Unbind device" in /admin).
async function activate(body, env) {
  const p = await parseRequest("activate", body);
  if (p.err) return p.err;
  const r = p.r;

  const rec = await getRec(env, r.key);
  if (!rec) return json({ error: "invalid_key" }, 404);
  if (rec.revoked) return json({ error: "revoked" }, 403);

  const bound = !!(rec.device || rec.dev_pub);
  const sameDevice = bound && (isBoundDevice(rec, r) ||
    (ALLOW_REINSTALL_SAME_ANDROID_ID && rec.device === r.device));
  if (bound && !sameDevice) return json({ error: "key_in_use" }, 403); // checked before any KV write

  // replay guard (only for authenticated, state-changing calls: a valid key must exist)
  if (await env.LICENSES.get("n:" + r.nonce)) return json({ error: "replay" }, 409);
  await env.LICENSES.put("n:" + r.nonce, "1", { expirationTtl: 2 * TIME_TOLERANCE });

  let dirty = false;
  if (!rec.activated_at) {
    rec.activated_at = r.now;
    const days = planDays(rec);
    rec.expires_at = days ? r.now + days * 86400 : 0; // 0 = lifetime
    dirty = true;
  }
  if (rec.expires_at && r.now >= rec.expires_at) return json({ error: "expired" }, 403);

  if (rec.dev_pub !== r.pub || rec.device !== r.device) { // first bind, legacy upgrade, or allowed reinstall
    rec.dev_pub = r.pub; rec.device = r.device;
    dirty = true;
  }

  // Sign first: a broken SIGNING_KEY must never leave a key bound to a device.
  let signed;
  try { signed = await signedAnswer(env, rec, r); }
  catch (e) { return json({ error: "signing_failed" }, 500); }
  if (dirty) await putRec(env, r.key, rec);
  return json({ ok: true, ...signed });
}

// CHECK: periodic re-validation from the app. Read-only: it can only confirm or deny, never rebind.
async function check(body, env) {
  const p = await parseRequest("check", body);
  if (p.err) return p.err;
  const r = p.r;

  const rec = await getRec(env, r.key);
  if (!rec) return json({ error: "invalid_key" }, 404);       // deleted in the dashboard
  if (rec.revoked) return json({ error: "revoked" }, 403);
  // Not bound to THIS phone (bound elsewhere, or just unbound by the admin): this phone is locked out.
  if (!(rec.device || rec.dev_pub) || !isBoundDevice(rec, r)) return json({ error: "key_in_use" }, 403);
  if (rec.expires_at && r.now >= rec.expires_at) return json({ error: "expired" }, 403);
  if (!rec.dev_pub && rec.device === r.device) {               // one-time upgrade of an older record
    rec.dev_pub = r.pub;
    await putRec(env, r.key, rec);
  }
  try { return json({ ok: true, ...(await signedAnswer(env, rec, r)) }); }
  catch (e) { return json({ error: "signing_failed" }, 500); }
}

async function admin(path, body, env) {
  if (path === "/admin/ping") return json({ ok: true });

  if (path === "/admin/pubkey") {
    try { return json({ ok: true, ...(await pubInfo(env)) }); }
    catch (e) { return json({ error: "signing_key_invalid" }, 500); }
  }

  if (path === "/admin/generate") {
    const plan = String(body.plan || "");
    if (!(plan in PLAN_DAYS) && plan !== "trial") return json({ error: "bad_plan", allowed: [...Object.keys(PLAN_DAYS), "trial"] }, 400);
    let trialDays = 0;
    if (plan === "trial") {
      trialDays = Number(body.days);
      if (!Number.isInteger(trialDays) || trialDays < 1 || trialDays > MAX_TRIAL_DAYS) {
        return json({ error: "bad_days", min: 1, max: MAX_TRIAL_DAYS }, 400);
      }
    }
    const count = Math.min(Math.max(parseInt(body.count || 1, 10) || 1, 1), 50);
    const keys = [];
    for (let i = 0; i < count; i++) {
      let key;
      do { key = newKey(); } while (await getRec(env, key));
      await putRec(env, key, {
        plan, days: trialDays, created_at: Math.floor(Date.now() / 1000), note: String(body.note || ""),
        device: null, dev_pub: null, activated_at: null, expires_at: null, revoked: false,
      });
      keys.push(key);
    }
    return json({ ok: true, plan, days: trialDays, keys });
  }

  if (path === "/admin/list") {
    const limit = Math.min(Math.max(parseInt(body.limit || 100, 10) || 100, 1), 200);
    const res = await env.LICENSES.list({ prefix: "k:", limit, cursor: body.cursor || undefined });
    return json({
      ok: true,
      items: res.keys.map((k) => ({ key: k.name.slice(2), ...(k.metadata || {}) })),
      cursor: res.list_complete ? null : res.cursor,
    });
  }

  const key = normKey(body.key);
  const rec = await getRec(env, key);
  if (!rec) return json({ error: "invalid_key" }, 404);
  if (path === "/admin/info") return json({ ok: true, key, ...rec });
  if (path === "/admin/delete") { await env.LICENSES.delete("k:" + key); return json({ ok: true, key, deleted: true }); } // permanent
  if (path === "/admin/revoke") { rec.revoked = true; await putRec(env, key, rec); return json({ ok: true, key, revoked: true }); }
  if (path === "/admin/restore") { rec.revoked = false; await putRec(env, key, rec); return json({ ok: true, key, revoked: false }); }
  if (path === "/admin/unbind") { rec.device = null; rec.dev_pub = null; await putRec(env, key, rec); return json({ ok: true, key, device: null }); } // expiry is kept
  return json({ error: "not_found" }, 404);
}

// ---------------------------------------------------------------- dashboard page

function dashboard() {
  const n = toB64(crypto.getRandomValues(new Uint8Array(16)));
  const html = PAGE.replaceAll("__NONCE__", n);
  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-frame-options": "DENY",
      "x-robots-tag": "noindex, nofollow",
      "referrer-policy": "no-referrer",
      "content-security-policy":
        "default-src 'none'; script-src 'nonce-" + n + "'; style-src 'nonce-" + n +
        "'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}

const PAGE = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Thieves Trap - License Admin</title>
<style nonce="__NONCE__">
*{box-sizing:border-box}
[hidden]{display:none!important}
body{margin:0;background:#0a0a0b;color:#e8e8ea;font:15px/1.4 system-ui,sans-serif;padding:16px;max-width:760px;margin:0 auto}
h1{color:#ffd700;font-size:20px;margin:8px 0 16px}
h2{color:#ffd700;font-size:15px;margin:0 0 10px}
.card{background:#14141a;border:1px solid #2a2a30;border-radius:12px;padding:14px;margin-bottom:14px}
label{display:block;font-size:12px;color:#8e8e93;margin:8px 0 4px}
input,select{width:100%;padding:11px;border-radius:8px;border:1px solid #333338;background:#0a0a0b;color:#fff;font-size:15px}
button{padding:10px 14px;border-radius:8px;border:0;background:#d4af37;color:#0a0a0b;font-weight:700;font-size:14px;cursor:pointer}
button.sec{background:#26262c;color:#e8e8ea}
button.dan{background:#5a1010;color:#ff8a80}
button:disabled{opacity:.5}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.grow{flex:1;min-width:120px}
.key{font:700 16px ui-monospace,monospace;color:#ffd700;word-break:break-all}
.newkey{display:flex;gap:8px;align-items:center;justify-content:space-between;background:#0a0a0b;border:1px solid #d4af37;border-radius:8px;padding:10px;margin-top:8px}
.msg{font-size:13px;margin-top:8px;color:#ff8a80}
.ok{color:#66bb6a}
.small{font-size:12px;color:#8e8e93}
.badge{display:inline-block;padding:2px 8px;border-radius:99px;font-size:11px;font-weight:700}
.b-Active{background:#12301a;color:#66bb6a}.b-Unused{background:#222;color:#c0c0c8}
.b-Expired{background:#3a2a0a;color:#ffa726}.b-Revoked{background:#3a1010;color:#ef5350}
.item{border-top:1px solid #2a2a30;padding:10px 0}
.item:first-child{border-top:0}
.mt{margin-top:10px}.w90{width:90px}.w130{width:130px}
.acts{margin-top:8px;display:flex;gap:8px;flex-wrap:wrap}
input[type=checkbox]{width:22px;height:22px;padding:0;flex:none;accent-color:#d4af37}
.chk{display:flex;align-items:center;gap:8px;margin:0;font-size:13px;color:#e8e8ea}
.pre{padding:7px 12px}
#printArea{display:none}
.ticket{box-sizing:border-box;width:94mm;height:50mm;border:0.3mm dashed #888;border-radius:3mm;overflow:hidden;display:flex;flex-direction:column;background:#fff;color:#111;break-inside:avoid;page-break-inside:avoid;font-family:system-ui,Arial,sans-serif}
.tk-head{background:#0a0a0b;color:#d4af37;padding:1.6mm 3mm;display:flex;justify-content:space-between;align-items:baseline;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.tk-brand{font-weight:800;letter-spacing:.3mm;font-size:11pt}
.tk-by{font-size:6.5pt;color:#c9c9cf}
.tk-body{flex:1;display:flex;align-items:center;gap:3mm;padding:2mm 3mm;min-height:0}
.tk-left{flex:1;min-width:0}
.tk-label{font-size:6pt;letter-spacing:.3mm;color:#666}
.tk-key{font:700 9pt ui-monospace,Menlo,Consolas,monospace;margin:0.8mm 0 1.4mm;word-break:break-all;color:#000}
.tk-valid{font-size:7.5pt;font-weight:700;color:#7a5c00}
.tk-how{font-size:6pt;color:#555;margin-top:1mm;line-height:1.25}
.tk-qr{flex:none;width:28mm;height:28mm}
.tk-qr svg{width:100%;height:100%;display:block}
.tk-foot{border-top:0.2mm solid #d4af37;padding:1mm 3mm;display:flex;justify-content:space-between;font-size:6pt;color:#555}
@media print{
@page{size:A4;margin:8mm}
html,body{background:#fff!important;color:#000!important;margin:0!important;padding:0!important;max-width:none!important}
body>*:not(#printArea){display:none!important}
#printArea{display:grid!important;grid-template-columns:94mm 94mm;gap:6mm;justify-content:center;align-content:start}
}
</style></head><body>
<h1>Thieves Trap - License Admin</h1>

<div id="login" class="card">
  <h2>Sign in</h2>
  <label for="tok">Admin token</label>
  <input id="tok" type="password" autocomplete="current-password">
  <div class="row mt"><button id="btnLogin">Sign in</button></div>
  <div id="loginMsg" class="msg"></div>
</div>

<div id="app" hidden>
  <div class="card">
    <h2>Generate keys</h2>
    <div class="row">
      <div class="grow"><label for="plan">Plan</label>
        <select id="plan">
          <option value="monthly">Monthly (30 days)</option>
          <option value="3months">3 Months (90 days)</option>
          <option value="annual">Annual (365 days)</option>
          <option value="lifetime">Lifetime / Admin</option>
          <option value="trial">Trial (custom days, max 14)</option>
        </select></div>
      <div class="w90"><label for="count">Count</label><input id="count" type="number" min="1" max="50" value="1"></div>
    </div>
    <div id="trialBox" hidden>
      <div class="row">
        <div class="w130"><label for="days">Trial days (1-14)</label><input id="days" type="number" min="1" max="14" value="7"></div>
        <button class="sec pre" data-days="1">1 day</button>
        <button class="sec pre" data-days="3">3 days</button>
        <button class="sec pre" data-days="5">5 days</button>
        <button class="sec pre" data-days="7">7 days</button>
        <button class="sec pre" data-days="14">14 days</button>
      </div>
      <div class="small mt">The trial clock starts when the key is first activated on a phone.</div>
    </div>
    <label for="note">Note (optional, e.g. customer name)</label>
    <input id="note" maxlength="120">
    <div class="row mt"><button id="btnGen">Generate</button></div>
    <div id="genMsg" class="msg"></div>
    <div id="genOut"></div>
    <div class="row mt" id="copyAllRow" hidden><button class="sec" id="btnCopyAll">Copy all</button><button id="btnPrintNew">Print these tickets</button></div>
  </div>

  <div class="card">
    <h2>Licenses</h2>
    <div class="row">
      <div class="grow"><input id="search" placeholder="Search key, note or device"></div>
      <div class="w130"><select id="filter">
        <option value="">All</option><option>Unused</option><option>Active</option><option>Expired</option><option>Revoked</option>
      </select></div>
    </div>
    <div class="row mt">
      <label class="chk"><input type="checkbox" id="selAll"> Select all shown</label>
      <span id="selCount" class="small grow">0 selected</span>
      <button id="btnPrint" disabled>Print Selected Tickets</button>
    </div>
    <div id="list"></div>
    <div id="listMsg" class="small mt"></div>
    <div id="diag" class="small mt"></div>
    <div class="row mt">
      <button class="sec" id="btnMore" hidden>Load more</button>
      <button class="sec" id="btnRefresh">Refresh</button>
      <button class="dan" id="btnPurge">Delete all revoked</button>
      <button class="sec" id="btnDiag">Diagnostics</button>
      <button class="sec" id="btnLogout">Sign out</button>
    </div>
  </div>
</div>

<div id="printArea"></div>

<script nonce="__NONCE__">
/* QR Code Generator for JavaScript, Copyright (c) 2009 Kazuhiko Arase, MIT license (qrcode-generator 2.0.4, minified) */
var qrcode=function(){var t=function(t,r){var e=t,n=g[r],o=null,i=0,a=null,u=[],f={},c=function(t,r){o=function(t){for(var r=new Array(t),e=0;e<t;e+=1){r[e]=new Array(t);for(var n=0;n<t;n+=1)r[e][n]=null}return r}(i=4*e+17),l(0,0),l(i-7,0),l(0,i-7),s(),h(),d(t,r),e>=7&&v(t),null==a&&(a=p(e,n,u)),w(a,r)},l=function(t,r){for(var e=-1;e<=7;e+=1)if(!(t+e<=-1||i<=t+e))for(var n=-1;n<=7;n+=1)r+n<=-1||i<=r+n||(o[t+e][r+n]=0<=e&&e<=6&&(0==n||6==n)||0<=n&&n<=6&&(0==e||6==e)||2<=e&&e<=4&&2<=n&&n<=4)},h=function(){for(var t=8;t<i-8;t+=1)null==o[t][6]&&(o[t][6]=t%2==0);for(var r=8;r<i-8;r+=1)null==o[6][r]&&(o[6][r]=r%2==0)},s=function(){for(var t=B.getPatternPosition(e),r=0;r<t.length;r+=1)for(var n=0;n<t.length;n+=1){var i=t[r],a=t[n];if(null==o[i][a])for(var u=-2;u<=2;u+=1)for(var f=-2;f<=2;f+=1)o[i+u][a+f]=-2==u||2==u||-2==f||2==f||0==u&&0==f}},v=function(t){for(var r=B.getBCHTypeNumber(e),n=0;n<18;n+=1){var a=!t&&1==(r>>n&1);o[Math.floor(n/3)][n%3+i-8-3]=a}for(n=0;n<18;n+=1){a=!t&&1==(r>>n&1);o[n%3+i-8-3][Math.floor(n/3)]=a}},d=function(t,r){for(var e=n<<3|r,a=B.getBCHTypeInfo(e),u=0;u<15;u+=1){var f=!t&&1==(a>>u&1);u<6?o[u][8]=f:u<8?o[u+1][8]=f:o[i-15+u][8]=f}for(u=0;u<15;u+=1){f=!t&&1==(a>>u&1);u<8?o[8][i-u-1]=f:u<9?o[8][15-u-1+1]=f:o[8][15-u-1]=f}o[i-8][8]=!t},w=function(t,r){for(var e=-1,n=i-1,a=7,u=0,f=B.getMaskFunction(r),c=i-1;c>0;c-=2)for(6==c&&(c-=1);;){for(var g=0;g<2;g+=1)if(null==o[n][c-g]){var l=!1;u<t.length&&(l=1==(t[u]>>>a&1)),f(n,c-g)&&(l=!l),o[n][c-g]=l,-1==(a-=1)&&(u+=1,a=7)}if((n+=e)<0||i<=n){n-=e,e=-e;break}}},p=function(t,r,e){for(var n=A.getRSBlocks(t,r),o=b(),i=0;i<e.length;i+=1){var a=e[i];o.put(a.getMode(),4),o.put(a.getLength(),B.getLengthInBits(a.getMode(),t)),a.write(o)}var u=0;for(i=0;i<n.length;i+=1)u+=n[i].dataCount;if(o.getLengthInBits()>8*u)throw"code length overflow. ("+o.getLengthInBits()+">"+8*u+")";for(o.getLengthInBits()+4<=8*u&&o.put(0,4);o.getLengthInBits()%8!=0;)o.putBit(!1);for(;!(o.getLengthInBits()>=8*u||(o.put(236,8),o.getLengthInBits()>=8*u));)o.put(17,8);return function(t,r){for(var e=0,n=0,o=0,i=new Array(r.length),a=new Array(r.length),u=0;u<r.length;u+=1){var f=r[u].dataCount,c=r[u].totalCount-f;n=Math.max(n,f),o=Math.max(o,c),i[u]=new Array(f);for(var g=0;g<i[u].length;g+=1)i[u][g]=255&t.getBuffer()[g+e];e+=f;var l=B.getErrorCorrectPolynomial(c),h=k(i[u],l.getLength()-1).mod(l);for(a[u]=new Array(l.getLength()-1),g=0;g<a[u].length;g+=1){var s=g+h.getLength()-a[u].length;a[u][g]=s>=0?h.getAt(s):0}}var v=0;for(g=0;g<r.length;g+=1)v+=r[g].totalCount;var d=new Array(v),w=0;for(g=0;g<n;g+=1)for(u=0;u<r.length;u+=1)g<i[u].length&&(d[w]=i[u][g],w+=1);for(g=0;g<o;g+=1)for(u=0;u<r.length;u+=1)g<a[u].length&&(d[w]=a[u][g],w+=1);return d}(o,n)};f.addData=function(t,r){var e=null;switch(r=r||"Byte"){case"Numeric":e=M(t);break;case"Alphanumeric":e=x(t);break;case"Byte":e=m(t);break;case"Kanji":e=L(t);break;default:throw"mode:"+r}u.push(e),a=null},f.isDark=function(t,r){if(t<0||i<=t||r<0||i<=r)throw t+","+r;return o[t][r]},f.getModuleCount=function(){return i},f.make=function(){if(e<1){for(var t=1;t<40;t++){for(var r=A.getRSBlocks(t,n),o=b(),i=0;i<u.length;i++){var a=u[i];o.put(a.getMode(),4),o.put(a.getLength(),B.getLengthInBits(a.getMode(),t)),a.write(o)}var g=0;for(i=0;i<r.length;i++)g+=r[i].dataCount;if(o.getLengthInBits()<=8*g)break}e=t}c(!1,function(){for(var t=0,r=0,e=0;e<8;e+=1){c(!0,e);var n=B.getLostPoint(f);(0==e||t>n)&&(t=n,r=e)}return r}())},f.createTableTag=function(t,r){t=t||2;var e="";e+='<table style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: "+(r=void 0===r?4*t:r)+"px;",e+='">',e+="<tbody>";for(var n=0;n<f.getModuleCount();n+=1){e+="<tr>";for(var o=0;o<f.getModuleCount();o+=1)e+='<td style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: 0px;",e+=" width: "+t+"px;",e+=" height: "+t+"px;",e+=" background-color: ",e+=f.isDark(n,o)?"#000000":"#ffffff",e+=";",e+='"/>';e+="</tr>"}return e+="</tbody>",e+="</table>"},f.createSvgTag=function(t,r,e,n){var o={};"object"==typeof arguments[0]&&(t=(o=arguments[0]).cellSize,r=o.margin,e=o.alt,n=o.title),t=t||2,r=void 0===r?4*t:r,(e="string"==typeof e?{text:e}:e||{}).text=e.text||null,e.id=e.text?e.id||"qrcode-description":null,(n="string"==typeof n?{text:n}:n||{}).text=n.text||null,n.id=n.text?n.id||"qrcode-title":null;var i,a,u,c,g=f.getModuleCount()*t+2*r,l="";for(c="l"+t+",0 0,"+t+" -"+t+",0 0,-"+t+"z ",l+='<svg version="1.1" xmlns="http://www.w3.org/2000/svg"',l+=o.scalable?"":' width="'+g+'px" height="'+g+'px"',l+=' viewBox="0 0 '+g+" "+g+'" ',l+=' preserveAspectRatio="xMinYMin meet"',l+=n.text||e.text?' role="img" aria-labelledby="'+y([n.id,e.id].join(" ").trim())+'"':"",l+=">",l+=n.text?'<title id="'+y(n.id)+'">'+y(n.text)+"</title>":"",l+=e.text?'<description id="'+y(e.id)+'">'+y(e.text)+"</description>":"",l+='<rect width="100%" height="100%" fill="white" cx="0" cy="0"/>',l+='<path d="',a=0;a<f.getModuleCount();a+=1)for(u=a*t+r,i=0;i<f.getModuleCount();i+=1)f.isDark(a,i)&&(l+="M"+(i*t+r)+","+u+c);return l+='" stroke="transparent" fill="black"/>',l+="</svg>"},f.createDataURL=function(t,r){t=t||2,r=void 0===r?4*t:r;var e=f.getModuleCount()*t+2*r,n=r,o=e-r;return I(e,e,function(r,e){if(n<=r&&r<o&&n<=e&&e<o){var i=Math.floor((r-n)/t),a=Math.floor((e-n)/t);return f.isDark(a,i)?0:1}return 1})},f.createImgTag=function(t,r,e){t=t||2,r=void 0===r?4*t:r;var n=f.getModuleCount()*t+2*r,o="";return o+="<img",o+=' src="',o+=f.createDataURL(t,r),o+='"',o+=' width="',o+=n,o+='"',o+=' height="',o+=n,o+='"',e&&(o+=' alt="',o+=y(e),o+='"'),o+="/>"};var y=function(t){for(var r="",e=0;e<t.length;e+=1){var n=t.charAt(e);switch(n){case"<":r+="&lt;";break;case">":r+="&gt;";break;case"&":r+="&amp;";break;case'"':r+="&quot;";break;default:r+=n}}return r};return f.createASCII=function(t,r){if((t=t||1)<2)return function(t){t=void 0===t?2:t;var r,e,n,o,i,a=1*f.getModuleCount()+2*t,u=t,c=a-t,g={"██":"█","█ ":"▀"," █":"▄","  ":" "},l={"██":"▀","█ ":"▀"," █":" ","  ":" "},h="";for(r=0;r<a;r+=2){for(n=Math.floor((r-u)/1),o=Math.floor((r+1-u)/1),e=0;e<a;e+=1)i="█",u<=e&&e<c&&u<=r&&r<c&&f.isDark(n,Math.floor((e-u)/1))&&(i=" "),u<=e&&e<c&&u<=r+1&&r+1<c&&f.isDark(o,Math.floor((e-u)/1))?i+=" ":i+="█",h+=t<1&&r+1>=c?l[i]:g[i];h+="\\n"}return a%2&&t>0?h.substring(0,h.length-a-1)+Array(a+1).join("▀"):h.substring(0,h.length-1)}(r);t-=1,r=void 0===r?2*t:r;var e,n,o,i,a=f.getModuleCount()*t+2*r,u=r,c=a-r,g=Array(t+1).join("██"),l=Array(t+1).join("  "),h="",s="";for(e=0;e<a;e+=1){for(o=Math.floor((e-u)/t),s="",n=0;n<a;n+=1)i=1,u<=n&&n<c&&u<=e&&e<c&&f.isDark(o,Math.floor((n-u)/t))&&(i=0),s+=i?g:l;for(o=0;o<t;o+=1)h+=s+"\\n"}return h.substring(0,h.length-1)},f.renderTo2dContext=function(t,r){r=r||2;for(var e=f.getModuleCount(),n=0;n<e;n++)for(var o=0;o<e;o++)t.fillStyle=f.isDark(n,o)?"black":"white",t.fillRect(o*r,n*r,r,r)},f};t.stringToBytes=(t.stringToBytesFuncs={default:function(t){for(var r=[],e=0;e<t.length;e+=1){var n=t.charCodeAt(e);r.push(255&n)}return r}}).default,t.createStringToBytes=function(t,r){var e=function(){for(var e=S(t),n=function(){var t=e.read();if(-1==t)throw"eof";return t},o=0,i={};;){var a=e.read();if(-1==a)break;var u=n(),f=n()<<8|n();i[String.fromCharCode(a<<8|u)]=f,o+=1}if(o!=r)throw o+" != "+r;return i}(),n="?".charCodeAt(0);return function(t){for(var r=[],o=0;o<t.length;o+=1){var i=t.charCodeAt(o);if(i<128)r.push(i);else{var a=e[t.charAt(o)];"number"==typeof a?(255&a)==a?r.push(a):(r.push(a>>>8),r.push(255&a)):r.push(n)}}return r}};var r,e,n,o,i,a=1,u=2,f=4,c=8,g={L:1,M:0,Q:3,H:2},l=0,h=1,s=2,v=3,d=4,w=5,p=6,y=7,B=(r=[[],[6,18],[6,22],[6,26],[6,30],[6,34],[6,22,38],[6,24,42],[6,26,46],[6,28,50],[6,30,54],[6,32,58],[6,34,62],[6,26,46,66],[6,26,48,70],[6,26,50,74],[6,30,54,78],[6,30,56,82],[6,30,58,86],[6,34,62,90],[6,28,50,72,94],[6,26,50,74,98],[6,30,54,78,102],[6,28,54,80,106],[6,32,58,84,110],[6,30,58,86,114],[6,34,62,90,118],[6,26,50,74,98,122],[6,30,54,78,102,126],[6,26,52,78,104,130],[6,30,56,82,108,134],[6,34,60,86,112,138],[6,30,58,86,114,142],[6,34,62,90,118,146],[6,30,54,78,102,126,150],[6,24,50,76,102,128,154],[6,28,54,80,106,132,158],[6,32,58,84,110,136,162],[6,26,54,82,110,138,166],[6,30,58,86,114,142,170]],e=1335,n=7973,i=function(t){for(var r=0;0!=t;)r+=1,t>>>=1;return r},(o={}).getBCHTypeInfo=function(t){for(var r=t<<10;i(r)-i(e)>=0;)r^=e<<i(r)-i(e);return 21522^(t<<10|r)},o.getBCHTypeNumber=function(t){for(var r=t<<12;i(r)-i(n)>=0;)r^=n<<i(r)-i(n);return t<<12|r},o.getPatternPosition=function(t){return r[t-1]},o.getMaskFunction=function(t){switch(t){case l:return function(t,r){return(t+r)%2==0};case h:return function(t,r){return t%2==0};case s:return function(t,r){return r%3==0};case v:return function(t,r){return(t+r)%3==0};case d:return function(t,r){return(Math.floor(t/2)+Math.floor(r/3))%2==0};case w:return function(t,r){return t*r%2+t*r%3==0};case p:return function(t,r){return(t*r%2+t*r%3)%2==0};case y:return function(t,r){return(t*r%3+(t+r)%2)%2==0};default:throw"bad maskPattern:"+t}},o.getErrorCorrectPolynomial=function(t){for(var r=k([1],0),e=0;e<t;e+=1)r=r.multiply(k([1,C.gexp(e)],0));return r},o.getLengthInBits=function(t,r){if(1<=r&&r<10)switch(t){case a:return 10;case u:return 9;case f:case c:return 8;default:throw"mode:"+t}else if(r<27)switch(t){case a:return 12;case u:return 11;case f:return 16;case c:return 10;default:throw"mode:"+t}else{if(!(r<41))throw"type:"+r;switch(t){case a:return 14;case u:return 13;case f:return 16;case c:return 12;default:throw"mode:"+t}}},o.getLostPoint=function(t){for(var r=t.getModuleCount(),e=0,n=0;n<r;n+=1)for(var o=0;o<r;o+=1){for(var i=0,a=t.isDark(n,o),u=-1;u<=1;u+=1)if(!(n+u<0||r<=n+u))for(var f=-1;f<=1;f+=1)o+f<0||r<=o+f||0==u&&0==f||a==t.isDark(n+u,o+f)&&(i+=1);i>5&&(e+=3+i-5)}for(n=0;n<r-1;n+=1)for(o=0;o<r-1;o+=1){var c=0;t.isDark(n,o)&&(c+=1),t.isDark(n+1,o)&&(c+=1),t.isDark(n,o+1)&&(c+=1),t.isDark(n+1,o+1)&&(c+=1),0!=c&&4!=c||(e+=3)}for(n=0;n<r;n+=1)for(o=0;o<r-6;o+=1)t.isDark(n,o)&&!t.isDark(n,o+1)&&t.isDark(n,o+2)&&t.isDark(n,o+3)&&t.isDark(n,o+4)&&!t.isDark(n,o+5)&&t.isDark(n,o+6)&&(e+=40);for(o=0;o<r;o+=1)for(n=0;n<r-6;n+=1)t.isDark(n,o)&&!t.isDark(n+1,o)&&t.isDark(n+2,o)&&t.isDark(n+3,o)&&t.isDark(n+4,o)&&!t.isDark(n+5,o)&&t.isDark(n+6,o)&&(e+=40);var g=0;for(o=0;o<r;o+=1)for(n=0;n<r;n+=1)t.isDark(n,o)&&(g+=1);return e+=Math.abs(100*g/r/r-50)/5*10},o),C=function(){for(var t=new Array(256),r=new Array(256),e=0;e<8;e+=1)t[e]=1<<e;for(e=8;e<256;e+=1)t[e]=t[e-4]^t[e-5]^t[e-6]^t[e-8];for(e=0;e<255;e+=1)r[t[e]]=e;var n={glog:function(t){if(t<1)throw"glog("+t+")";return r[t]},gexp:function(r){for(;r<0;)r+=255;for(;r>=256;)r-=255;return t[r]}};return n}();function k(t,r){if(void 0===t.length)throw t.length+"/"+r;var e=function(){for(var e=0;e<t.length&&0==t[e];)e+=1;for(var n=new Array(t.length-e+r),o=0;o<t.length-e;o+=1)n[o]=t[o+e];return n}(),n={getAt:function(t){return e[t]},getLength:function(){return e.length},multiply:function(t){for(var r=new Array(n.getLength()+t.getLength()-1),e=0;e<n.getLength();e+=1)for(var o=0;o<t.getLength();o+=1)r[e+o]^=C.gexp(C.glog(n.getAt(e))+C.glog(t.getAt(o)));return k(r,0)},mod:function(t){if(n.getLength()-t.getLength()<0)return n;for(var r=C.glog(n.getAt(0))-C.glog(t.getAt(0)),e=new Array(n.getLength()),o=0;o<n.getLength();o+=1)e[o]=n.getAt(o);for(o=0;o<t.getLength();o+=1)e[o]^=C.gexp(C.glog(t.getAt(o))+r);return k(e,0).mod(t)}};return n}var A=function(){var t=[[1,26,19],[1,26,16],[1,26,13],[1,26,9],[1,44,34],[1,44,28],[1,44,22],[1,44,16],[1,70,55],[1,70,44],[2,35,17],[2,35,13],[1,100,80],[2,50,32],[2,50,24],[4,25,9],[1,134,108],[2,67,43],[2,33,15,2,34,16],[2,33,11,2,34,12],[2,86,68],[4,43,27],[4,43,19],[4,43,15],[2,98,78],[4,49,31],[2,32,14,4,33,15],[4,39,13,1,40,14],[2,121,97],[2,60,38,2,61,39],[4,40,18,2,41,19],[4,40,14,2,41,15],[2,146,116],[3,58,36,2,59,37],[4,36,16,4,37,17],[4,36,12,4,37,13],[2,86,68,2,87,69],[4,69,43,1,70,44],[6,43,19,2,44,20],[6,43,15,2,44,16],[4,101,81],[1,80,50,4,81,51],[4,50,22,4,51,23],[3,36,12,8,37,13],[2,116,92,2,117,93],[6,58,36,2,59,37],[4,46,20,6,47,21],[7,42,14,4,43,15],[4,133,107],[8,59,37,1,60,38],[8,44,20,4,45,21],[12,33,11,4,34,12],[3,145,115,1,146,116],[4,64,40,5,65,41],[11,36,16,5,37,17],[11,36,12,5,37,13],[5,109,87,1,110,88],[5,65,41,5,66,42],[5,54,24,7,55,25],[11,36,12,7,37,13],[5,122,98,1,123,99],[7,73,45,3,74,46],[15,43,19,2,44,20],[3,45,15,13,46,16],[1,135,107,5,136,108],[10,74,46,1,75,47],[1,50,22,15,51,23],[2,42,14,17,43,15],[5,150,120,1,151,121],[9,69,43,4,70,44],[17,50,22,1,51,23],[2,42,14,19,43,15],[3,141,113,4,142,114],[3,70,44,11,71,45],[17,47,21,4,48,22],[9,39,13,16,40,14],[3,135,107,5,136,108],[3,67,41,13,68,42],[15,54,24,5,55,25],[15,43,15,10,44,16],[4,144,116,4,145,117],[17,68,42],[17,50,22,6,51,23],[19,46,16,6,47,17],[2,139,111,7,140,112],[17,74,46],[7,54,24,16,55,25],[34,37,13],[4,151,121,5,152,122],[4,75,47,14,76,48],[11,54,24,14,55,25],[16,45,15,14,46,16],[6,147,117,4,148,118],[6,73,45,14,74,46],[11,54,24,16,55,25],[30,46,16,2,47,17],[8,132,106,4,133,107],[8,75,47,13,76,48],[7,54,24,22,55,25],[22,45,15,13,46,16],[10,142,114,2,143,115],[19,74,46,4,75,47],[28,50,22,6,51,23],[33,46,16,4,47,17],[8,152,122,4,153,123],[22,73,45,3,74,46],[8,53,23,26,54,24],[12,45,15,28,46,16],[3,147,117,10,148,118],[3,73,45,23,74,46],[4,54,24,31,55,25],[11,45,15,31,46,16],[7,146,116,7,147,117],[21,73,45,7,74,46],[1,53,23,37,54,24],[19,45,15,26,46,16],[5,145,115,10,146,116],[19,75,47,10,76,48],[15,54,24,25,55,25],[23,45,15,25,46,16],[13,145,115,3,146,116],[2,74,46,29,75,47],[42,54,24,1,55,25],[23,45,15,28,46,16],[17,145,115],[10,74,46,23,75,47],[10,54,24,35,55,25],[19,45,15,35,46,16],[17,145,115,1,146,116],[14,74,46,21,75,47],[29,54,24,19,55,25],[11,45,15,46,46,16],[13,145,115,6,146,116],[14,74,46,23,75,47],[44,54,24,7,55,25],[59,46,16,1,47,17],[12,151,121,7,152,122],[12,75,47,26,76,48],[39,54,24,14,55,25],[22,45,15,41,46,16],[6,151,121,14,152,122],[6,75,47,34,76,48],[46,54,24,10,55,25],[2,45,15,64,46,16],[17,152,122,4,153,123],[29,74,46,14,75,47],[49,54,24,10,55,25],[24,45,15,46,46,16],[4,152,122,18,153,123],[13,74,46,32,75,47],[48,54,24,14,55,25],[42,45,15,32,46,16],[20,147,117,4,148,118],[40,75,47,7,76,48],[43,54,24,22,55,25],[10,45,15,67,46,16],[19,148,118,6,149,119],[18,75,47,31,76,48],[34,54,24,34,55,25],[20,45,15,61,46,16]],r=function(t,r){var e={};return e.totalCount=t,e.dataCount=r,e},e={};return e.getRSBlocks=function(e,n){var o=function(r,e){switch(e){case g.L:return t[4*(r-1)+0];case g.M:return t[4*(r-1)+1];case g.Q:return t[4*(r-1)+2];case g.H:return t[4*(r-1)+3];default:return}}(e,n);if(void 0===o)throw"bad rs block @ typeNumber:"+e+"/errorCorrectionLevel:"+n;for(var i=o.length/3,a=[],u=0;u<i;u+=1)for(var f=o[3*u+0],c=o[3*u+1],l=o[3*u+2],h=0;h<f;h+=1)a.push(r(c,l));return a},e}(),b=function(){var t=[],r=0,e={getBuffer:function(){return t},getAt:function(r){var e=Math.floor(r/8);return 1==(t[e]>>>7-r%8&1)},put:function(t,r){for(var n=0;n<r;n+=1)e.putBit(1==(t>>>r-n-1&1))},getLengthInBits:function(){return r},putBit:function(e){var n=Math.floor(r/8);t.length<=n&&t.push(0),e&&(t[n]|=128>>>r%8),r+=1}};return e},M=function(t){var r=a,e=t,n={getMode:function(){return r},getLength:function(t){return e.length},write:function(t){for(var r=e,n=0;n+2<r.length;)t.put(o(r.substring(n,n+3)),10),n+=3;n<r.length&&(r.length-n==1?t.put(o(r.substring(n,n+1)),4):r.length-n==2&&t.put(o(r.substring(n,n+2)),7))}},o=function(t){for(var r=0,e=0;e<t.length;e+=1)r=10*r+i(t.charAt(e));return r},i=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);throw"illegal char :"+t};return n},x=function(t){var r=u,e=t,n={getMode:function(){return r},getLength:function(t){return e.length},write:function(t){for(var r=e,n=0;n+1<r.length;)t.put(45*o(r.charAt(n))+o(r.charAt(n+1)),11),n+=2;n<r.length&&t.put(o(r.charAt(n)),6)}},o=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);if("A"<=t&&t<="Z")return t.charCodeAt(0)-"A".charCodeAt(0)+10;switch(t){case" ":return 36;case"$":return 37;case"%":return 38;case"*":return 39;case"+":return 40;case"-":return 41;case".":return 42;case"/":return 43;case":":return 44;default:throw"illegal char :"+t}};return n},m=function(r){var e=f,n=t.stringToBytes(r),o={getMode:function(){return e},getLength:function(t){return n.length},write:function(t){for(var r=0;r<n.length;r+=1)t.put(n[r],8)}};return o},L=function(r){var e=c,n=t.stringToBytesFuncs.SJIS;if(!n)throw"sjis not supported.";!function(){var t=n("友");if(2!=t.length||38726!=(t[0]<<8|t[1]))throw"sjis not supported."}();var o=n(r),i={getMode:function(){return e},getLength:function(t){return~~(o.length/2)},write:function(t){for(var r=o,e=0;e+1<r.length;){var n=(255&r[e])<<8|255&r[e+1];if(33088<=n&&n<=40956)n-=33088;else{if(!(57408<=n&&n<=60351))throw"illegal char at "+(e+1)+"/"+n;n-=49472}n=192*(n>>>8&255)+(255&n),t.put(n,13),e+=2}if(e<r.length)throw"illegal char at "+(e+1)}};return i},D=function(){var t=[],r={writeByte:function(r){t.push(255&r)},writeShort:function(t){r.writeByte(t),r.writeByte(t>>>8)},writeBytes:function(t,e,n){e=e||0,n=n||t.length;for(var o=0;o<n;o+=1)r.writeByte(t[o+e])},writeString:function(t){for(var e=0;e<t.length;e+=1)r.writeByte(t.charCodeAt(e))},toByteArray:function(){return t},toString:function(){var r="";r+="[";for(var e=0;e<t.length;e+=1)e>0&&(r+=","),r+=t[e];return r+="]"}};return r},S=function(t){var r=t,e=0,n=0,o=0,i={read:function(){for(;o<8;){if(e>=r.length){if(0==o)return-1;throw"unexpected end of file./"+o}var t=r.charAt(e);if(e+=1,"="==t)return o=0,-1;t.match(/^\\s$/)||(n=n<<6|a(t.charCodeAt(0)),o+=6)}var i=n>>>o-8&255;return o-=8,i}},a=function(t){if(65<=t&&t<=90)return t-65;if(97<=t&&t<=122)return t-97+26;if(48<=t&&t<=57)return t-48+52;if(43==t)return 62;if(47==t)return 63;throw"c:"+t};return i},I=function(t,r,e){for(var n=function(t,r){var e=t,n=r,o=new Array(t*r),i={setPixel:function(t,r,n){o[r*e+t]=n},write:function(t){t.writeString("GIF87a"),t.writeShort(e),t.writeShort(n),t.writeByte(128),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(255),t.writeByte(255),t.writeByte(255),t.writeString(","),t.writeShort(0),t.writeShort(0),t.writeShort(e),t.writeShort(n),t.writeByte(0);var r=a(2);t.writeByte(2);for(var o=0;r.length-o>255;)t.writeByte(255),t.writeBytes(r,o,255),o+=255;t.writeByte(r.length-o),t.writeBytes(r,o,r.length-o),t.writeByte(0),t.writeString(";")}},a=function(t){for(var r=1<<t,e=1+(1<<t),n=t+1,i=u(),a=0;a<r;a+=1)i.add(String.fromCharCode(a));i.add(String.fromCharCode(r)),i.add(String.fromCharCode(e));var f,c,g,l=D(),h=(f=l,c=0,g=0,{write:function(t,r){if(t>>>r!=0)throw"length over";for(;c+r>=8;)f.writeByte(255&(t<<c|g)),r-=8-c,t>>>=8-c,g=0,c=0;g|=t<<c,c+=r},flush:function(){c>0&&f.writeByte(g)}});h.write(r,n);var s=0,v=String.fromCharCode(o[s]);for(s+=1;s<o.length;){var d=String.fromCharCode(o[s]);s+=1,i.contains(v+d)?v+=d:(h.write(i.indexOf(v),n),i.size()<4095&&(i.size()==1<<n&&(n+=1),i.add(v+d)),v=d)}return h.write(i.indexOf(v),n),h.write(e,n),h.flush(),l.toByteArray()},u=function(){var t={},r=0,e={add:function(n){if(e.contains(n))throw"dup key:"+n;t[n]=r,r+=1},size:function(){return r},indexOf:function(r){return t[r]},contains:function(r){return void 0!==t[r]}};return e};return i}(t,r),o=0;o<r;o+=1)for(var i=0;i<t;i+=1)n.setPixel(i,o,e(i,o));var a=D();n.write(a);for(var u=function(){var t=0,r=0,e=0,n="",o={},i=function(t){n+=String.fromCharCode(a(63&t))},a=function(t){if(t<0);else{if(t<26)return 65+t;if(t<52)return t-26+97;if(t<62)return t-52+48;if(62==t)return 43;if(63==t)return 47}throw"n:"+t};return o.writeByte=function(n){for(t=t<<8|255&n,r+=8,e+=1;r>=6;)i(t>>>r-6),r-=6},o.flush=function(){if(r>0&&(i(t<<6-r),t=0,r=0),e%3!=0)for(var o=3-e%3,a=0;a<o;a+=1)n+="="},o.toString=function(){return n},o}(),f=a.toByteArray(),c=0;c<f.length;c+=1)u.writeByte(f[c]);return u.flush(),"data:image/gif;base64,"+u};return t}();qrcode.stringToBytesFuncs["UTF-8"]=function(t){return function(t){for(var r=[],e=0;e<t.length;e++){var n=t.charCodeAt(e);n<128?r.push(n):n<2048?r.push(192|n>>6,128|63&n):n<55296||n>=57344?r.push(224|n>>12,128|n>>6&63,128|63&n):(e++,n=65536+((1023&n)<<10|1023&t.charCodeAt(e)),r.push(240|n>>18,128|n>>12&63,128|n>>6&63,128|63&n))}return r}(t)},function(t){"function"==typeof define&&define.amd?define([],t):"object"==typeof exports&&(module.exports=t())}(function(){return qrcode});
</script>

<script nonce="__NONCE__">
(function () {
  var token = sessionStorage.getItem("tt_admin") || "";
  var items = [], cursor = null;
  var PLAN = { monthly: "Monthly", "3months": "3 Months", annual: "Annual", lifetime: "Lifetime", trial: "Trial" };
  var selected = {}, lastShown = [], lastGen = null;
  function $(id) { return document.getElementById(id); }

  function api(path, body) {
    return fetch(path, {
      method: "POST",
      headers: { "Authorization": "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, data: j }; });
    });
  }

  function copy(text, btn) {
    function done() { var o = btn.textContent; btn.textContent = "Copied"; setTimeout(function () { btn.textContent = o; }, 1200); }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fallback);
    } else { fallback(); }
    function fallback() {
      var t = document.createElement("textarea"); t.value = text; document.body.appendChild(t);
      t.select(); try { document.execCommand("copy"); done(); } catch (e) {} document.body.removeChild(t);
    }
  }

  function fmt(ts) {
    return ts ? new Date(ts * 1000).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "";
  }
  function status(it) {
    if (it.revoked) return "Revoked";
    if (!it.activated_at) return "Unused";
    if (it.expires_at && Date.now() / 1000 >= it.expires_at) return "Expired";
    return "Active";
  }

  function planLabel(o) {
    var base = PLAN[o.plan] || o.plan;
    return o.plan === "trial" && o.days ? base + " " + o.days + "d" : base;
  }
  function validityText(plan, days) {
    if (plan === "lifetime") return "Lifetime license - never expires";
    if (plan === "monthly") return "Valid for 30 days from activation";
    if (plan === "3months") return "Valid for 90 days from activation";
    if (plan === "annual") return "Valid for 1 year from activation";
    if (plan === "trial") return days + "-day trial from activation";
    return String(plan);
  }
  function byNewest(a, b) { return (b.created_at || 0) - (a.created_at || 0); }

  function qrSvg(text) {
    var qr = qrcode(0, "M"); qr.addData(text); qr.make();
    var n = qr.getModuleCount(), q = 4, size = n + 2 * q, NS = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 " + size + " " + size);
    svg.setAttribute("shape-rendering", "crispEdges");
    var bg = document.createElementNS(NS, "rect");
    bg.setAttribute("width", size); bg.setAttribute("height", size); bg.setAttribute("fill", "#ffffff");
    svg.appendChild(bg);
    var d = "";
    for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (qr.isDark(r, c)) d += "M" + (c + q) + " " + (r + q) + "h1v1h-1z";
    var path = document.createElementNS(NS, "path");
    path.setAttribute("d", d); path.setAttribute("fill", "#000000");
    svg.appendChild(path);
    return svg;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag); if (cls) e.className = cls; if (text) e.textContent = text; return e;
  }

  function ticketEl(t) {
    var tk = el("div", "ticket");
    var head = el("div", "tk-head");
    head.appendChild(el("span", "tk-brand", "THIEVES TRAP"));
    head.appendChild(el("span", "tk-by", "by iLine Tech"));
    var body = el("div", "tk-body");
    var left = el("div", "tk-left");
    left.appendChild(el("div", "tk-label", "LICENSE KEY"));
    left.appendChild(el("div", "tk-key", t.key));
    left.appendChild(el("div", "tk-valid", validityText(t.plan, t.days)));
    left.appendChild(el("div", "tk-how", "Open the app > Premium > enter the key, or tap the QR button and scan the code."));
    var qr = el("div", "tk-qr"); qr.appendChild(qrSvg(t.key));
    body.appendChild(left); body.appendChild(qr);
    var foot = el("div", "tk-foot");
    foot.appendChild(el("span", "", "Your phone is safe with us"));
    foot.appendChild(el("span", "", "One device per key"));
    tk.appendChild(head); tk.appendChild(body); tk.appendChild(foot);
    return tk;
  }

  function printItems(arr) {
    if (!arr.length) return;
    var area = $("printArea"); area.textContent = "";
    arr.forEach(function (t) { area.appendChild(ticketEl(t)); });
    window.print();
  }

  function updateSel() {
    var n = 0; items.forEach(function (it) { if (selected[it.key]) n++; });
    $("selCount").textContent = n + " selected";
    $("btnPrint").disabled = n === 0;
    $("selAll").checked = lastShown.length > 0 && lastShown.every(function (it) { return selected[it.key]; });
  }

  function show(loggedIn) { $("login").hidden = loggedIn; $("app").hidden = !loggedIn; }

  function login() {
    token = $("tok").value.trim() || token;
    $("loginMsg").textContent = "";
    api("/admin/ping").then(function (r) {
      if (r.status === 200) { sessionStorage.setItem("tt_admin", token); $("tok").value = ""; show(true); reload(); }
      else if (r.status === 429 || (r.data && r.data.error === "too_many_attempts")) $("loginMsg").textContent = "Too many attempts. Wait 15 minutes.";
      else { token = ""; sessionStorage.removeItem("tt_admin"); $("loginMsg").textContent = "Wrong token."; }
    }).catch(function () { $("loginMsg").textContent = "Network error."; });
  }

  function logout() { token = ""; sessionStorage.removeItem("tt_admin"); items = []; show(false); }

  function generate() {
    $("genMsg").className = "msg"; $("genMsg").textContent = "";
    $("btnGen").disabled = true;
    var body = { plan: $("plan").value, count: parseInt($("count").value, 10) || 1, note: $("note").value };
    if (body.plan === "trial") {
      body.days = parseInt($("days").value, 10);
      if (!(body.days >= 1 && body.days <= 14)) { $("genMsg").textContent = "Trial days must be 1 to 14."; $("btnGen").disabled = false; return; }
    }
    api("/admin/generate", body)
      .then(function (r) {
        $("btnGen").disabled = false;
        if (r.status !== 200) { $("genMsg").textContent = "Error: " + (r.data.error || r.status); return; }
        var out = $("genOut"); out.textContent = "";
        r.data.keys.forEach(function (k) {
          var row = document.createElement("div"); row.className = "newkey";
          var code = document.createElement("span"); code.className = "key"; code.textContent = k;
          var b = document.createElement("button"); b.textContent = "Copy";
          b.addEventListener("click", function () { copy(k, b); });
          row.appendChild(code); row.appendChild(b); out.appendChild(row);
        });
        $("copyAllRow").hidden = false;
        lastGen = { keys: r.data.keys, plan: r.data.plan, days: r.data.days || 0 };
        $("btnCopyAll").onclick = function () { copy(r.data.keys.join("\\n"), $("btnCopyAll")); };
        $("genMsg").className = "msg ok";
        $("genMsg").textContent = r.data.keys.length + " " + planLabel(r.data) + " key(s) created.";
        reload();
      }).catch(function () { $("btnGen").disabled = false; $("genMsg").textContent = "Network error."; });
  }

  function reload() { items = []; cursor = null; loadMore(); }

  function loadMore() {
    $("listMsg").textContent = "Loading...";
    api("/admin/list", { limit: 100, cursor: cursor }).then(function (r) {
      if (r.status === 401 || r.status === 429) { logout(); return; }
      if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
      items = items.concat(r.data.items);
      cursor = r.data.cursor;
      $("btnMore").hidden = !cursor;
      render();
    }).catch(function () { $("listMsg").textContent = "Network error."; });
  }

  function act(path, it, apply, confirmText) {
    if (confirmText && !window.confirm(confirmText)) return;
    api(path, { key: it.key }).then(function (r) {
      if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
      apply(it); render();
    });
  }

  function render() {
    var q = $("search").value.trim().toLowerCase(), f = $("filter").value;
    var list = items.slice().sort(byNewest)
      .filter(function (it) {
        if (f && status(it) !== f) return false;
        if (!q) return true;
        return (it.key + " " + (it.note || "") + " " + (it.device || "")).toLowerCase().indexOf(q) >= 0;
      });
    var box = $("list"); box.textContent = ""; lastShown = list;
    list.forEach(function (it) {
      var st = status(it);
      var d = document.createElement("div"); d.className = "item";

      var top = document.createElement("div"); top.className = "row";
      var k = document.createElement("span"); k.className = "key grow"; k.textContent = it.key;
      var cb = document.createElement("button"); cb.className = "sec"; cb.textContent = "Copy";
      cb.addEventListener("click", function () { copy(it.key, cb); });
      var cbx = document.createElement("input"); cbx.type = "checkbox"; cbx.checked = !!selected[it.key];
      cbx.addEventListener("change", function () { if (cbx.checked) selected[it.key] = true; else delete selected[it.key]; updateSel(); });
      top.appendChild(cbx); top.appendChild(k); top.appendChild(cb); d.appendChild(top);

      var info = document.createElement("div"); info.className = "small";
      var badge = document.createElement("span"); badge.className = "badge b-" + st; badge.textContent = st;
      info.appendChild(badge);
      var parts = [planLabel(it)];
      if (it.activated_at) parts.push(it.expires_at ? "expires " + fmt(it.expires_at) : "no expiry");
      if (it.device) parts.push("device " + it.device);
      if (it.note) parts.push(it.note);
      info.appendChild(document.createTextNode(" " + parts.join(" \\u00b7 ")));
      d.appendChild(info);

      var acts = document.createElement("div"); acts.className = "acts";
      if (it.device) {
        var u = document.createElement("button"); u.className = "sec"; u.textContent = "Unbind device";
        u.addEventListener("click", function () {
          act("/admin/unbind", it, function (x) { x.device = null; }, "Unbind this key from its device? The phone using it is locked out at its next check, and the key binds to whichever phone activates it next. Expiry is kept.");
        });
        acts.appendChild(u);
      }
      if (it.revoked) {
        var rs = document.createElement("button"); rs.className = "sec"; rs.textContent = "Restore";
        rs.addEventListener("click", function () { act("/admin/restore", it, function (x) { x.revoked = false; }, null); });
        acts.appendChild(rs);
      } else {
        var rv = document.createElement("button"); rv.className = "dan"; rv.textContent = "Revoke";
        rv.addEventListener("click", function () {
          act("/admin/revoke", it, function (x) { x.revoked = true; }, "Revoke this key? The app will drop premium at its next server check.");
        });
        acts.appendChild(rv);
      }
      var del = document.createElement("button"); del.className = "dan"; del.textContent = "Delete";
      del.addEventListener("click", function () {
        act("/admin/delete", it, function (x) { delete selected[x.key]; items = items.filter(function (y) { return y.key !== x.key; }); },
          st === "Active"
            ? "PERMANENTLY delete this ACTIVE key? The customer will lose premium at the app's next server check."
            : "Permanently delete this key? This cannot be undone.");
      });
      acts.appendChild(del);
      d.appendChild(acts);
      box.appendChild(d);
    });
    $("listMsg").textContent = list.length + " shown, " + items.length + " loaded" + (cursor ? " (more available)" : "");
    updateSel();
  }

  $("btnLogin").addEventListener("click", login);
  $("tok").addEventListener("keydown", function (e) { if (e.key === "Enter") login(); });
  $("btnGen").addEventListener("click", generate);
  $("btnMore").addEventListener("click", loadMore);
  $("btnRefresh").addEventListener("click", reload);
  $("btnLogout").addEventListener("click", logout);
  $("plan").addEventListener("change", function () { $("trialBox").hidden = $("plan").value !== "trial"; });
  document.querySelectorAll("button[data-days]").forEach(function (b) {
    b.addEventListener("click", function () { $("days").value = b.getAttribute("data-days"); });
  });
  $("selAll").addEventListener("change", function () {
    var on = $("selAll").checked;
    lastShown.forEach(function (it) { if (on) selected[it.key] = true; else delete selected[it.key]; });
    render();
  });
  $("btnPrint").addEventListener("click", function () {
    var arr = items.slice().sort(byNewest).filter(function (it) { return selected[it.key]; });
    if (!arr.length) return;
    var used = arr.filter(function (it) { return status(it) !== "Unused"; }).length;
    if (used && !window.confirm(used + " selected key(s) are already Active, Expired or Revoked. Print them anyway?")) return;
    printItems(arr.map(function (it) { return { key: it.key, plan: it.plan, days: it.days }; }));
  });
  $("btnPrintNew").addEventListener("click", function () {
    if (!lastGen) return;
    printItems(lastGen.keys.map(function (k) { return { key: k, plan: lastGen.plan, days: lastGen.days }; }));
  });
  window.addEventListener("afterprint", function () { $("printArea").textContent = ""; });
  $("btnDiag").addEventListener("click", function () {
    $("diag").textContent = "Checking...";
    api("/admin/pubkey").then(function (r) {
      $("diag").textContent = r.status === 200
        ? "Server signing key fingerprint: " + r.data.fingerprint
        : "Signing key problem: " + (r.data.error || r.status) + " (re-enter the SIGNING_KEY secret)";
    });
  });
  $("btnPurge").addEventListener("click", function () {
    var gone = items.filter(function (it) { return status(it) === "Revoked"; });
    if (!gone.length) { $("listMsg").textContent = "No revoked keys loaded."; return; }
    if (!window.confirm("Permanently delete " + gone.length + " revoked key(s)? This cannot be undone.")) return;
    var i = 0;
    (function next() {
      if (i >= gone.length) { render(); return; }
      var k = gone[i++].key;
      api("/admin/delete", { key: k }).then(function (r) {
        if (r.status === 200 || r.status === 404) delete selected[k]; items = items.filter(function (y) { return y.key !== k; });
        $("listMsg").textContent = "Deleting " + i + " / " + gone.length + "...";
        next();
      });
    })();
  });
  $("search").addEventListener("input", render);
  $("filter").addEventListener("change", render);

  if (token) { api("/admin/ping").then(function (r) { if (r.status === 200) { show(true); reload(); } else logout(); }); }
})();
</script>
</body></html>`;

// ---------------------------------------------------------------- router

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, "") || "/";

    if (req.method === "GET" && path === "/admin") return dashboard();
    if (req.method !== "POST") return json({ error: "not_found" }, 404);

    let body;
    try { body = await req.json(); } catch { body = null; }

    try {
      if (path === "/activate" || path === "/check") {
        if (!body) return json({ error: "bad_request" }, 400);
        return path === "/activate" ? await activate(body, env) : await check(body, env);
      }
      if (path.startsWith("/admin/")) {
        const auth = await checkAdmin(req, env);
        if (auth === "limited") return json({ error: "too_many_attempts" }, 429);
        if (auth !== "ok") return json({ error: "unauthorized" }, 401);
        return await admin(path, body || {}, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: "server" }, 500);
    }
  },
};
