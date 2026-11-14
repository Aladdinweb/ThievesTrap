// Thieves Trap public Worker (Cloudflare Worker + KV): license API + public website. NO admin code lives here.
// Bindings required:  KV namespace  LICENSES   (shared with the separate admin Worker, see admin_worker/)
// Secrets required:   SIGNING_KEY (base64 of the PKCS8 DER of the P-256 private key)
//
// Visitor feedback (POST /feedback, stored in KV as f:* entries, reviewed and deleted in the admin Worker).
// Public website (GET):   /  /terms  /privacy  (site.js)   /download (redirect to the latest APK)
//                         /version  ->  {min_version_code, latest_version_code, latest_version_name, key_fp}
// License API (POST):     /activate  (binds an UNBOUND key to the phone; a DIFFERENT phone gets key_in_use)
//                         /check     (background re-validation; NEVER changes the binding)
// A SUSPENDED key (admin Suspend) is refused on /activate (suspended, 403) and answered on /check with a signed, already-expired
// payload so phones lose Premium without losing the key; Resume restores it. Everything else answers 404. The admin panel is a separate Worker that only shares the KV namespace.

import { sitePage } from "./site.js";

const PLAN_DAYS = { monthly: 30, "3months": 90, annual: 365, lifetime: 0 };
const LEASE_SECONDS = 72 * 3600;       // how long the app may trust a signed answer without re-checking online
const TIME_TOLERANCE = 600;            // max clock difference (s) between app and server for a request
// STRICT lock-to-device: a key belongs to ONE device until the admin presses "Unbind" in the ADMIN Worker panel.
// There is no automatic migration. Set to true ONLY if you want a reinstall on the SAME phone (same
// Android ID, new Keystore key) to re-bind without admin help. Android ID is spoofable, so default is false.
const ALLOW_REINSTALL_SAME_ANDROID_ID = false;
const MAX_TRIAL_DAYS = 14;         // longest allowed trial key (1..14 days, set at generation)
const VERSION_JSON = "https://raw.githubusercontent.com/Aladdinweb/ThievesTrap/main/version.json";
const RELEASE_PREFIX = "https://github.com/Aladdinweb/ThievesTrap/releases/";

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const toB64 = (bytes) => btoa(String.fromCharCode(...bytes));
const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

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
  plan: r.plan, days: r.days || 0, extra_days: r.extra_days || 0, renewals: r.renewals || 0, created_at: r.created_at, device: r.device || null, activated_at: r.activated_at || null,
  expires_at: r.expires_at || 0, revoked: !!r.revoked, suspended: !!r.suspended, note: String(r.note || "").slice(0, 120),
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

// expOverride: used for a SUSPENDED key. The phone receives a validly signed answer whose expiry is already
// in the past, so it drops Premium at its next check but KEEPS the key and recovers by itself after Resume.
async function signedAnswer(env, rec, r, expOverride) {
  return signPayload(env, {
    v: 2, device: r.device, dpk: r.dpk, plan: rec.plan, exp: expOverride !== undefined ? expOverride : (rec.expires_at || 0),
    iat: r.now, lease: r.now + LEASE_SECONDS, nonce: r.nonce,
  });
}

// Is the caller the device this key is currently bound to?
function isBoundDevice(rec, r) {
  if (rec.dev_pub) return rec.dev_pub === r.pub;
  return !rec.device || rec.device === r.device; // older records bound by Android ID only
}

// ACTIVATE: binds an UNBOUND key to the calling phone. A key bound to another phone is refused with
// key_in_use (403): only the admin can free it ("Unbind device" in the admin panel).
async function activate(body, env) {
  const p = await parseRequest("activate", body);
  if (p.err) return p.err;
  const r = p.r;

  const rec = await getRec(env, r.key);
  if (!rec) return json({ error: "invalid_key" }, 404);
  if (rec.revoked) return json({ error: "revoked" }, 403);
  if (rec.suspended) return json({ error: "suspended" }, 403);   // paused by the admin: nothing is bound or persisted

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
    const base = planDays(rec);
    const days = base ? base + Math.max(0, parseInt(rec.extra_days, 10) || 0) : 0; // extra_days = renewal bought before first use
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
  if (rec.suspended) {
    // Only the bound phone learns this. It gets an already-expired signed answer (Premium off, key kept);
    // after Resume the next check returns the normal answer and Premium comes back with no re-entry.
    try { return json({ ok: true, suspended: true, ...(await signedAnswer(env, rec, r, r.now - 1)) }); }
    catch (e) { return json({ error: "signing_failed" }, 500); }
  }
  if (rec.expires_at && r.now >= rec.expires_at) return json({ error: "expired" }, 403);
  if (!rec.dev_pub && rec.device === r.device) {               // one-time upgrade of an older record
    rec.dev_pub = r.pub;
    await putRec(env, r.key, rec);
  }
  try { return json({ ok: true, ...(await signedAnswer(env, rec, r)) }); }
  catch (e) { return json({ error: "signing_failed" }, 500); }
}


// ---------------------------------------------------------------- public website helpers

const notFound = () => new Response("Not found", { status: 404, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });

// version.json on main is rewritten by release.yml on every tag; cached at the edge for 5 minutes.
async function latestRelease() {
  const r = await fetch(VERSION_JSON, { cf: { cacheTtl: 300, cacheEverything: true } });
  if (!r.ok) return null;
  const j = await r.json();
  const url = String(j.download_url || "");
  if (!url.startsWith(RELEASE_PREFIX)) return null;   // only ever redirect to our own releases
  return { code: parseInt(j.version_code, 10) || 0, name: String(j.version_name || "").slice(0, 20), url };
}

// The APK is streamed through this Worker, so visitors only ever see this site's own address.
async function download(req) {
  let l = null;
  try { l = await latestRelease(); } catch (e) {}
  if (!l) return new Response("Download temporarily unavailable. Please try again later.", { status: 503, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
  const init = { redirect: "follow", headers: { "user-agent": "ThievesTrap-Site" } };
  const range = req.headers.get("range");
  if (range) init.headers.range = range;
  let up;
  try { up = await fetch(l.url, init); } catch (e) { up = null; }
  if (!up || !(up.status === 200 || up.status === 206)) {
    return new Response("Download temporarily unavailable. Please try again later.", { status: 502, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
  }
  const h = new Headers({
    "content-type": "application/vnd.android.package-archive",
    "content-disposition": 'attachment; filename="Thieves_Trap_v' + (l.name || "latest").replace(/[^0-9A-Za-z._-]/g, "") + '.apk"',
    "cache-control": "public, max-age=300",
    "accept-ranges": "bytes",
    "x-content-type-options": "nosniff",
  });
  for (const k of ["content-length", "content-range"]) { const v = up.headers.get(k); if (v) h.set(k, v); }
  return new Response(up.body, { status: up.status, headers: h });
}

// Public, read-only. min_version_code is set from the admin panel (KV key cfg:min_version); 0 = no minimum.
async function versionInfo(env) {
  let min = 0, latest = null, fp = null;
  try { min = Math.max(0, parseInt(await env.LICENSES.get("cfg:min_version"), 10) || 0); } catch (e) {}
  try { latest = await latestRelease(); } catch (e) {}
  try { fp = (await pubInfo(env)).fingerprint; } catch (e) {}
  return new Response(JSON.stringify({
    ok: true, min_version_code: min,
    latest_version_code: latest ? latest.code : null, latest_version_name: latest ? latest.name : null, key_fp: fp,
  }), { headers: { "content-type": "application/json", "cache-control": "public, max-age=60" } });
}

// ---------------------------------------------------------------- visitor feedback (public form -> KV)
// Entry "f:<9999999999999 - ms>:<rand>" (a plain list returns the NEWEST first). The full text is the value; a short
// summary is in KV metadata so the admin list needs no extra reads. Entries expire after one year or when the admin
// deletes them. Limits: 3 per visitor per hour (anonymous hashed counter that lives one hour), 100 per day in total
// (protects the KV write budget used by licensing), 4 KB request, message 5..1000 characters, hidden honeypot field.
const FB_MAX_BODY = 4096;
const FB_TTL = 365 * 86400;
const FB_PER_HOUR = 3;
const FB_PER_DAY = 100;

const fbClean = (v, n) => String(v === undefined || v === null ? "" : v)
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B-\u200F\u202A-\u202E\u2066-\u2069]/g, "") // control and bidi-override characters
  .trim().slice(0, n);

async function fbHash(ip) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("tt-fb|" + ip));
  return Array.from(new Uint8Array(d).slice(0, 8)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function feedback(req, env) {
  const origin = req.headers.get("origin");
  if (origin) { try { if (new URL(origin).host !== new URL(req.url).host) return json({ error: "forbidden" }, 403); } catch (e) { return json({ error: "forbidden" }, 403); } }
  const raw = await req.text();
  if (raw.length > FB_MAX_BODY) return json({ error: "too_large" }, 413);
  let b; try { b = JSON.parse(raw); } catch (e) { return json({ error: "bad_request" }, 400); }
  if (!b || typeof b !== "object") return json({ error: "bad_request" }, 400);
  if (b.website) return json({ ok: true });                       // honeypot filled: pretend success, store nothing

  const message = fbClean(b.message, 1000);
  const name = fbClean(b.name, 60).replace(/\s+/g, " ");
  const contact = fbClean(b.contact, 100).replace(/\s+/g, " ");
  const rating = Number.isInteger(b.rating) && b.rating >= 1 && b.rating <= 5 ? b.rating : 0;
  if (message.length < 5) return json({ error: "message_too_short" }, 400);

  const rl = "fr:" + await fbHash(req.headers.get("cf-connecting-ip") || "unknown");
  const used = parseInt(await env.LICENSES.get(rl), 10) || 0;
  if (used >= FB_PER_HOUR) return json({ error: "rate_limited" }, 429);
  const dayKey = "fg:" + new Date().toISOString().slice(0, 10);
  const today = parseInt(await env.LICENSES.get(dayKey), 10) || 0;
  if (today >= FB_PER_DAY) return json({ error: "busy" }, 429);

  const t = Date.now();
  const id = "f:" + String(9999999999999 - t).padStart(13, "0") + ":" + toB64(crypto.getRandomValues(new Uint8Array(3))).replace(/[^A-Za-z0-9]/g, "x");
  const meta = { t, r: rating, n: name.slice(0, 30), c: contact.slice(0, 40), p: message.replace(/\s+/g, " ").slice(0, 160) };
  const size = () => new TextEncoder().encode(JSON.stringify(meta)).length;
  while (size() > 900 && meta.p.length > 20) meta.p = meta.p.slice(0, meta.p.length - 20);  // KV metadata limit is 1024 bytes
  await env.LICENSES.put(id, JSON.stringify({ t, name, contact, rating, message }), { expirationTtl: FB_TTL, metadata: meta });
  await env.LICENSES.put(rl, String(used + 1), { expirationTtl: 3600 });
  await env.LICENSES.put(dayKey, String(today + 1), { expirationTtl: 172800 });
  return json({ ok: true });
}

// ---------------------------------------------------------------- router

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, "") || "/";

    if (req.method === "GET" || req.method === "HEAD") {
      if (path === "/") return sitePage("home");
      if (path === "/terms") return sitePage("terms");
      if (path === "/privacy") return sitePage("privacy");
      if (path === "/download") return await download(req);
      if (path === "/version") return await versionInfo(env);
      if (path === "/robots.txt") return new Response("User-agent: *\nAllow: /\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
      return notFound();
    }
    if (req.method !== "POST") return json({ error: "not_found" }, 404);

    // Feedback reads its own raw body, so it must be dispatched BEFORE the JSON parsing below consumes it.
    if (path === "/feedback") {
      try { return await feedback(req, env); } catch (e) { return json({ error: "server" }, 500); }
    }

    let body;
    try { body = await req.json(); } catch { body = null; }

    try {
      if (path === "/activate" || path === "/check") {
        if (!body) return json({ error: "bad_request" }, 400);
        return path === "/activate" ? await activate(body, env) : await check(body, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: "server" }, 500);
    }
  },
};
