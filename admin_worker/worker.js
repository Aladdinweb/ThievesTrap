// Thieves Trap ADMIN Worker (separate from the public site and the license API).
// Own workers.dev address, own ADMIN_TOKEN secret, shares only the KV namespace LICENSES with the public Worker.
// It holds NO signing key and serves NO public pages: key generation, listing, revoke/restore/unbind/delete,
// ticket printing and the Force Update minimum version all live here.
//
// Bindings required:  KV namespace  LICENSES   (same namespace id as license_worker/wrangler.toml)
//                     service binding PUBLIC -> Worker thievestrap-license (used only by Diagnostics)
// Secrets required:   ADMIN_TOKEN (long random string, also the dashboard password; NOT the old one)
//
// GET  /              dashboard page (useless without the token, noindex)
// POST /admin/ping | generate | list | info | renew | suspend | resume | revoke | restore | unbind | delete | purge | audit |
//      minversion | diagnostics | feedback_list | feedback_get | feedback_delete   (Bearer ADMIN_TOKEN)

const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
const PLAN_DAYS = { monthly: 30, "3months": 90, annual: 365, lifetime: 0 };
const MAX_TRIAL_DAYS = 14;         // longest allowed trial key (1..14 days, set at generation)
const MAX_BAD_ATTEMPTS = 10;       // failed admin logins per IP ...
const BAD_ATTEMPT_WINDOW = 900;    // ... per 15 minutes
const MAX_MIN_VERSION = 100000;
const MAX_RENEW_DAYS = 3650;       // one renewal step: at most 10 years
const MAX_HORIZON_DAYS = 3650;     // a key may never expire more than 10 years from today
const AUDIT_TTL = 90 * 86400;      // admin action log entries live 90 days
const MAX_AUDIT_LIST = 100;
const PURGE_BATCH = 40;            // revoked keys deleted per "Delete all revoked" call    // sanity cap for the Force Update minimum versionCode

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
  plan: r.plan, days: r.days || 0, extra_days: r.extra_days || 0, renewals: r.renewals || 0, created_at: r.created_at, device: r.device || null, activated_at: r.activated_at || null,
  expires_at: r.expires_at || 0, revoked: !!r.revoked, suspended: !!r.suspended, note: String(r.note || "").slice(0, 120),
});
const putRec = (env, key, rec) =>
  env.LICENSES.put("k:" + key, JSON.stringify(rec), { metadata: meta(rec) });


// ---------------------------------------------------------------- audit trail
// One small KV entry per sensitive admin action: key "a:<reversed time>:<rand>" (so a plain list returns the NEWEST
// first), the details live in KV metadata (no value read needed), auto-deleted after 90 days. Never throws.
const maskKey = (k) => { const p = String(k || "").split("-"); return p.length === 5 ? p[0] + "-" + p[1] + "-...-" + p[4] : (k ? "..." : ""); };
async function audit(env, action, key, detail) {
  try {
    const t = Date.now();
    const id = "a:" + String(9999999999999 - t).padStart(13, "0") + ":" + toB64(crypto.getRandomValues(new Uint8Array(3))).replace(/[^A-Za-z0-9]/g, "x");
    await env.LICENSES.put(id, "1", { expirationTtl: AUDIT_TTL, metadata: { t, a: action, k: maskKey(key), d: String(detail || "").slice(0, 160) } });
  } catch (e) { /* the log must never break the action */ }
}

async function admin(path, body, env) {
  if (path === "/admin/ping") return json({ ok: true });

  // Diagnostics: health of this panel, the shared KV and the public Worker (via the PUBLIC service binding).
  if (path === "/admin/diagnostics") {
    const now = Math.floor(Date.now() / 1000);
    const out = { ok: true, time: new Date().toISOString(), admin: {}, kv: {}, public: {} };
    out.admin.token_ok = typeof env.ADMIN_TOKEN === "string" && env.ADMIN_TOKEN.length >= 32;
    try {
      const by = { unused: 0, active: 0, expired: 0, revoked: 0, suspended: 0 };
      let total = 0, cursor, pages = 0, more = false;
      do {
        const res = await env.LICENSES.list({ prefix: "k:", limit: 1000, cursor });
        for (const k of res.keys) {
          const m = k.metadata || {};
          total++;
          if (m.revoked) by.revoked++;
          else if (m.suspended) by.suspended++;
          else if (!m.activated_at) by.unused++;
          else if (m.expires_at && now >= m.expires_at) by.expired++;
          else by.active++;
        }
        cursor = res.list_complete ? null : res.cursor;
        more = !!cursor; pages++;
      } while (cursor && pages < 5);
      out.kv = { ok: true, total, by_status: by, truncated: more };
      out.min_version_code = parseInt(await env.LICENSES.get("cfg:min_version"), 10) || 0;
    } catch (e) { out.kv = { ok: false, error: "kv_failed" }; }
    if (!env.PUBLIC) {
      out.public = { bound: false };
    } else {
      out.public.bound = true;
      const call = async (p, init) => {
        try { return await env.PUBLIC.fetch(new Request("https://public.internal" + p, init)); } catch (e) { return null; }
      };
      const v = await call("/version");
      if (!v) out.public.version = { ok: false };
      else {
        let j = null; try { j = await v.json(); } catch (e) {}
        out.public.version = { ok: v.status === 200 && !!j, status: v.status, key_fp: j && j.key_fp || null, min_version_code: j ? j.min_version_code : null,
          latest_version_code: j ? j.latest_version_code : null, latest_version_name: j ? j.latest_version_name : null };
      }
      const home = await call("/");
      out.public.site = { ok: !!home && home.status === 200, status: home ? home.status : 0 };
      const dl = await call("/download", { headers: { range: "bytes=0-0" } });
      let total = null;
      if (dl) { const cr = dl.headers.get("content-range") || ""; const mm = cr.match(/\/(\d+)$/); total = mm ? parseInt(mm[1], 10) : parseInt(dl.headers.get("content-length") || "0", 10) || null; try { await dl.body?.cancel(); } catch (e) {} }
      out.public.download = { ok: !!dl && (dl.status === 200 || dl.status === 206), status: dl ? dl.status : 0, bytes: total };
      const adm = await call("/admin");
      out.public.admin_hidden = { ok: !!adm && adm.status === 404, status: adm ? adm.status : 0 };
      const chk = await call("/check", { method: "POST", body: "{}" });
      out.public.api = { ok: !!chk && chk.status !== 404 && chk.status < 500, status: chk ? chk.status : 0 }; // a real API answers 4xx (e.g. update_required) to an empty request
    }
    return json(out);
  }

  // Force Update: the lowest app versionCode allowed to open its UI. 0 = no minimum.
  // The public Worker serves it (GET /version); the app ignores a minimum that has no released APK yet.
  if (path === "/admin/minversion") {
    if (body.value === undefined) {
      const cur = parseInt(await env.LICENSES.get("cfg:min_version"), 10) || 0;
      return json({ ok: true, min_version_code: cur });
    }
    const v = body.value;
    if (!Number.isInteger(v) || v < 0 || v > MAX_MIN_VERSION) return json({ error: "bad_value", min: 0, max: MAX_MIN_VERSION }, 400);
    const before = parseInt(await env.LICENSES.get("cfg:min_version"), 10) || 0;
    if (v === 0) await env.LICENSES.delete("cfg:min_version"); else await env.LICENSES.put("cfg:min_version", String(v));
    await audit(env, "force_update", "", "minimum versionCode " + before + " -> " + v);
    return json({ ok: true, min_version_code: v });
  }

  // Visitor feedback written by the public Worker (KV "f:" entries). Review and delete only; nothing here can edit them.
  const FB_ID = /^f:[0-9]{13}:[A-Za-z0-9]{1,12}$/;
  if (path === "/admin/feedback_list") {
    const limit = Math.min(Math.max(parseInt(body.limit || 50, 10) || 50, 1), MAX_AUDIT_LIST);
    const res = await env.LICENSES.list({ prefix: "f:", limit, cursor: body.cursor || undefined });
    return json({ ok: true, items: res.keys.map((k) => ({ id: k.name, ...(k.metadata || {}) })), cursor: res.list_complete ? null : res.cursor });
  }
  if (path === "/admin/feedback_get") {
    if (!FB_ID.test(String(body.id || ""))) return json({ error: "bad_id" }, 400);
    const v = await env.LICENSES.get(body.id);
    if (!v) return json({ error: "not_found" }, 404);
    let o = {}; try { o = JSON.parse(v); } catch (e) {}
    return json({ ok: true, id: body.id, t: o.t || 0, name: o.name || "", contact: o.contact || "", rating: o.rating || 0, message: o.message || "" });
  }
  if (path === "/admin/feedback_delete") {
    if (!FB_ID.test(String(body.id || ""))) return json({ error: "bad_id" }, 400);
    await env.LICENSES.delete(body.id);
    await audit(env, "feedback_delete", "", "one visitor feedback entry removed");
    return json({ ok: true, deleted: true });
  }

  // Delete revoked keys in small batches (call again while "remaining" > 0). One log entry per batch.
  if (path === "/admin/purge") {
    const res = await env.LICENSES.list({ prefix: "k:", limit: 1000 });
    const revoked = res.keys.filter((k) => k.metadata && k.metadata.revoked);
    const batch = revoked.slice(0, PURGE_BATCH);
    for (const k of batch) await env.LICENSES.delete(k.name);
    if (batch.length) await audit(env, "purge_revoked", "", batch.length + " revoked key(s) deleted");
    return json({ ok: true, deleted: batch.length, remaining: revoked.length - batch.length + (res.list_complete ? 0 : 1) });
  }

  // Newest-first admin action log.
  if (path === "/admin/audit") {
    const limit = Math.min(Math.max(parseInt(body.limit || 50, 10) || 50, 1), MAX_AUDIT_LIST);
    const res = await env.LICENSES.list({ prefix: "a:", limit, cursor: body.cursor || undefined });
    return json({ ok: true, items: res.keys.map((k) => k.metadata || {}), cursor: res.list_complete ? null : res.cursor });
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
    await audit(env, "generate", keys[0], count + " x " + plan + (plan === "trial" ? " (" + trialDays + " d)" : ""));
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
  if (path === "/admin/delete") { await env.LICENSES.delete("k:" + key); await audit(env, "delete", key, rec.plan + (rec.activated_at ? ", was activated" : ", unused")); return json({ ok: true, key, deleted: true }); } // permanent
  if (path === "/admin/renew") {
    // Extend an existing key without generating a new one. body.days = whole days to add (1..3650).
    const days = body.days;
    if (!Number.isInteger(days) || days < 1 || days > MAX_RENEW_DAYS) return json({ error: "bad_days", min: 1, max: MAX_RENEW_DAYS }, 400);
    if (rec.plan === "lifetime" || (rec.activated_at && !rec.expires_at)) return json({ error: "lifetime" }, 400); // nothing to extend
    const now = Math.floor(Date.now() / 1000);
    if (rec.activated_at) {
      // active: add to the current expiry; expired: restart from today
      const next = Math.max(now, rec.expires_at || 0) + days * 86400;
      if (next - now > MAX_HORIZON_DAYS * 86400) return json({ error: "too_long", max_days: MAX_HORIZON_DAYS }, 400);
      rec.expires_at = next;
    } else {
      // unused: the bonus is added to the plan length when the key is first activated
      const extra = (parseInt(rec.extra_days, 10) || 0) + days;
      if (extra > MAX_HORIZON_DAYS) return json({ error: "too_long", max_days: MAX_HORIZON_DAYS }, 400);
      rec.extra_days = extra;
    }
    rec.renewals = (parseInt(rec.renewals, 10) || 0) + 1;
    rec.last_renewed_at = now;
    await putRec(env, key, rec);
    await audit(env, "renew", key, "+" + days + " d" + (rec.activated_at ? ", expires " + new Date(rec.expires_at * 1000).toISOString().slice(0, 10) : ", bonus before activation"));
    return json({ ok: true, key, activated: !!rec.activated_at, expires_at: rec.expires_at || 0, extra_days: rec.extra_days || 0, renewals: rec.renewals });
  }
  if (path === "/admin/revoke") { rec.revoked = true; await putRec(env, key, rec); await audit(env, "revoke", key, rec.plan); return json({ ok: true, key, revoked: true }); }
  if (path === "/admin/restore") { rec.revoked = false; await putRec(env, key, rec); await audit(env, "restore", key, rec.plan); return json({ ok: true, key, revoked: false }); }
  if (path === "/admin/unbind") { rec.device = null; rec.dev_pub = null; await putRec(env, key, rec); await audit(env, "unbind", key, "device released"); return json({ ok: true, key, device: null }); } // expiry is kept
  // Suspend / Resume: a PAUSE, not a revocation. The key, its device binding and its expiry date stay as they are
  // (the clock keeps running; use Renew if you want to compensate). The public Worker refuses /activate and answers
  // /check with an already-expired signed payload, so the phone loses Premium but keeps the key and recovers on Resume.
  if (path === "/admin/suspend") {
    if (rec.revoked) return json({ error: "revoked" }, 400);
    if (!rec.suspended) { rec.suspended = true; rec.suspended_at = Math.floor(Date.now() / 1000); await putRec(env, key, rec); await audit(env, "suspend", key, rec.plan); }
    return json({ ok: true, key, suspended: true });
  }
  if (path === "/admin/resume") {
    if (rec.suspended) { rec.suspended = false; rec.suspended_at = null; await putRec(env, key, rec); await audit(env, "resume", key, rec.plan); }
    return json({ ok: true, key, suspended: false });
  }
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
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<meta name="theme-color" content="#08080a">
<title>Admin</title>
<style nonce="__NONCE__">
:root{--bg:#08080a;--card:#111114;--card2:#16161a;--line:#24242b;--tx:#f2f2f4;--mu:#9a9aa3;--grn:#00ff66;--red:#ff4545;--gold:#d4af37;--amb:#f5a524;--blu:#5aa9ff}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
[hidden]{display:none!important}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;padding-bottom:calc(78px + env(safe-area-inset-bottom,0px));overflow-x:hidden}
h1,h2,h3{margin:0}
h2{font-size:17px;margin-bottom:4px}
p{margin:0}
.small{font-size:13px;color:var(--mu)}
.mt{margin-top:12px}
.mt2{margin-top:6px}
code,.mono{font-family:ui-monospace,Menlo,Consolas,monospace}
/* sign in */
.loginwrap{min-height:100vh;display:grid;place-items:center;padding:20px}
.login{width:min(100%,380px)}
.login h1{font-size:22px;margin-bottom:4px}
/* top bar + navigation */
.top{position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:12px;padding:10px 16px;padding-top:max(10px,env(safe-area-inset-top,0px));background:rgba(8,8,10,.93);-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
.brand{font-weight:800;letter-spacing:.05em}.brand span{color:var(--grn);font-weight:700}
.grow{flex:1;min-width:0}
.tabs{position:fixed;inset-inline:0;bottom:0;z-index:30;display:grid;grid-template-columns:repeat(6,1fr);background:rgba(12,12,15,.97);border-top:1px solid var(--line);padding-bottom:env(safe-area-inset-bottom,0px)}
.tabs button{position:relative;min-height:58px;background:none;border:0;border-radius:0;color:var(--mu);font-size:11.5px;font-weight:600;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;cursor:pointer;padding:6px 2px}
.tabs button svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.tabs button.on{color:var(--grn)}
.bdg{position:absolute;top:5px;inset-inline-start:calc(50% + 6px);min-width:18px;height:18px;padding:0 5px;border-radius:9px;background:var(--red);color:#fff;font-size:11px;font-weight:800;display:grid;place-items:center;font-style:normal}
.tabs button:active{background:rgba(0,255,102,.08)}
.wrap{max-width:1100px;margin:0 auto;padding:16px}
.tab{display:none}
.tab.on{display:block}
/* cards, grids */
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px;margin-bottom:14px;min-width:0}
.cols{display:grid;gap:14px;align-items:start;grid-template-columns:minmax(0,1fr)}
.cols>.card{margin-bottom:0}
.stats{display:grid;gap:10px;grid-template-columns:repeat(2,minmax(0,1fr));margin-bottom:14px}
.stat{min-height:0;text-align:start;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px 14px;cursor:pointer;color:var(--tx);transition:border-color .2s,transform .15s}
.stat:active{transform:scale(.98)}
.stat b{display:block;font-size:26px;line-height:1.1}
.stat span{font-size:12px;color:var(--mu);text-transform:uppercase;letter-spacing:.06em}
.stat.s-Active b{color:var(--grn)}.stat.s-Unused b{color:var(--blu)}.stat.s-Expired b{color:var(--mu)}.stat.s-Suspended b{color:var(--amb)}.stat.s-Revoked b{color:var(--red)}
.stat:hover{border-color:#3a3a44}
/* form controls */
label{display:block;font-size:13px;color:var(--mu);margin:12px 0 4px}
input,select{width:100%;min-height:44px;background:#0c0c0f;border:1px solid var(--line);border-radius:10px;color:var(--tx);padding:8px 12px;font:inherit}
input:focus,select:focus{outline:none;border-color:var(--grn);box-shadow:0 0 0 3px rgba(0,255,102,.15)}
input[type=checkbox]{width:22px;height:22px;min-height:0;accent-color:var(--grn)}
button{min-height:44px;background:var(--grn);color:#03140a;border:0;border-radius:10px;padding:8px 16px;font:inherit;font-weight:700;cursor:pointer;transition:transform .12s,box-shadow .2s,opacity .2s}
button:hover{box-shadow:0 0 16px rgba(0,255,102,.3)}
button:active{transform:scale(.97)}
button:disabled{opacity:.4;cursor:not-allowed;box-shadow:none}
button.sec{background:var(--card2);color:var(--tx);border:1px solid var(--line)}
button.sec:hover{border-color:#3a3a44;box-shadow:none}
button.dan{background:rgba(255,69,69,.12);color:var(--red);border:1px solid rgba(255,69,69,.4)}
button.dan:hover{box-shadow:0 0 14px rgba(255,69,69,.25)}
button.warn{background:rgba(245,165,36,.12);color:var(--amb);border:1px solid rgba(245,165,36,.45)}
button.warn:hover{box-shadow:0 0 14px rgba(245,165,36,.25)}
button.gold{background:rgba(212,175,55,.12);color:var(--gold);border:1px solid rgba(212,175,55,.45)}
button.gold:hover{box-shadow:0 0 14px rgba(212,175,55,.25)}
button.sm{min-height:36px;padding:4px 12px;font-size:13px}
.row{display:flex;flex-wrap:wrap;gap:10px;align-items:flex-end}
.row>.f{flex:1 1 150px;min-width:0}
.btns{display:flex;flex-wrap:wrap;gap:8px}
.toolbar{display:grid;gap:10px;grid-template-columns:minmax(0,1fr)}
/* key list */
.klist{display:grid;gap:12px;grid-template-columns:minmax(0,1fr)}
.krow{background:var(--card2);border:1px solid var(--line);border-radius:14px;padding:12px;min-width:0}
.khead{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.key{font-weight:700;font-size:14px;letter-spacing:.02em;overflow-wrap:anywhere;flex:1 1 200px}
.kmeta{margin:6px 0 10px;font-size:12.5px;color:var(--mu);overflow-wrap:anywhere}
.acts{display:grid;gap:8px;grid-template-columns:repeat(auto-fit,minmax(112px,1fr))}
.acts button{min-height:40px;padding:6px 8px;font-size:13px}
.badge{font-size:11px;font-weight:800;letter-spacing:.05em;text-transform:uppercase;padding:3px 10px;border-radius:999px;border:1px solid var(--line);flex:none}
.b-Active{color:var(--grn);border-color:rgba(0,255,102,.5);background:rgba(0,255,102,.08)}
.b-Unused{color:var(--blu);border-color:rgba(90,169,255,.5);background:rgba(90,169,255,.08)}
.b-Expired{color:var(--mu)}
.b-Suspended{color:var(--amb);border-color:rgba(245,165,36,.55);background:rgba(245,165,36,.1)}
.b-Revoked{color:var(--red);border-color:rgba(255,69,69,.5);background:rgba(255,69,69,.08)}
.renew{margin-top:10px;padding:10px;border:1px solid #2f2f36;border-radius:12px;background:#0e0e11}
/* new keys output */
.newkey{display:flex;align-items:center;gap:8px;padding:8px 10px;border:1px solid var(--line);border-radius:10px;background:var(--card2);margin-top:8px}
.newkey code{flex:1;overflow-wrap:anywhere;font-weight:700}
/* activity */
.ev{display:grid;grid-template-columns:auto 1fr;gap:2px 10px;padding:10px 0;border-bottom:1px solid var(--line)}
.ev:last-child{border-bottom:0}
.evt{grid-row:span 2;font-size:12px;color:var(--mu);min-width:86px}
.evh{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.evb{font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.05em;padding:2px 9px;border-radius:999px;border:1px solid var(--line)}
.e-generate,.e-resume,.e-restore{color:var(--grn);border-color:rgba(0,255,102,.45)}
.e-renew,.e-force_update{color:var(--gold);border-color:rgba(212,175,55,.5)}
.e-suspend{color:var(--amb);border-color:rgba(245,165,36,.5)}
.e-revoke,.e-delete,.e-purge_revoked{color:var(--red);border-color:rgba(255,69,69,.5)}
.e-unbind{color:var(--blu);border-color:rgba(90,169,255,.5)}
.e-feedback_delete{color:var(--red);border-color:rgba(255,69,69,.5)}
.fb{background:var(--card2);border:1px solid var(--line);border-radius:14px;padding:12px;margin-top:10px;min-width:0}
.fbhead{display:flex;flex-wrap:wrap;gap:6px 12px;align-items:center}
.stars{color:var(--gold);letter-spacing:2px;font-size:16px}
.fbwho{font-weight:700;overflow-wrap:anywhere}
.fbtime{font-size:12px;color:var(--mu);margin-inline-start:auto}
.fbmsg{margin:8px 0 2px;white-space:pre-wrap;overflow-wrap:anywhere;unicode-bidi:plaintext}
.evk{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px}
.evd{grid-column:2;font-size:13px;color:var(--mu);overflow-wrap:anywhere}
.diagbox{white-space:pre-wrap;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px;line-height:1.55;background:#0c0c0f;border:1px solid var(--line);border-radius:12px;padding:12px;overflow-wrap:anywhere}
.w100{width:90px;flex:none}
.msg{margin-top:8px;font-size:13px;color:var(--mu);min-height:1em}
@media(min-width:640px){.stats{grid-template-columns:repeat(3,minmax(0,1fr))}.toolbar{grid-template-columns:minmax(0,2fr) minmax(0,1fr)}}
@media(min-width:900px){
body{padding-bottom:24px}
.stats{grid-template-columns:repeat(6,minmax(0,1fr))}
.cols{grid-template-columns:repeat(2,minmax(0,1fr))}
.klist{grid-template-columns:repeat(2,minmax(0,1fr))}
.tabs{position:sticky;top:var(--topH,58px);bottom:auto;grid-template-columns:repeat(6,auto);justify-content:start;gap:4px;padding:8px 16px;border-top:0;border-bottom:1px solid var(--line);background:rgba(8,8,10,.95)}
.tabs button{flex-direction:row;gap:8px;min-height:40px;padding:0 16px;border-radius:10px;font-size:14px}
.tabs button.on{background:rgba(0,255,102,.1)}
}
@media(min-width:900px){.bdg{top:7px;inset-inline-start:auto;inset-inline-end:3px}}
@media(max-width:380px){.tabs button{font-size:10.5px}.acts{grid-template-columns:repeat(2,minmax(0,1fr))}}
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

<div id="login" class="loginwrap">
  <div class="card login">
    <h1>Thieves Trap Admin</h1>
    <p class="small">Private panel. Enter the admin token.</p>
    <label for="tok">Admin token</label>
    <input id="tok" type="password" autocomplete="off" autocapitalize="off" spellcheck="false">
    <div class="mt"><button id="btnLogin" type="button">Sign in</button></div>
    <div id="loginMsg" class="msg"></div>
  </div>
</div>

<div id="app" hidden>
  <header class="top" id="topbar">
    <div class="brand">THIEVES TRAP <span>Admin</span></div>
    <div class="grow"></div>
    <button class="sec sm" id="btnOut" type="button">Sign out</button>
  </header>
  <nav class="tabs" id="tabs" aria-label="Sections">
    <button type="button" data-tab="overview" class="on"><svg viewBox="0 0 24 24"><path d="M4 13h6V4H4zM14 20h6V4h-6zM4 20h6v-3H4z"/></svg><span>Overview</span></button>
    <button type="button" data-tab="keys"><svg viewBox="0 0 24 24"><circle cx="8" cy="12" r="4"/><path d="M12 12h9M18 12v4M15 12v3"/></svg><span>Keys</span></button>
    <button type="button" data-tab="generate"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg><span>Generate</span></button>
    <button type="button" data-tab="activity"><svg viewBox="0 0 24 24"><path d="M3 12h4l3-8 4 16 3-8h4"/></svg><span>Activity</span></button>
    <button type="button" data-tab="feedback"><svg viewBox="0 0 24 24"><path d="M4 5h16v11H9l-5 4z"/></svg><span>Feedback</span><i class="bdg" id="fbBadge" hidden></i></button>
    <button type="button" data-tab="system"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/></svg><span>System</span></button>
  </nav>

  <main class="wrap">
    <section id="tab-overview" class="tab on">
      <div class="stats" id="stats"></div>
      <div class="cols">
        <div class="card">
          <h2>Quick actions</h2>
          <div class="small">Common tasks.</div>
          <div class="btns mt">
            <button type="button" data-tab="generate">Generate keys</button>
            <button type="button" class="sec" data-tab="keys">Manage keys</button>
            <button type="button" class="sec" data-tab="system">Force Update</button>
          </div>
        </div>
        <div class="card">
          <h2>Recent activity</h2>
          <div id="recent" class="small">Loading...</div>
          <div class="mt"><button type="button" class="sec sm" data-tab="activity">Open activity log</button></div>
        </div>
      </div>
    </section>

    <section id="tab-generate" class="tab">
      <div class="card">
        <h2>Generate license keys</h2>
        <div class="row mt2">
          <div class="f"><label for="plan">Plan</label>
            <select id="plan">
              <option value="monthly">Monthly (30 days)</option>
              <option value="3months">3 Months (90 days)</option>
              <option value="annual">Annual (365 days)</option>
              <option value="lifetime">Lifetime (no expiry)</option>
              <option value="trial">Trial (custom days, max 14)</option>
            </select></div>
          <div class="f"><label for="count">How many (1-50)</label><input id="count" type="number" min="1" max="50" value="1" inputmode="numeric"></div>
        </div>
        <div id="trialBox" hidden>
          <label for="trialDays">Trial length in days (1-14)</label>
          <div class="btns">
            <input id="trialDays" type="number" min="1" max="14" value="3" inputmode="numeric" class="w100">
            <button type="button" class="sec sm" data-days="1">1</button><button type="button" class="sec sm" data-days="3">3</button><button type="button" class="sec sm" data-days="5">5</button><button type="button" class="sec sm" data-days="7">7</button><button type="button" class="sec sm" data-days="14">14</button>
          </div>
          <div class="small mt2">The trial clock starts when the key is first activated on a phone.</div>
        </div>
        <label for="note">Note (optional, e.g. customer name)</label>
        <input id="note" type="text" maxlength="120" autocomplete="off">
        <div class="mt"><button id="btnGen" type="button">Generate</button></div>
        <div id="genMsg" class="msg"></div>
        <div id="genOut" hidden>
          <div id="genList"></div>
          <div class="btns mt"><button type="button" class="sec" id="btnCopyAll">Copy all</button><button type="button" class="gold" id="btnPrintGen">Print these tickets</button></div>
        </div>
      </div>
    </section>

    <section id="tab-keys" class="tab">
      <div class="card">
        <h2>Licenses</h2>
        <div class="toolbar mt2">
          <div><label for="search">Search key or note</label><input id="search" type="search" autocomplete="off"></div>
          <div><label for="filter">Status</label>
            <select id="filter"><option value="">All</option><option>Unused</option><option>Active</option><option>Expired</option><option>Suspended</option><option>Revoked</option></select></div>
        </div>
        <div class="row mt">
          <label class="f"><input id="selAll" type="checkbox"> Select all shown</label>
          <span class="small" id="selCount">0 selected</span>
        </div>
        <div class="btns mt">
          <button type="button" class="gold" id="btnPrint" disabled>Print Selected Tickets</button>
          <button type="button" class="sec" id="btnRefresh">Refresh</button>
          <button type="button" class="dan" id="btnPurge">Delete all revoked</button>
        </div>
        <div id="listMsg" class="msg"></div>
      </div>
      <div id="klist" class="klist"></div>
    </section>

    <section id="tab-activity" class="tab">
      <div class="card">
        <div class="row">
          <div class="f"><h2>Admin activity</h2><div class="small">Key generation, renewals, suspensions, revocations and Force Update changes. Kept for 90 days. A new entry can take up to a minute to show after a refresh.</div></div>
          <button type="button" class="sec" id="btnAuditRefresh">Refresh</button>
        </div>
        <div id="auditList" class="mt"></div>
        <div class="mt"><button type="button" class="sec" id="btnAuditMore" hidden>Load older</button></div>
        <div id="auditMsg" class="msg"></div>
      </div>
    </section>

    <section id="tab-feedback" class="tab">
      <div class="card">
        <div class="row">
          <div class="f"><h2>Visitor feedback</h2><div class="small">Messages sent from the website. Read them here and delete the ones you have handled. Entries are also removed automatically after one year.</div></div>
          <button type="button" class="sec" id="btnFbRefresh">Refresh</button>
        </div>
        <div id="fbList"></div>
        <div class="mt"><button type="button" class="sec" id="btnFbMore" hidden>Load older</button></div>
        <div id="fbMsg" class="msg"></div>
      </div>
    </section>

    <section id="tab-system" class="tab">
      <div class="cols">
        <div class="card">
          <h2>Force Update</h2>
          <div class="small">Lowest app versionCode allowed to open the app. Older builds see a mandatory "Update Required" dialog (their protection keeps running). 0 = no minimum. Only raise it to a versionCode that is ALREADY released: the app ignores a minimum that has no APK yet.</div>
          <div class="row mt">
            <div class="f"><label for="minv">Minimum versionCode</label><input id="minv" type="number" min="0" step="1" inputmode="numeric"></div>
            <button id="btnMinSave" type="button">Save</button>
          </div>
          <div id="minMsg" class="msg"></div>
        </div>
        <div class="card">
          <h2>Diagnostics</h2>
          <div class="small">Checks this panel, the key database and the public Worker.</div>
          <div class="mt"><button id="btnDiag" type="button" class="sec">Run diagnostics</button></div>
          <div id="diag" class="diagbox mt" hidden></div>
        </div>
      </div>
    </section>
  </main>
</div>

<div id="printArea"></div>

<script nonce="__NONCE__">
/* QR Code Generator for JavaScript, Copyright (c) 2009 Kazuhiko Arase, MIT license (qrcode-generator 2.0.4, minified) */
var qrcode=function(){var t=function(t,r){var e=t,n=g[r],o=null,i=0,a=null,u=[],f={},c=function(t,r){o=function(t){for(var r=new Array(t),e=0;e<t;e+=1){r[e]=new Array(t);for(var n=0;n<t;n+=1)r[e][n]=null}return r}(i=4*e+17),l(0,0),l(i-7,0),l(0,i-7),s(),h(),d(t,r),e>=7&&v(t),null==a&&(a=p(e,n,u)),w(a,r)},l=function(t,r){for(var e=-1;e<=7;e+=1)if(!(t+e<=-1||i<=t+e))for(var n=-1;n<=7;n+=1)r+n<=-1||i<=r+n||(o[t+e][r+n]=0<=e&&e<=6&&(0==n||6==n)||0<=n&&n<=6&&(0==e||6==e)||2<=e&&e<=4&&2<=n&&n<=4)},h=function(){for(var t=8;t<i-8;t+=1)null==o[t][6]&&(o[t][6]=t%2==0);for(var r=8;r<i-8;r+=1)null==o[6][r]&&(o[6][r]=r%2==0)},s=function(){for(var t=B.getPatternPosition(e),r=0;r<t.length;r+=1)for(var n=0;n<t.length;n+=1){var i=t[r],a=t[n];if(null==o[i][a])for(var u=-2;u<=2;u+=1)for(var f=-2;f<=2;f+=1)o[i+u][a+f]=-2==u||2==u||-2==f||2==f||0==u&&0==f}},v=function(t){for(var r=B.getBCHTypeNumber(e),n=0;n<18;n+=1){var a=!t&&1==(r>>n&1);o[Math.floor(n/3)][n%3+i-8-3]=a}for(n=0;n<18;n+=1){a=!t&&1==(r>>n&1);o[n%3+i-8-3][Math.floor(n/3)]=a}},d=function(t,r){for(var e=n<<3|r,a=B.getBCHTypeInfo(e),u=0;u<15;u+=1){var f=!t&&1==(a>>u&1);u<6?o[u][8]=f:u<8?o[u+1][8]=f:o[i-15+u][8]=f}for(u=0;u<15;u+=1){f=!t&&1==(a>>u&1);u<8?o[8][i-u-1]=f:u<9?o[8][15-u-1+1]=f:o[8][15-u-1]=f}o[i-8][8]=!t},w=function(t,r){for(var e=-1,n=i-1,a=7,u=0,f=B.getMaskFunction(r),c=i-1;c>0;c-=2)for(6==c&&(c-=1);;){for(var g=0;g<2;g+=1)if(null==o[n][c-g]){var l=!1;u<t.length&&(l=1==(t[u]>>>a&1)),f(n,c-g)&&(l=!l),o[n][c-g]=l,-1==(a-=1)&&(u+=1,a=7)}if((n+=e)<0||i<=n){n-=e,e=-e;break}}},p=function(t,r,e){for(var n=A.getRSBlocks(t,r),o=b(),i=0;i<e.length;i+=1){var a=e[i];o.put(a.getMode(),4),o.put(a.getLength(),B.getLengthInBits(a.getMode(),t)),a.write(o)}var u=0;for(i=0;i<n.length;i+=1)u+=n[i].dataCount;if(o.getLengthInBits()>8*u)throw"code length overflow. ("+o.getLengthInBits()+">"+8*u+")";for(o.getLengthInBits()+4<=8*u&&o.put(0,4);o.getLengthInBits()%8!=0;)o.putBit(!1);for(;!(o.getLengthInBits()>=8*u||(o.put(236,8),o.getLengthInBits()>=8*u));)o.put(17,8);return function(t,r){for(var e=0,n=0,o=0,i=new Array(r.length),a=new Array(r.length),u=0;u<r.length;u+=1){var f=r[u].dataCount,c=r[u].totalCount-f;n=Math.max(n,f),o=Math.max(o,c),i[u]=new Array(f);for(var g=0;g<i[u].length;g+=1)i[u][g]=255&t.getBuffer()[g+e];e+=f;var l=B.getErrorCorrectPolynomial(c),h=k(i[u],l.getLength()-1).mod(l);for(a[u]=new Array(l.getLength()-1),g=0;g<a[u].length;g+=1){var s=g+h.getLength()-a[u].length;a[u][g]=s>=0?h.getAt(s):0}}var v=0;for(g=0;g<r.length;g+=1)v+=r[g].totalCount;var d=new Array(v),w=0;for(g=0;g<n;g+=1)for(u=0;u<r.length;u+=1)g<i[u].length&&(d[w]=i[u][g],w+=1);for(g=0;g<o;g+=1)for(u=0;u<r.length;u+=1)g<a[u].length&&(d[w]=a[u][g],w+=1);return d}(o,n)};f.addData=function(t,r){var e=null;switch(r=r||"Byte"){case"Numeric":e=M(t);break;case"Alphanumeric":e=x(t);break;case"Byte":e=m(t);break;case"Kanji":e=L(t);break;default:throw"mode:"+r}u.push(e),a=null},f.isDark=function(t,r){if(t<0||i<=t||r<0||i<=r)throw t+","+r;return o[t][r]},f.getModuleCount=function(){return i},f.make=function(){if(e<1){for(var t=1;t<40;t++){for(var r=A.getRSBlocks(t,n),o=b(),i=0;i<u.length;i++){var a=u[i];o.put(a.getMode(),4),o.put(a.getLength(),B.getLengthInBits(a.getMode(),t)),a.write(o)}var g=0;for(i=0;i<r.length;i++)g+=r[i].dataCount;if(o.getLengthInBits()<=8*g)break}e=t}c(!1,function(){for(var t=0,r=0,e=0;e<8;e+=1){c(!0,e);var n=B.getLostPoint(f);(0==e||t>n)&&(t=n,r=e)}return r}())},f.createTableTag=function(t,r){t=t||2;var e="";e+='<table style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: "+(r=void 0===r?4*t:r)+"px;",e+='">',e+="<tbody>";for(var n=0;n<f.getModuleCount();n+=1){e+="<tr>";for(var o=0;o<f.getModuleCount();o+=1)e+='<td style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: 0px;",e+=" width: "+t+"px;",e+=" height: "+t+"px;",e+=" background-color: ",e+=f.isDark(n,o)?"#000000":"#ffffff",e+=";",e+='"/>';e+="</tr>"}return e+="</tbody>",e+="</table>"},f.createSvgTag=function(t,r,e,n){var o={};"object"==typeof arguments[0]&&(t=(o=arguments[0]).cellSize,r=o.margin,e=o.alt,n=o.title),t=t||2,r=void 0===r?4*t:r,(e="string"==typeof e?{text:e}:e||{}).text=e.text||null,e.id=e.text?e.id||"qrcode-description":null,(n="string"==typeof n?{text:n}:n||{}).text=n.text||null,n.id=n.text?n.id||"qrcode-title":null;var i,a,u,c,g=f.getModuleCount()*t+2*r,l="";for(c="l"+t+",0 0,"+t+" -"+t+",0 0,-"+t+"z ",l+='<svg version="1.1" xmlns="http://www.w3.org/2000/svg"',l+=o.scalable?"":' width="'+g+'px" height="'+g+'px"',l+=' viewBox="0 0 '+g+" "+g+'" ',l+=' preserveAspectRatio="xMinYMin meet"',l+=n.text||e.text?' role="img" aria-labelledby="'+y([n.id,e.id].join(" ").trim())+'"':"",l+=">",l+=n.text?'<title id="'+y(n.id)+'">'+y(n.text)+"</title>":"",l+=e.text?'<description id="'+y(e.id)+'">'+y(e.text)+"</description>":"",l+='<rect width="100%" height="100%" fill="white" cx="0" cy="0"/>',l+='<path d="',a=0;a<f.getModuleCount();a+=1)for(u=a*t+r,i=0;i<f.getModuleCount();i+=1)f.isDark(a,i)&&(l+="M"+(i*t+r)+","+u+c);return l+='" stroke="transparent" fill="black"/>',l+="</svg>"},f.createDataURL=function(t,r){t=t||2,r=void 0===r?4*t:r;var e=f.getModuleCount()*t+2*r,n=r,o=e-r;return I(e,e,function(r,e){if(n<=r&&r<o&&n<=e&&e<o){var i=Math.floor((r-n)/t),a=Math.floor((e-n)/t);return f.isDark(a,i)?0:1}return 1})},f.createImgTag=function(t,r,e){t=t||2,r=void 0===r?4*t:r;var n=f.getModuleCount()*t+2*r,o="";return o+="<img",o+=' src="',o+=f.createDataURL(t,r),o+='"',o+=' width="',o+=n,o+='"',o+=' height="',o+=n,o+='"',e&&(o+=' alt="',o+=y(e),o+='"'),o+="/>"};var y=function(t){for(var r="",e=0;e<t.length;e+=1){var n=t.charAt(e);switch(n){case"<":r+="&lt;";break;case">":r+="&gt;";break;case"&":r+="&amp;";break;case'"':r+="&quot;";break;default:r+=n}}return r};return f.createASCII=function(t,r){if((t=t||1)<2)return function(t){t=void 0===t?2:t;var r,e,n,o,i,a=1*f.getModuleCount()+2*t,u=t,c=a-t,g={"██":"█","█ ":"▀"," █":"▄","  ":" "},l={"██":"▀","█ ":"▀"," █":" ","  ":" "},h="";for(r=0;r<a;r+=2){for(n=Math.floor((r-u)/1),o=Math.floor((r+1-u)/1),e=0;e<a;e+=1)i="█",u<=e&&e<c&&u<=r&&r<c&&f.isDark(n,Math.floor((e-u)/1))&&(i=" "),u<=e&&e<c&&u<=r+1&&r+1<c&&f.isDark(o,Math.floor((e-u)/1))?i+=" ":i+="█",h+=t<1&&r+1>=c?l[i]:g[i];h+="\\n"}return a%2&&t>0?h.substring(0,h.length-a-1)+Array(a+1).join("▀"):h.substring(0,h.length-1)}(r);t-=1,r=void 0===r?2*t:r;var e,n,o,i,a=f.getModuleCount()*t+2*r,u=r,c=a-r,g=Array(t+1).join("██"),l=Array(t+1).join("  "),h="",s="";for(e=0;e<a;e+=1){for(o=Math.floor((e-u)/t),s="",n=0;n<a;n+=1)i=1,u<=n&&n<c&&u<=e&&e<c&&f.isDark(o,Math.floor((n-u)/t))&&(i=0),s+=i?g:l;for(o=0;o<t;o+=1)h+=s+"\\n"}return h.substring(0,h.length-1)},f.renderTo2dContext=function(t,r){r=r||2;for(var e=f.getModuleCount(),n=0;n<e;n++)for(var o=0;o<e;o++)t.fillStyle=f.isDark(n,o)?"black":"white",t.fillRect(o*r,n*r,r,r)},f};t.stringToBytes=(t.stringToBytesFuncs={default:function(t){for(var r=[],e=0;e<t.length;e+=1){var n=t.charCodeAt(e);r.push(255&n)}return r}}).default,t.createStringToBytes=function(t,r){var e=function(){for(var e=S(t),n=function(){var t=e.read();if(-1==t)throw"eof";return t},o=0,i={};;){var a=e.read();if(-1==a)break;var u=n(),f=n()<<8|n();i[String.fromCharCode(a<<8|u)]=f,o+=1}if(o!=r)throw o+" != "+r;return i}(),n="?".charCodeAt(0);return function(t){for(var r=[],o=0;o<t.length;o+=1){var i=t.charCodeAt(o);if(i<128)r.push(i);else{var a=e[t.charAt(o)];"number"==typeof a?(255&a)==a?r.push(a):(r.push(a>>>8),r.push(255&a)):r.push(n)}}return r}};var r,e,n,o,i,a=1,u=2,f=4,c=8,g={L:1,M:0,Q:3,H:2},l=0,h=1,s=2,v=3,d=4,w=5,p=6,y=7,B=(r=[[],[6,18],[6,22],[6,26],[6,30],[6,34],[6,22,38],[6,24,42],[6,26,46],[6,28,50],[6,30,54],[6,32,58],[6,34,62],[6,26,46,66],[6,26,48,70],[6,26,50,74],[6,30,54,78],[6,30,56,82],[6,30,58,86],[6,34,62,90],[6,28,50,72,94],[6,26,50,74,98],[6,30,54,78,102],[6,28,54,80,106],[6,32,58,84,110],[6,30,58,86,114],[6,34,62,90,118],[6,26,50,74,98,122],[6,30,54,78,102,126],[6,26,52,78,104,130],[6,30,56,82,108,134],[6,34,60,86,112,138],[6,30,58,86,114,142],[6,34,62,90,118,146],[6,30,54,78,102,126,150],[6,24,50,76,102,128,154],[6,28,54,80,106,132,158],[6,32,58,84,110,136,162],[6,26,54,82,110,138,166],[6,30,58,86,114,142,170]],e=1335,n=7973,i=function(t){for(var r=0;0!=t;)r+=1,t>>>=1;return r},(o={}).getBCHTypeInfo=function(t){for(var r=t<<10;i(r)-i(e)>=0;)r^=e<<i(r)-i(e);return 21522^(t<<10|r)},o.getBCHTypeNumber=function(t){for(var r=t<<12;i(r)-i(n)>=0;)r^=n<<i(r)-i(n);return t<<12|r},o.getPatternPosition=function(t){return r[t-1]},o.getMaskFunction=function(t){switch(t){case l:return function(t,r){return(t+r)%2==0};case h:return function(t,r){return t%2==0};case s:return function(t,r){return r%3==0};case v:return function(t,r){return(t+r)%3==0};case d:return function(t,r){return(Math.floor(t/2)+Math.floor(r/3))%2==0};case w:return function(t,r){return t*r%2+t*r%3==0};case p:return function(t,r){return(t*r%2+t*r%3)%2==0};case y:return function(t,r){return(t*r%3+(t+r)%2)%2==0};default:throw"bad maskPattern:"+t}},o.getErrorCorrectPolynomial=function(t){for(var r=k([1],0),e=0;e<t;e+=1)r=r.multiply(k([1,C.gexp(e)],0));return r},o.getLengthInBits=function(t,r){if(1<=r&&r<10)switch(t){case a:return 10;case u:return 9;case f:case c:return 8;default:throw"mode:"+t}else if(r<27)switch(t){case a:return 12;case u:return 11;case f:return 16;case c:return 10;default:throw"mode:"+t}else{if(!(r<41))throw"type:"+r;switch(t){case a:return 14;case u:return 13;case f:return 16;case c:return 12;default:throw"mode:"+t}}},o.getLostPoint=function(t){for(var r=t.getModuleCount(),e=0,n=0;n<r;n+=1)for(var o=0;o<r;o+=1){for(var i=0,a=t.isDark(n,o),u=-1;u<=1;u+=1)if(!(n+u<0||r<=n+u))for(var f=-1;f<=1;f+=1)o+f<0||r<=o+f||0==u&&0==f||a==t.isDark(n+u,o+f)&&(i+=1);i>5&&(e+=3+i-5)}for(n=0;n<r-1;n+=1)for(o=0;o<r-1;o+=1){var c=0;t.isDark(n,o)&&(c+=1),t.isDark(n+1,o)&&(c+=1),t.isDark(n,o+1)&&(c+=1),t.isDark(n+1,o+1)&&(c+=1),0!=c&&4!=c||(e+=3)}for(n=0;n<r;n+=1)for(o=0;o<r-6;o+=1)t.isDark(n,o)&&!t.isDark(n,o+1)&&t.isDark(n,o+2)&&t.isDark(n,o+3)&&t.isDark(n,o+4)&&!t.isDark(n,o+5)&&t.isDark(n,o+6)&&(e+=40);for(o=0;o<r;o+=1)for(n=0;n<r-6;n+=1)t.isDark(n,o)&&!t.isDark(n+1,o)&&t.isDark(n+2,o)&&t.isDark(n+3,o)&&t.isDark(n+4,o)&&!t.isDark(n+5,o)&&t.isDark(n+6,o)&&(e+=40);var g=0;for(o=0;o<r;o+=1)for(n=0;n<r;n+=1)t.isDark(n,o)&&(g+=1);return e+=Math.abs(100*g/r/r-50)/5*10},o),C=function(){for(var t=new Array(256),r=new Array(256),e=0;e<8;e+=1)t[e]=1<<e;for(e=8;e<256;e+=1)t[e]=t[e-4]^t[e-5]^t[e-6]^t[e-8];for(e=0;e<255;e+=1)r[t[e]]=e;var n={glog:function(t){if(t<1)throw"glog("+t+")";return r[t]},gexp:function(r){for(;r<0;)r+=255;for(;r>=256;)r-=255;return t[r]}};return n}();function k(t,r){if(void 0===t.length)throw t.length+"/"+r;var e=function(){for(var e=0;e<t.length&&0==t[e];)e+=1;for(var n=new Array(t.length-e+r),o=0;o<t.length-e;o+=1)n[o]=t[o+e];return n}(),n={getAt:function(t){return e[t]},getLength:function(){return e.length},multiply:function(t){for(var r=new Array(n.getLength()+t.getLength()-1),e=0;e<n.getLength();e+=1)for(var o=0;o<t.getLength();o+=1)r[e+o]^=C.gexp(C.glog(n.getAt(e))+C.glog(t.getAt(o)));return k(r,0)},mod:function(t){if(n.getLength()-t.getLength()<0)return n;for(var r=C.glog(n.getAt(0))-C.glog(t.getAt(0)),e=new Array(n.getLength()),o=0;o<n.getLength();o+=1)e[o]=n.getAt(o);for(o=0;o<t.getLength();o+=1)e[o]^=C.gexp(C.glog(t.getAt(o))+r);return k(e,0).mod(t)}};return n}var A=function(){var t=[[1,26,19],[1,26,16],[1,26,13],[1,26,9],[1,44,34],[1,44,28],[1,44,22],[1,44,16],[1,70,55],[1,70,44],[2,35,17],[2,35,13],[1,100,80],[2,50,32],[2,50,24],[4,25,9],[1,134,108],[2,67,43],[2,33,15,2,34,16],[2,33,11,2,34,12],[2,86,68],[4,43,27],[4,43,19],[4,43,15],[2,98,78],[4,49,31],[2,32,14,4,33,15],[4,39,13,1,40,14],[2,121,97],[2,60,38,2,61,39],[4,40,18,2,41,19],[4,40,14,2,41,15],[2,146,116],[3,58,36,2,59,37],[4,36,16,4,37,17],[4,36,12,4,37,13],[2,86,68,2,87,69],[4,69,43,1,70,44],[6,43,19,2,44,20],[6,43,15,2,44,16],[4,101,81],[1,80,50,4,81,51],[4,50,22,4,51,23],[3,36,12,8,37,13],[2,116,92,2,117,93],[6,58,36,2,59,37],[4,46,20,6,47,21],[7,42,14,4,43,15],[4,133,107],[8,59,37,1,60,38],[8,44,20,4,45,21],[12,33,11,4,34,12],[3,145,115,1,146,116],[4,64,40,5,65,41],[11,36,16,5,37,17],[11,36,12,5,37,13],[5,109,87,1,110,88],[5,65,41,5,66,42],[5,54,24,7,55,25],[11,36,12,7,37,13],[5,122,98,1,123,99],[7,73,45,3,74,46],[15,43,19,2,44,20],[3,45,15,13,46,16],[1,135,107,5,136,108],[10,74,46,1,75,47],[1,50,22,15,51,23],[2,42,14,17,43,15],[5,150,120,1,151,121],[9,69,43,4,70,44],[17,50,22,1,51,23],[2,42,14,19,43,15],[3,141,113,4,142,114],[3,70,44,11,71,45],[17,47,21,4,48,22],[9,39,13,16,40,14],[3,135,107,5,136,108],[3,67,41,13,68,42],[15,54,24,5,55,25],[15,43,15,10,44,16],[4,144,116,4,145,117],[17,68,42],[17,50,22,6,51,23],[19,46,16,6,47,17],[2,139,111,7,140,112],[17,74,46],[7,54,24,16,55,25],[34,37,13],[4,151,121,5,152,122],[4,75,47,14,76,48],[11,54,24,14,55,25],[16,45,15,14,46,16],[6,147,117,4,148,118],[6,73,45,14,74,46],[11,54,24,16,55,25],[30,46,16,2,47,17],[8,132,106,4,133,107],[8,75,47,13,76,48],[7,54,24,22,55,25],[22,45,15,13,46,16],[10,142,114,2,143,115],[19,74,46,4,75,47],[28,50,22,6,51,23],[33,46,16,4,47,17],[8,152,122,4,153,123],[22,73,45,3,74,46],[8,53,23,26,54,24],[12,45,15,28,46,16],[3,147,117,10,148,118],[3,73,45,23,74,46],[4,54,24,31,55,25],[11,45,15,31,46,16],[7,146,116,7,147,117],[21,73,45,7,74,46],[1,53,23,37,54,24],[19,45,15,26,46,16],[5,145,115,10,146,116],[19,75,47,10,76,48],[15,54,24,25,55,25],[23,45,15,25,46,16],[13,145,115,3,146,116],[2,74,46,29,75,47],[42,54,24,1,55,25],[23,45,15,28,46,16],[17,145,115],[10,74,46,23,75,47],[10,54,24,35,55,25],[19,45,15,35,46,16],[17,145,115,1,146,116],[14,74,46,21,75,47],[29,54,24,19,55,25],[11,45,15,46,46,16],[13,145,115,6,146,116],[14,74,46,23,75,47],[44,54,24,7,55,25],[59,46,16,1,47,17],[12,151,121,7,152,122],[12,75,47,26,76,48],[39,54,24,14,55,25],[22,45,15,41,46,16],[6,151,121,14,152,122],[6,75,47,34,76,48],[46,54,24,10,55,25],[2,45,15,64,46,16],[17,152,122,4,153,123],[29,74,46,14,75,47],[49,54,24,10,55,25],[24,45,15,46,46,16],[4,152,122,18,153,123],[13,74,46,32,75,47],[48,54,24,14,55,25],[42,45,15,32,46,16],[20,147,117,4,148,118],[40,75,47,7,76,48],[43,54,24,22,55,25],[10,45,15,67,46,16],[19,148,118,6,149,119],[18,75,47,31,76,48],[34,54,24,34,55,25],[20,45,15,61,46,16]],r=function(t,r){var e={};return e.totalCount=t,e.dataCount=r,e},e={};return e.getRSBlocks=function(e,n){var o=function(r,e){switch(e){case g.L:return t[4*(r-1)+0];case g.M:return t[4*(r-1)+1];case g.Q:return t[4*(r-1)+2];case g.H:return t[4*(r-1)+3];default:return}}(e,n);if(void 0===o)throw"bad rs block @ typeNumber:"+e+"/errorCorrectionLevel:"+n;for(var i=o.length/3,a=[],u=0;u<i;u+=1)for(var f=o[3*u+0],c=o[3*u+1],l=o[3*u+2],h=0;h<f;h+=1)a.push(r(c,l));return a},e}(),b=function(){var t=[],r=0,e={getBuffer:function(){return t},getAt:function(r){var e=Math.floor(r/8);return 1==(t[e]>>>7-r%8&1)},put:function(t,r){for(var n=0;n<r;n+=1)e.putBit(1==(t>>>r-n-1&1))},getLengthInBits:function(){return r},putBit:function(e){var n=Math.floor(r/8);t.length<=n&&t.push(0),e&&(t[n]|=128>>>r%8),r+=1}};return e},M=function(t){var r=a,e=t,n={getMode:function(){return r},getLength:function(t){return e.length},write:function(t){for(var r=e,n=0;n+2<r.length;)t.put(o(r.substring(n,n+3)),10),n+=3;n<r.length&&(r.length-n==1?t.put(o(r.substring(n,n+1)),4):r.length-n==2&&t.put(o(r.substring(n,n+2)),7))}},o=function(t){for(var r=0,e=0;e<t.length;e+=1)r=10*r+i(t.charAt(e));return r},i=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);throw"illegal char :"+t};return n},x=function(t){var r=u,e=t,n={getMode:function(){return r},getLength:function(t){return e.length},write:function(t){for(var r=e,n=0;n+1<r.length;)t.put(45*o(r.charAt(n))+o(r.charAt(n+1)),11),n+=2;n<r.length&&t.put(o(r.charAt(n)),6)}},o=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);if("A"<=t&&t<="Z")return t.charCodeAt(0)-"A".charCodeAt(0)+10;switch(t){case" ":return 36;case"$":return 37;case"%":return 38;case"*":return 39;case"+":return 40;case"-":return 41;case".":return 42;case"/":return 43;case":":return 44;default:throw"illegal char :"+t}};return n},m=function(r){var e=f,n=t.stringToBytes(r),o={getMode:function(){return e},getLength:function(t){return n.length},write:function(t){for(var r=0;r<n.length;r+=1)t.put(n[r],8)}};return o},L=function(r){var e=c,n=t.stringToBytesFuncs.SJIS;if(!n)throw"sjis not supported.";!function(){var t=n("友");if(2!=t.length||38726!=(t[0]<<8|t[1]))throw"sjis not supported."}();var o=n(r),i={getMode:function(){return e},getLength:function(t){return~~(o.length/2)},write:function(t){for(var r=o,e=0;e+1<r.length;){var n=(255&r[e])<<8|255&r[e+1];if(33088<=n&&n<=40956)n-=33088;else{if(!(57408<=n&&n<=60351))throw"illegal char at "+(e+1)+"/"+n;n-=49472}n=192*(n>>>8&255)+(255&n),t.put(n,13),e+=2}if(e<r.length)throw"illegal char at "+(e+1)}};return i},D=function(){var t=[],r={writeByte:function(r){t.push(255&r)},writeShort:function(t){r.writeByte(t),r.writeByte(t>>>8)},writeBytes:function(t,e,n){e=e||0,n=n||t.length;for(var o=0;o<n;o+=1)r.writeByte(t[o+e])},writeString:function(t){for(var e=0;e<t.length;e+=1)r.writeByte(t.charCodeAt(e))},toByteArray:function(){return t},toString:function(){var r="";r+="[";for(var e=0;e<t.length;e+=1)e>0&&(r+=","),r+=t[e];return r+="]"}};return r},S=function(t){var r=t,e=0,n=0,o=0,i={read:function(){for(;o<8;){if(e>=r.length){if(0==o)return-1;throw"unexpected end of file./"+o}var t=r.charAt(e);if(e+=1,"="==t)return o=0,-1;t.match(/^\\s$/)||(n=n<<6|a(t.charCodeAt(0)),o+=6)}var i=n>>>o-8&255;return o-=8,i}},a=function(t){if(65<=t&&t<=90)return t-65;if(97<=t&&t<=122)return t-97+26;if(48<=t&&t<=57)return t-48+52;if(43==t)return 62;if(47==t)return 63;throw"c:"+t};return i},I=function(t,r,e){for(var n=function(t,r){var e=t,n=r,o=new Array(t*r),i={setPixel:function(t,r,n){o[r*e+t]=n},write:function(t){t.writeString("GIF87a"),t.writeShort(e),t.writeShort(n),t.writeByte(128),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(255),t.writeByte(255),t.writeByte(255),t.writeString(","),t.writeShort(0),t.writeShort(0),t.writeShort(e),t.writeShort(n),t.writeByte(0);var r=a(2);t.writeByte(2);for(var o=0;r.length-o>255;)t.writeByte(255),t.writeBytes(r,o,255),o+=255;t.writeByte(r.length-o),t.writeBytes(r,o,r.length-o),t.writeByte(0),t.writeString(";")}},a=function(t){for(var r=1<<t,e=1+(1<<t),n=t+1,i=u(),a=0;a<r;a+=1)i.add(String.fromCharCode(a));i.add(String.fromCharCode(r)),i.add(String.fromCharCode(e));var f,c,g,l=D(),h=(f=l,c=0,g=0,{write:function(t,r){if(t>>>r!=0)throw"length over";for(;c+r>=8;)f.writeByte(255&(t<<c|g)),r-=8-c,t>>>=8-c,g=0,c=0;g|=t<<c,c+=r},flush:function(){c>0&&f.writeByte(g)}});h.write(r,n);var s=0,v=String.fromCharCode(o[s]);for(s+=1;s<o.length;){var d=String.fromCharCode(o[s]);s+=1,i.contains(v+d)?v+=d:(h.write(i.indexOf(v),n),i.size()<4095&&(i.size()==1<<n&&(n+=1),i.add(v+d)),v=d)}return h.write(i.indexOf(v),n),h.write(e,n),h.flush(),l.toByteArray()},u=function(){var t={},r=0,e={add:function(n){if(e.contains(n))throw"dup key:"+n;t[n]=r,r+=1},size:function(){return r},indexOf:function(r){return t[r]},contains:function(r){return void 0!==t[r]}};return e};return i}(t,r),o=0;o<r;o+=1)for(var i=0;i<t;i+=1)n.setPixel(i,o,e(i,o));var a=D();n.write(a);for(var u=function(){var t=0,r=0,e=0,n="",o={},i=function(t){n+=String.fromCharCode(a(63&t))},a=function(t){if(t<0);else{if(t<26)return 65+t;if(t<52)return t-26+97;if(t<62)return t-52+48;if(62==t)return 43;if(63==t)return 47}throw"n:"+t};return o.writeByte=function(n){for(t=t<<8|255&n,r+=8,e+=1;r>=6;)i(t>>>r-6),r-=6},o.flush=function(){if(r>0&&(i(t<<6-r),t=0,r=0),e%3!=0)for(var o=3-e%3,a=0;a<o;a+=1)n+="="},o.toString=function(){return n},o}(),f=a.toByteArray(),c=0;c<f.length;c+=1)u.writeByte(f[c]);return u.flush(),"data:image/gif;base64,"+u};return t}();qrcode.stringToBytesFuncs["UTF-8"]=function(t){return function(t){for(var r=[],e=0;e<t.length;e++){var n=t.charCodeAt(e);n<128?r.push(n):n<2048?r.push(192|n>>6,128|63&n):n<55296||n>=57344?r.push(224|n>>12,128|n>>6&63,128|63&n):(e++,n=65536+((1023&n)<<10|1023&t.charCodeAt(e)),r.push(240|n>>18,128|n>>12&63,128|n>>6&63,128|63&n))}return r}(t)},function(t){"function"==typeof define&&define.amd?define([],t):"object"==typeof exports&&(module.exports=t())}(function(){return qrcode});
</script>

<script nonce="__NONCE__">
(function () {
  "use strict";
  var token = sessionStorage.getItem("tt_admin") || "";
  var items = [], moreKeys = false, audit = [], auditCursor = null, auditLoaded = false;
  var selected = {}, lastShown = [], lastGen = null, renewOpen = null;
  var PLAN = { monthly: "Monthly", "3months": "3 Months", annual: "Annual", lifetime: "Lifetime", trial: "Trial" };
  var EVENT = { generate: "Generated", renew: "Renewed", suspend: "Suspended", resume: "Resumed", revoke: "Revoked", restore: "Restored",
                unbind: "Unbound", "delete": "Deleted", purge_revoked: "Purged revoked", force_update: "Force Update", feedback_delete: "Feedback deleted" };
  var STATUSES = ["Active", "Unused", "Expired", "Suspended", "Revoked"];

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined && text !== null) e.textContent = text; return e; }
  function btn(label, cls, fn) { var b = el("button", cls || "", label); b.type = "button"; b.addEventListener("click", fn); return b; }

  function api(path, body) {
    return fetch(path, {
      method: "POST",
      headers: { "Authorization": "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, data: j }; });
    });
  }

  function copy(text, b) {
    function done() { var o = b.textContent; b.textContent = "Copied"; setTimeout(function () { b.textContent = o; }, 1200); }
    function fallback() {
      var t = document.createElement("textarea"); t.value = text; document.body.appendChild(t);
      t.select(); try { document.execCommand("copy"); done(); } catch (e) {} document.body.removeChild(t);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback); else fallback();
  }

  function fmt(ts) { return ts ? new Date(ts * 1000).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : ""; }
  function fmtTime(ms) { return new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }); }
  function status(it) {
    if (it.revoked) return "Revoked";
    if (it.suspended) return "Suspended";
    if (!it.activated_at) return "Unused";
    if (it.expires_at && Date.now() / 1000 >= it.expires_at) return "Expired";
    return "Active";
  }
  function planLabel(o) { var base = PLAN[o.plan] || o.plan; return o.plan === "trial" && o.days ? base + " " + o.days + "d" : base; }
  function validityText(plan, days) {
    if (plan === "lifetime") return "Lifetime license - never expires";
    if (plan === "monthly") return "Valid for 30 days from activation";
    if (plan === "3months") return "Valid for 90 days from activation";
    if (plan === "annual") return "Valid for 1 year from activation";
    if (plan === "trial") return days + "-day trial from activation";
    return String(plan);
  }
  function maskKey(k) { var p = String(k || "").split("-"); return p.length === 5 ? p[0] + "-" + p[1] + "-...-" + p[4] : ""; }
  function byNewest(a, b) { return (b.created_at || 0) - (a.created_at || 0); }

  /* ---------- tickets (unchanged design) ---------- */
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

  /* ---------- navigation ---------- */
  var TABS = ["overview", "keys", "generate", "activity", "feedback", "system"];
  function setTab(name, push) {
    if (TABS.indexOf(name) < 0) name = "overview";
    TABS.forEach(function (t) { $("tab-" + t).classList.toggle("on", t === name); });
    Array.prototype.forEach.call($("tabs").querySelectorAll("button"), function (b) { b.classList.toggle("on", b.getAttribute("data-tab") === name); });
    if (push !== false && location.hash !== "#" + name) { try { history.replaceState(null, "", "#" + name); } catch (e) {} }
    if (name === "activity" && !auditLoaded) loadAudit(true);
    if (name === "feedback") { if (!fbLoaded) loadFeedback(true).then(markFbSeen); else markFbSeen(); }
    window.scrollTo(0, 0);
  }
  Array.prototype.forEach.call(document.querySelectorAll("[data-tab]"), function (b) {
    b.addEventListener("click", function () {
      var f = b.getAttribute("data-filter");
      if (f !== null) { $("filter").value = f; render(); }
      setTab(b.getAttribute("data-tab"));
    });
  });
  function measureTop() { var t = $("topbar"); if (t) document.documentElement.style.setProperty("--topH", t.offsetHeight + "px"); }
  window.addEventListener("resize", measureTop);

  /* ---------- keys ---------- */
  function loadKeys() {
    var acc = [], pages = 0;
    $("listMsg").textContent = "Loading...";
    function page(cur) {
      return api("/admin/list", { limit: 200, cursor: cur }).then(function (r) {
        if (r.status === 401) { logout("Session expired. Sign in again."); return; }
        if (r.status !== 200) { $("listMsg").textContent = "Error " + r.status; return; }
        acc = acc.concat(r.data.items || []); pages++;
        moreKeys = !!r.data.cursor;
        if (r.data.cursor && pages < 10) return page(r.data.cursor);
      });
    }
    return page(null).then(function () {
      items = acc.sort(byNewest);
      $("listMsg").textContent = moreKeys ? "Showing the newest " + items.length + " keys." : "";
      render();
    }).catch(function () { $("listMsg").textContent = "Network error."; });
  }

  function renderStats() {
    var counts = { Active: 0, Unused: 0, Expired: 0, Suspended: 0, Revoked: 0 };
    items.forEach(function (it) { counts[status(it)]++; });
    var box = $("stats"); box.textContent = "";
    var all = el("button", "stat", ""); all.type = "button";
    all.appendChild(el("b", "", String(items.length) + (moreKeys ? "+" : ""))); all.appendChild(el("span", "", "Total"));
    all.setAttribute("data-tab", "keys"); all.setAttribute("data-filter", "");
    box.appendChild(all);
    STATUSES.forEach(function (s) {
      var c = el("button", "stat s-" + s, ""); c.type = "button";
      c.appendChild(el("b", "", String(counts[s]))); c.appendChild(el("span", "", s));
      c.setAttribute("data-tab", "keys"); c.setAttribute("data-filter", s);
      box.appendChild(c);
    });
    Array.prototype.forEach.call(box.querySelectorAll("[data-tab]"), function (b) {
      b.addEventListener("click", function () { $("filter").value = b.getAttribute("data-filter"); render(); setTab("keys"); });
    });
  }

  function updateSel() {
    var n = 0; items.forEach(function (it) { if (selected[it.key]) n++; });
    $("selCount").textContent = n + " selected";
    $("btnPrint").disabled = n === 0;
    $("selAll").checked = lastShown.length > 0 && lastShown.every(function (it) { return selected[it.key]; });
  }

  function pushAudit(action, key, detail) {
    audit.unshift({ t: Date.now(), a: action, k: maskKey(key), d: detail || "" });
    renderAudit(); renderRecent();
  }

  function renewForm(it, st) {
    var rf = el("div", "renew");
    rf.appendChild(el("div", "small", !it.activated_at ? "Unused key: the extra time is added to the plan length when it is first activated."
      : (st === "Expired" ? "Expired key: renewed from today." : "Active key: added to the current expiry date.")));
    var pr = el("div", "btns mt2");
    var amt = el("input"); amt.type = "number"; amt.min = "1"; amt.step = "1"; amt.value = "1"; amt.inputMode = "numeric";
    var unit = el("select");
    [["30", "month(s)"], ["1", "day(s)"], ["365", "year(s)"]].forEach(function (u) { var o = el("option", "", u[1]); o.value = u[0]; unit.appendChild(o); });
    [["+1 month", 1, "30"], ["+3 months", 3, "30"], ["+1 year", 1, "365"]].forEach(function (p) {
      pr.appendChild(btn(p[0], "sec sm", function () { amt.value = String(p[1]); unit.value = p[2]; }));
    });
    rf.appendChild(pr);
    var fr = el("div", "row mt");
    var c1 = el("div", "f"); c1.appendChild(amt);
    var c2 = el("div", "f"); c2.appendChild(unit);
    fr.appendChild(c1); fr.appendChild(c2);
    fr.appendChild(btn("Apply", "", function () {
      var n = Number(amt.value), days = n * Number(unit.value);
      if (!Number.isInteger(n) || n < 1 || days > 3650) { $("listMsg").textContent = "Enter a whole number (max 10 years in total)."; return; }
      var base = !it.activated_at ? 0 : Math.max(Date.now() / 1000, it.expires_at || 0);
      var when = it.activated_at ? " New expiry: " + fmt(base + days * 86400) + "." : "";
      if (!window.confirm("Extend " + it.key + " by " + days + " day(s)?" + when)) return;
      api("/admin/renew", { key: it.key, days: days }).then(function (r) {
        if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error === "lifetime" ? "Lifetime keys cannot be extended." : (r.data.error || r.status)); return; }
        it.expires_at = r.data.expires_at; it.extra_days = r.data.extra_days; it.renewals = r.data.renewals;
        renewOpen = null; pushAudit("renew", it.key, "+" + days + " d"); render();
        $("listMsg").textContent = "Renewed. The phone picks it up within about 15 minutes (immediately when its Premium screen is opened).";
      });
    }));
    fr.appendChild(btn("Cancel", "sec", function () { renewOpen = null; render(); }));
    rf.appendChild(fr);
    return rf;
  }

  function keyRow(it) {
    var st = status(it);
    var d = el("div", "krow");
    var head = el("div", "khead");
    var cb = el("input"); cb.type = "checkbox"; cb.checked = !!selected[it.key];
    cb.addEventListener("change", function () { if (cb.checked) selected[it.key] = true; else delete selected[it.key]; updateSel(); });
    head.appendChild(cb);
    head.appendChild(el("code", "key", it.key));
    head.appendChild(el("span", "badge b-" + st, st));
    d.appendChild(head);

    var parts = [planLabel(it)];
    if (it.created_at) parts.push("created " + fmt(it.created_at));
    if (!it.activated_at && it.extra_days) parts.push("+" + it.extra_days + "d bonus");
    if (it.renewals) parts.push("renewed x" + it.renewals);
    if (it.activated_at) { parts.push("activated " + fmt(it.activated_at)); parts.push(it.expires_at ? "expires " + fmt(it.expires_at) : "no expiry"); }
    parts.push(it.device ? "device " + String(it.device).slice(0, 6) + "..." : (it.activated_at ? "unbound" : "not bound"));
    if (it.note) parts.push("note: " + it.note);
    d.appendChild(el("div", "kmeta", parts.join("  |  ")));

    var acts = el("div", "acts");
    acts.appendChild(btn("Copy", "sec", function (e) { copy(it.key, e.currentTarget); }));
    acts.appendChild(btn("Renew / Extend", "gold", function () { renewOpen = renewOpen === it.key ? null : it.key; render(); }));
    if (!it.revoked) {
      if (it.suspended) {
        acts.appendChild(btn("Resume", "", function () {
          if (!window.confirm("Resume " + it.key + "? Premium comes back on the phone at its next check.")) return;
          api("/admin/resume", { key: it.key }).then(function (r) {
            if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
            it.suspended = false; pushAudit("resume", it.key, it.plan); render();
          });
        }));
      } else if (st === "Active" || st === "Unused") {
        acts.appendChild(btn("Suspend", "warn", function () {
          if (!window.confirm("Suspend " + it.key + "?\\n\\nThe phone loses Premium at its next check (about 15 minutes, immediately when its Premium screen is opened) but keeps the key. The expiry date does NOT pause. You can resume it at any time.")) return;
          api("/admin/suspend", { key: it.key }).then(function (r) {
            if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
            it.suspended = true; pushAudit("suspend", it.key, it.plan); render();
          });
        }));
      }
    }
    acts.appendChild(btn("Unbind", "sec", function () {
      if (!window.confirm("Release " + it.key + " from its phone? That phone loses Premium at its next check and the next phone to activate the key binds it (the expiry date is kept).")) return;
      api("/admin/unbind", { key: it.key }).then(function (r) {
        if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
        it.device = null; pushAudit("unbind", it.key, "device released"); render();
      });
    }));
    if (it.revoked) {
      acts.appendChild(btn("Restore", "", function () {
        api("/admin/restore", { key: it.key }).then(function (r) { if (r.status === 200) { it.revoked = false; pushAudit("restore", it.key, it.plan); render(); } });
      }));
    } else {
      acts.appendChild(btn("Revoke", "dan", function () {
        if (!window.confirm("Revoke " + it.key + "? The phone loses Premium and the key is cleared from it. (To pause it temporarily use Suspend.)")) return;
        api("/admin/revoke", { key: it.key }).then(function (r) { if (r.status === 200) { it.revoked = true; pushAudit("revoke", it.key, it.plan); render(); } });
      }));
    }
    acts.appendChild(btn("Delete", "dan", function () {
      var warn = st === "Active" ? "\\n\\nThis key is ACTIVE on a phone." : "";
      if (!window.confirm("Permanently delete " + it.key + "? This cannot be undone." + warn)) return;
      api("/admin/delete", { key: it.key }).then(function (r) {
        if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
        items = items.filter(function (x) { return x.key !== it.key; }); delete selected[it.key];
        pushAudit("delete", it.key, it.plan); render();
      });
    }));
    d.appendChild(acts);
    if (renewOpen === it.key) d.appendChild(renewForm(it, st));
    return d;
  }

  function render() {
    renderStats();
    var q = $("search").value.trim().toLowerCase(), f = $("filter").value;
    lastShown = items.filter(function (it) {
      if (f && status(it) !== f) return false;
      if (q && it.key.toLowerCase().indexOf(q) < 0 && String(it.note || "").toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    var box = $("klist"); box.textContent = "";
    if (!lastShown.length) box.appendChild(el("div", "card small", items.length ? "No keys match this filter." : "No keys yet. Generate some in the Generate tab."));
    lastShown.forEach(function (it) { box.appendChild(keyRow(it)); });
    updateSel();
  }

  /* ---------- generate ---------- */
  $("plan").addEventListener("change", function () { $("trialBox").hidden = $("plan").value !== "trial"; });
  Array.prototype.forEach.call(document.querySelectorAll("[data-days]"), function (b) {
    b.addEventListener("click", function () { $("trialDays").value = b.getAttribute("data-days"); });
  });
  $("btnGen").addEventListener("click", function () {
    var plan = $("plan").value, count = parseInt($("count").value, 10) || 1;
    var body = { plan: plan, count: count, note: $("note").value.trim() };
    if (plan === "trial") {
      var td = Number($("trialDays").value);
      if (!Number.isInteger(td) || td < 1 || td > 14) { $("genMsg").textContent = "Trial length must be a whole number from 1 to 14 days."; return; }
      body.days = td;
    }
    $("genMsg").textContent = "Generating...";
    $("btnGen").disabled = true;
    api("/admin/generate", body).then(function (r) {
      $("btnGen").disabled = false;
      if (r.status !== 200) { $("genMsg").textContent = "Error: " + (r.data.error || r.status); return; }
      lastGen = (r.data.keys || []).map(function (k) { return { key: k, plan: r.data.plan, days: r.data.days }; });
      $("genMsg").textContent = lastGen.length + " key(s) generated.";
      var list = $("genList"); list.textContent = "";
      lastGen.forEach(function (g) {
        var row = el("div", "newkey"); row.appendChild(el("code", "", g.key));
        row.appendChild(btn("Copy", "sec sm", function (e) { copy(g.key, e.currentTarget); }));
        list.appendChild(row);
      });
      $("genOut").hidden = false;
      if (lastGen.length) pushAudit("generate", lastGen[0].key, lastGen.length + " x " + plan);
      loadKeys();
    }).catch(function () { $("btnGen").disabled = false; $("genMsg").textContent = "Network error."; });
  });
  $("btnCopyAll").addEventListener("click", function (e) { if (lastGen) copy(lastGen.map(function (g) { return g.key; }).join("\\n"), e.currentTarget); });
  $("btnPrintGen").addEventListener("click", function () { if (lastGen) printItems(lastGen); });

  /* ---------- keys toolbar ---------- */
  $("search").addEventListener("input", render);
  $("filter").addEventListener("change", render);
  $("btnRefresh").addEventListener("click", loadKeys);
  $("selAll").addEventListener("change", function () {
    lastShown.forEach(function (it) { if ($("selAll").checked) selected[it.key] = true; else delete selected[it.key]; });
    render();
  });
  $("btnPrint").addEventListener("click", function () {
    var arr = items.filter(function (it) { return selected[it.key]; });
    var bad = arr.filter(function (it) { return status(it) !== "Unused"; }).length;
    if (bad && !window.confirm(bad + " selected key(s) are not Unused (already used, expired, suspended or revoked). Print anyway?")) return;
    printItems(arr.map(function (it) { return { key: it.key, plan: it.plan, days: it.days }; }));
  });
  $("btnPurge").addEventListener("click", function () {
    var n = items.filter(function (it) { return it.revoked; }).length;
    if (!n) { $("listMsg").textContent = "No revoked keys."; return; }
    if (!window.confirm("Permanently delete all " + n + " revoked key(s)?")) return;
    var total = 0, loops = 0;
    $("listMsg").textContent = "Deleting...";
    function step() {
      return api("/admin/purge").then(function (r) {
        if (r.status !== 200) { $("listMsg").textContent = "Error: " + (r.data.error || r.status); return; }
        total += r.data.deleted; loops++;
        if (r.data.deleted > 0 && r.data.remaining > 0 && loops < 25) return step();
      });
    }
    step().then(function () { $("listMsg").textContent = total + " revoked key(s) deleted."; auditLoaded = false; loadKeys(); });
  });

  /* ---------- activity ---------- */
  function eventRow(e) {
    var d = el("div", "ev");
    d.appendChild(el("div", "evt", fmtTime(e.t)));
    var h = el("div", "evh");
    h.appendChild(el("span", "evb e-" + e.a, EVENT[e.a] || e.a));
    if (e.k) h.appendChild(el("span", "evk", e.k));
    d.appendChild(h);
    if (e.d) d.appendChild(el("div", "evd", e.d));
    return d;
  }
  function renderAudit() {
    var box = $("auditList"); box.textContent = "";
    if (!audit.length) box.appendChild(el("div", "small", auditLoaded ? "No admin activity recorded yet." : "Loading..."));
    audit.forEach(function (e) { box.appendChild(eventRow(e)); });
    $("btnAuditMore").hidden = !auditCursor;
  }
  function renderRecent() {
    var box = $("recent"); box.textContent = "";
    if (!audit.length) { box.textContent = auditLoaded ? "No admin activity recorded yet." : "Loading..."; return; }
    audit.slice(0, 5).forEach(function (e) { box.appendChild(eventRow(e)); });
  }
  function loadAudit(reset) {
    $("auditMsg").textContent = "";
    return api("/admin/audit", { limit: 50, cursor: reset ? undefined : auditCursor }).then(function (r) {
      if (r.status === 401) { logout("Session expired. Sign in again."); return; }
      if (r.status !== 200) { $("auditMsg").textContent = "Error " + r.status; return; }
      audit = reset ? (r.data.items || []) : audit.concat(r.data.items || []);
      auditCursor = r.data.cursor || null; auditLoaded = true;
      renderAudit(); renderRecent();
    }).catch(function () { $("auditMsg").textContent = "Network error."; });
  }
  $("btnAuditRefresh").addEventListener("click", function () { loadAudit(true); });
  $("btnAuditMore").addEventListener("click", function () { loadAudit(false); });

  /* ---------- feedback ---------- */
  var fbItems = [], fbCursor = null, fbLoaded = false, FB_SEEN = "tt_fb_seen";
  function stars(n) { var s = ""; for (var i = 1; i <= 5; i++) s += i <= n ? "★" : "☆"; return s; }
  function seenTime() { try { return Number(localStorage.getItem(FB_SEEN) || 0); } catch (e) { return 0; } }
  function updateFbBadge() {
    var seen = seenTime(), n = fbItems.filter(function (x) { return x.t > seen; }).length, b = $("fbBadge");
    b.hidden = n === 0; b.textContent = n > 9 ? "9+" : String(n);
  }
  function markFbSeen() {
    if (fbItems.length) { try { localStorage.setItem(FB_SEEN, String(Math.max.apply(null, fbItems.map(function (x) { return x.t; })))); } catch (e) {} }
    var b = $("fbBadge"); b.hidden = true;
  }
  function feedbackRow(it) {
    var d = el("div", "fb");
    var head = el("div", "fbhead");
    head.appendChild(el("span", "stars", it.r ? stars(it.r) : "no rating"));
    head.appendChild(el("span", "fbwho", [it.n || "Anonymous", it.c || ""].filter(Boolean).join("  |  ")));
    head.appendChild(el("span", "fbtime", fmtTime(it.t)));
    d.appendChild(head);
    var msg = el("div", "fbmsg", it.p || "");
    d.appendChild(msg);
    var acts = el("div", "btns mt2");
    var read = btn("Read full message", "sec sm", function () {
      read.disabled = true;
      api("/admin/feedback_get", { id: it.id }).then(function (r) {
        read.disabled = false;
        if (r.status !== 200) { $("fbMsg").textContent = "Could not load it (" + (r.data.error || r.status) + ")."; return; }
        msg.textContent = r.data.message;
        head.querySelector(".fbwho").textContent = [r.data.name || "Anonymous", r.data.contact || ""].filter(Boolean).join("  |  ");
        read.hidden = true;
      });
    });
    acts.appendChild(read);
    acts.appendChild(btn("Delete", "dan sm", function () {
      if (!window.confirm("Delete this feedback permanently?")) return;
      api("/admin/feedback_delete", { id: it.id }).then(function (r) {
        if (r.status !== 200) { $("fbMsg").textContent = "Error: " + (r.data.error || r.status); return; }
        fbItems = fbItems.filter(function (x) { return x.id !== it.id; });
        pushAudit("feedback_delete", "", "one visitor feedback entry removed");
        renderFeedback(); updateFbBadge();
      });
    }));
    d.appendChild(acts);
    return d;
  }
  function renderFeedback() {
    var box = $("fbList"); box.textContent = "";
    if (!fbItems.length) box.appendChild(el("div", "small mt", fbLoaded ? "No feedback yet." : "Loading..."));
    fbItems.forEach(function (it) { box.appendChild(feedbackRow(it)); });
    $("btnFbMore").hidden = !fbCursor;
  }
  function loadFeedback(reset) {
    $("fbMsg").textContent = "";
    return api("/admin/feedback_list", { limit: 50, cursor: reset ? undefined : fbCursor }).then(function (r) {
      if (r.status === 401) { logout("Session expired. Sign in again."); return; }
      if (r.status !== 200) { $("fbMsg").textContent = "Error " + r.status; return; }
      fbItems = reset ? (r.data.items || []) : fbItems.concat(r.data.items || []);
      fbCursor = r.data.cursor || null; fbLoaded = true;
      renderFeedback(); updateFbBadge();
    }).catch(function () { $("fbMsg").textContent = "Network error."; });
  }
  $("btnFbRefresh").addEventListener("click", function () { loadFeedback(true).then(markFbSeen); });
  $("btnFbMore").addEventListener("click", function () { loadFeedback(false); });

  /* ---------- system ---------- */
  function loadMin() {
    api("/admin/minversion").then(function (r) {
      if (r.status === 200) { $("minv").value = String(r.data.min_version_code); $("minMsg").textContent = "Current minimum: " + r.data.min_version_code + (r.data.min_version_code ? "" : " (none)"); }
    });
  }
  $("btnMinSave").addEventListener("click", function () {
    var raw = $("minv").value.trim(), v = Number(raw);
    if (raw === "" || !Number.isInteger(v) || v < 0) { $("minMsg").textContent = "Enter a whole number (0 = no minimum)."; return; }
    if (v > 0 && !window.confirm("Block every app build older than versionCode " + v + " until the user updates? Make sure that version is already released.")) return;
    api("/admin/minversion", { value: v }).then(function (r) {
      $("minMsg").textContent = r.status === 200 ? "Saved. Minimum is now " + r.data.min_version_code + "." : "Error: " + (r.data.error || r.status);
      if (r.status === 200) pushAudit("force_update", "", "minimum versionCode -> " + v);
    });
  });
  $("btnDiag").addEventListener("click", function () {
    var box = $("diag"); box.hidden = false; box.textContent = "Checking...";
    api("/admin/diagnostics").then(function (r) {
      if (r.status !== 200) { box.textContent = "Diagnostics failed: " + (r.data.error || r.status); return; }
      var d = r.data, L = [];
      function line(ok, text) { L.push((ok === null ? "INFO " : ok ? "OK   " : "FAIL ") + text); }
      line(null, "Checked " + d.time);
      line(d.admin.token_ok, "Admin token " + (d.admin.token_ok ? "is long enough" : "is SHORT: set a new 64-character ADMIN_TOKEN"));
      if (d.kv.ok) {
        var b = d.kv.by_status;
        line(true, "KV database: " + d.kv.total + " key(s)" + (d.kv.truncated ? " (first 5000 counted)" : "") + " - unused " + b.unused + ", active " + b.active + ", suspended " + (b.suspended || 0) + ", expired " + b.expired + ", revoked " + b.revoked);
        line(null, "Force Update minimum versionCode: " + d.min_version_code + (d.min_version_code ? "" : " (none)"));
      } else line(false, "KV database unreachable");
      var p = d.public;
      if (!p.bound) line(false, "Public Worker not linked (service binding PUBLIC missing, redeploy the admin Worker)");
      else {
        line(p.version.ok, "Public license Worker /version: " + (p.version.ok ? "answering, latest app " + (p.version.latest_version_name || "unknown") + " (code " + p.version.latest_version_code + ")" : "no answer"));
        line(!!(p.version.ok && p.version.key_fp), "Signing key fingerprint: " + (p.version.key_fp || "MISSING (SIGNING_KEY secret not working)"));
        line(p.site.ok, "Public website: HTTP " + p.site.status);
        line(p.download.ok, "APK download: " + (p.download.ok ? "reachable" + (p.download.bytes ? ", " + Math.round(p.download.bytes / 1048576 * 10) / 10 + " MB" : "") : "NOT reachable (HTTP " + p.download.status + ")"));
        line(p.admin_hidden.ok, "Admin hidden from public site: " + (p.admin_hidden.ok ? "yes (404)" : "NO, HTTP " + p.admin_hidden.status));
        line(p.api.ok, "License API answers: HTTP " + p.api.status);
      }
      L.push("Fingerprint check: compare the key fingerprint with the app's PUBLIC_KEY_B64 (see STATE.md).");
      box.textContent = L.join("\\n");
    });
  });

  /* ---------- session ---------- */
  function showApp(on) {
    $("login").hidden = on; $("app").hidden = !on;
    if (on) { measureTop(); setTab(location.hash.slice(1), false); }
  }
  function logout(msg) {
    token = ""; sessionStorage.removeItem("tt_admin"); items = []; audit = []; auditLoaded = false; selected = {}; fbItems = []; fbLoaded = false;
    showApp(false); $("loginMsg").textContent = msg || "";
  }
  function login() {
    token = $("tok").value.trim() || token;
    $("loginMsg").textContent = "";
    api("/admin/ping").then(function (r) {
      if (r.status === 200) { sessionStorage.setItem("tt_admin", token); $("tok").value = ""; start(); }
      else if (r.status === 429 || (r.data && r.data.error === "too_many_attempts")) $("loginMsg").textContent = "Too many attempts. Wait 15 minutes.";
      else { token = ""; sessionStorage.removeItem("tt_admin"); $("loginMsg").textContent = "Wrong token."; }
    }).catch(function () { $("loginMsg").textContent = "Network error."; });
  }
  function start() { showApp(true); loadKeys(); loadAudit(true); loadMin(); loadFeedback(true); }
  $("btnLogin").addEventListener("click", login);
  $("tok").addEventListener("keydown", function (e) { if (e.key === "Enter") login(); });
  $("btnOut").addEventListener("click", function () { logout(""); });
  window.addEventListener("hashchange", function () { if (!$("app").hidden) setTab(location.hash.slice(1), false); });
  window.addEventListener("afterprint", function () { $("printArea").textContent = ""; });

  if (token) { api("/admin/ping").then(function (r) { if (r.status === 200) start(); else logout(""); }).catch(function () { showApp(false); }); }
})();
</script>
</body></html>
`;

// ---------------------------------------------------------------- router

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, "") || "/";

    if ((req.method === "GET" || req.method === "HEAD") && path === "/") return dashboard();
    if (req.method !== "POST") return json({ error: "not_found" }, 404);

    let body;
    try { body = await req.json(); } catch { body = null; }

    try {
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
