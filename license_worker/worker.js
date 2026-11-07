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

const normKey = (k) => String(k || "").trim().toUpperCase();
const getRec = async (env, key) => {
  const raw = await env.LICENSES.get("k:" + key);
  return raw ? JSON.parse(raw) : null;
};
// small summary goes into KV metadata so the dashboard can list licenses without reading every record
const meta = (r) => ({
  plan: r.plan, created_at: r.created_at, device: r.device || null, activated_at: r.activated_at || null,
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
    const days = PLAN_DAYS[rec.plan] || 0;
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
    if (!(plan in PLAN_DAYS)) return json({ error: "bad_plan", allowed: Object.keys(PLAN_DAYS) }, 400);
    const count = Math.min(Math.max(parseInt(body.count || 1, 10) || 1, 1), 50);
    const keys = [];
    for (let i = 0; i < count; i++) {
      let key;
      do { key = newKey(); } while (await getRec(env, key));
      await putRec(env, key, {
        plan, created_at: Math.floor(Date.now() / 1000), note: String(body.note || ""),
        device: null, dev_pub: null, activated_at: null, expires_at: null, revoked: false,
      });
      keys.push(key);
    }
    return json({ ok: true, plan, keys });
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
        </select></div>
      <div class="w90"><label for="count">Count</label><input id="count" type="number" min="1" max="50" value="1"></div>
    </div>
    <label for="note">Note (optional, e.g. customer name)</label>
    <input id="note" maxlength="120">
    <div class="row mt"><button id="btnGen">Generate</button></div>
    <div id="genMsg" class="msg"></div>
    <div id="genOut"></div>
    <div class="row mt" id="copyAllRow" hidden><button class="sec" id="btnCopyAll">Copy all</button></div>
  </div>

  <div class="card">
    <h2>Licenses</h2>
    <div class="row">
      <div class="grow"><input id="search" placeholder="Search key, note or device"></div>
      <div class="w130"><select id="filter">
        <option value="">All</option><option>Unused</option><option>Active</option><option>Expired</option><option>Revoked</option>
      </select></div>
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

<script nonce="__NONCE__">
(function () {
  var token = sessionStorage.getItem("tt_admin") || "";
  var items = [], cursor = null;
  var PLAN = { monthly: "Monthly", "3months": "3 Months", annual: "Annual", lifetime: "Lifetime" };
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
    api("/admin/generate", { plan: $("plan").value, count: parseInt($("count").value, 10) || 1, note: $("note").value })
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
        $("btnCopyAll").onclick = function () { copy(r.data.keys.join("\\n"), $("btnCopyAll")); };
        $("genMsg").className = "msg ok";
        $("genMsg").textContent = r.data.keys.length + " " + (PLAN[r.data.plan] || r.data.plan) + " key(s) created.";
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
    var list = items.slice().sort(function (a, b) { return (b.created_at || 0) - (a.created_at || 0); })
      .filter(function (it) {
        if (f && status(it) !== f) return false;
        if (!q) return true;
        return (it.key + " " + (it.note || "") + " " + (it.device || "")).toLowerCase().indexOf(q) >= 0;
      });
    var box = $("list"); box.textContent = "";
    list.forEach(function (it) {
      var st = status(it);
      var d = document.createElement("div"); d.className = "item";

      var top = document.createElement("div"); top.className = "row";
      var k = document.createElement("span"); k.className = "key grow"; k.textContent = it.key;
      var cb = document.createElement("button"); cb.className = "sec"; cb.textContent = "Copy";
      cb.addEventListener("click", function () { copy(it.key, cb); });
      top.appendChild(k); top.appendChild(cb); d.appendChild(top);

      var info = document.createElement("div"); info.className = "small";
      var badge = document.createElement("span"); badge.className = "badge b-" + st; badge.textContent = st;
      info.appendChild(badge);
      var parts = [PLAN[it.plan] || it.plan];
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
        act("/admin/delete", it, function (x) { items = items.filter(function (y) { return y.key !== x.key; }); },
          st === "Active"
            ? "PERMANENTLY delete this ACTIVE key? The customer will lose premium at the app's next server check."
            : "Permanently delete this key? This cannot be undone.");
      });
      acts.appendChild(del);
      d.appendChild(acts);
      box.appendChild(d);
    });
    $("listMsg").textContent = list.length + " shown, " + items.length + " loaded" + (cursor ? " (more available)" : "");
  }

  $("btnLogin").addEventListener("click", login);
  $("tok").addEventListener("keydown", function (e) { if (e.key === "Enter") login(); });
  $("btnGen").addEventListener("click", generate);
  $("btnMore").addEventListener("click", loadMore);
  $("btnRefresh").addEventListener("click", reload);
  $("btnLogout").addEventListener("click", logout);
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
        if (r.status === 200 || r.status === 404) items = items.filter(function (y) { return y.key !== k; });
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
