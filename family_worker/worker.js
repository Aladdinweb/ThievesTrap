// Thieves Trap FAMILY Worker (v3.0.0 step 1: pairing).
// Separate from the license Worker. No secrets, no KV: everything lives in Durable Objects.
//
// What the server can see: device ids (hash of each phone's public key), display names (max 24
// chars) and ciphertext it cannot read. It never sees the pair key: both phones derive it with
// ECDH from ephemeral keys, and compare a short verification code (SAS) on screen, so a
// relaying server cannot swap keys unnoticed.
//
// Every request is signed by the phone's own Keystore key:
//   headers  X-TT-Pub (SPKI b64), X-TT-Ts (unix s), X-TT-Nonce, X-TT-Sig (raw r||s, b64)
//   message  TF1|<METHOD>|<path>|<ts>|<nonce>|<sha256 hex of the body text>

const MAX_BODY = 4096;
const TS_WINDOW = 300;            // seconds
const CODE_TTL_MS = 10 * 60 * 1000;
const DONE_TTL_MS = 2 * 60 * 1000;
const MAX_JOIN_TRIES = 3;         // per code

const enc = new TextEncoder();
const b64d = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const sha256hex = async (data) => hex(await crypto.subtle.digest('SHA-256', typeof data === 'string' ? enc.encode(data) : data));
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
const err = (code, status = 400) => json({ ok: false, error: code }, status);

function cleanName(v) {
  return String(v || '').replace(/[\u0000-\u001f\u007f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '').trim().slice(0, 24) || 'Phone';
}
function okB64(s, max) {
  return typeof s === 'string' && s.length > 20 && s.length <= max && /^[A-Za-z0-9+/]+={0,2}$/.test(s);
}
const CODE_RE = /^[0-9]{4}$/;
const PID_RE = /^[0-9a-f]{32}$/;

async function authenticate(request, path, bodyText) {
  const pub = request.headers.get('x-tt-pub') || '';
  const ts = parseInt(request.headers.get('x-tt-ts') || '', 10);
  const nonce = request.headers.get('x-tt-nonce') || '';
  const sig = request.headers.get('x-tt-sig') || '';
  if (!okB64(pub, 200) || !okB64(sig, 120) || !/^[A-Za-z0-9_-]{8,40}$/.test(nonce) || !Number.isFinite(ts)) return null;
  if (Math.abs(Date.now() / 1000 - ts) > TS_WINDOW) return { error: 'bad_time' };
  try {
    const spki = b64d(pub);
    const key = await crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const msg = `TF1|${request.method}|${path}|${ts}|${nonce}|${await sha256hex(bodyText)}`;
    const ok = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, b64d(sig), enc.encode(msg));
    if (!ok) return null;
    return { did: (await sha256hex(spki)).slice(0, 32), pub };
  } catch (e) {
    return null;
  }
}

async function rateLimit(env, name, max, windowSec) {
  const stub = env.LIMITER.get(env.LIMITER.idFromName(name));
  const r = await stub.fetch('https://do/hit', { method: 'POST', body: JSON.stringify({ max, windowSec }) });
  return (await r.json()).ok === true;
}
const callDO = async (ns, name, path, payload) => {
  const stub = ns.get(ns.idFromName(name));
  const r = await stub.fetch('https://do' + path, { method: 'POST', body: JSON.stringify(payload) });
  return r.json();
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (request.method === 'GET' && path === '/') return json({ ok: true, service: 'tt-family', v: 1 });
    if (request.method !== 'POST') return err('not_found', 404);
    const routes = ['/pair/open', '/pair/join', '/pair/poll', '/pair/decide', '/pair/info', '/pair/unpair'];
    if (!routes.includes(path)) return err('not_found', 404);

    const len = parseInt(request.headers.get('content-length') || '0', 10);
    if (len > MAX_BODY) return err('too_large', 413);
    const bodyText = await request.text();
    if (bodyText.length > MAX_BODY) return err('too_large', 413);
    let body;
    try { body = bodyText ? JSON.parse(bodyText) : {}; } catch (e) { return err('bad_request'); }
    if (typeof body !== 'object' || body === null) return err('bad_request');

    const who = await authenticate(request, path, bodyText);
    if (!who) return err('bad_signature', 401);
    if (who.error) return err(who.error, 400);

    const ip = await sha256hex(request.headers.get('cf-connecting-ip') || 'unknown');
    const ipKey = ip.slice(0, 16);

    if (path === '/pair/open') {
      if (!(await rateLimit(env, 'ip-open:' + ipKey, 20, 600))) return err('rate_limited', 429);
      if (!(await rateLimit(env, 'dev-open:' + who.did, 6, 600))) return err('rate_limited', 429);
      if (!okB64(body.epk, 200)) return err('bad_request');
      for (let i = 0; i < 8; i++) {
        const n = crypto.getRandomValues(new Uint32Array(1))[0] % 10000;
        const code = String(n).padStart(4, '0');
        const r = await callDO(env.RENDEZVOUS, code, '/init', {
          did: who.did, pub: who.pub, epk: body.epk, name: cleanName(body.name),
        });
        if (r.ok) return json({ ok: true, code, expires_in: CODE_TTL_MS / 1000 });
      }
      return err('busy', 503);
    }

    if (path === '/pair/join') {
      if (!(await rateLimit(env, 'ip-join:' + ipKey, 20, 600))) return err('rate_limited', 429);
      if (!(await rateLimit(env, 'dev-join:' + who.did, 6, 600))) return err('rate_limited', 429);
      const code = String(body.code || '').replace(/^TRAP-/i, '');
      if (!CODE_RE.test(code) || !okB64(body.epk, 200)) return err('bad_request');
      const r = await callDO(env.RENDEZVOUS, code, '/join', {
        did: who.did, pub: who.pub, epk: body.epk, name: cleanName(body.name),
      });
      return json(r, r.ok ? 200 : 400);
    }

    if (path === '/pair/poll' || path === '/pair/decide') {
      const code = String(body.code || '').replace(/^TRAP-/i, '');
      if (!CODE_RE.test(code)) return err('bad_request');
      const op = path === '/pair/poll' ? '/poll' : '/decide';
      const r = await callDO(env.RENDEZVOUS, code, op, { did: who.did, accept: body.accept === true, rooms: undefined });
      if (op === '/decide' && r.ok && r.accepted) {
        // create the room, then record the pid in the rendezvous
        const pid = hex(crypto.getRandomValues(new Uint8Array(16)));
        const made = await callDO(env.ROOMS, pid, '/create', { members: r.members });
        if (!made.ok) return err('busy', 503);
        await callDO(env.RENDEZVOUS, code, '/finish', { did: who.did, pid });
        return json({ ok: true, status: 'approved', pid });
      }
      return json(r, r.ok ? 200 : 400);
    }

    // /pair/info and /pair/unpair
    if (!PID_RE.test(String(body.pid || ''))) return err('bad_request');
    if (!(await rateLimit(env, 'dev-room:' + who.did, 120, 600))) return err('rate_limited', 429);
    const r = await callDO(env.ROOMS, body.pid, path === '/pair/info' ? '/info' : '/unpair', { did: who.did });
    return json(r, r.ok ? 200 : 404);
  },
};

// ---------------------------------------------------------------- Durable Objects

// One per live pairing code (named by the 4 digits). Self-destructs 10 minutes after creation.
export class Rendezvous {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    const b = await request.json();
    const st = (await this.ctx.storage.get('s')) || null;
    const now = Date.now();
    const live = st && now - st.created < CODE_TTL_MS && !st.dead;
    const save = (s) => this.ctx.storage.put('s', s);
    const out = (o) => new Response(JSON.stringify(o));

    if (path === '/init') {
      if (live) return out({ ok: false, error: 'taken' });
      await this.ctx.storage.deleteAll();
      await save({
        created: now, status: 'open', attempts: 0,
        owner: { did: b.did, pub: b.pub, epk: b.epk, name: b.name }, joiner: null, pid: null,
      });
      await this.ctx.storage.setAlarm(now + CODE_TTL_MS);
      return out({ ok: true });
    }
    if (!live) return out({ ok: false, error: 'invalid_code' });

    if (path === '/join') {
      if (b.did === st.owner.did) return out({ ok: false, error: 'self' });
      if (st.status !== 'open') return out({ ok: false, error: 'busy' });
      st.attempts += 1;
      if (st.attempts > MAX_JOIN_TRIES) { st.dead = true; await save(st); return out({ ok: false, error: 'invalid_code' }); }
      st.joiner = { did: b.did, pub: b.pub, epk: b.epk, name: b.name };
      st.status = 'pending';
      await save(st);
      return out({ ok: true, status: 'pending', peer: { name: st.owner.name, epk: st.owner.epk } });
    }

    const isOwner = b.did === st.owner.did;
    const isJoiner = st.joiner && b.did === st.joiner.did;
    if (!isOwner && !isJoiner) return out({ ok: false, error: 'invalid_code' });

    if (path === '/poll') {
      const peer = isOwner ? st.joiner : st.owner;
      return out({
        ok: true, status: st.status, owner: isOwner,
        peer: peer ? { name: peer.name, epk: peer.epk } : null,
        pid: st.status === 'approved' ? st.pid : undefined,
      });
    }
    if (path === '/decide') {
      if (!isOwner || st.status !== 'pending') return out({ ok: false, error: 'bad_state' });
      if (!b.accept) {
        st.status = 'denied';
        await save(st);
        await this.ctx.storage.setAlarm(now + DONE_TTL_MS);
        return out({ ok: true, status: 'denied' });
      }
      return out({
        ok: true, accepted: true,
        members: [
          { did: st.owner.did, pub: st.owner.pub, name: st.owner.name },
          { did: st.joiner.did, pub: st.joiner.pub, name: st.joiner.name },
        ],
      });
    }
    if (path === '/finish') {
      if (!isOwner || st.status !== 'pending') return out({ ok: false, error: 'bad_state' });
      st.status = 'approved'; st.pid = b.pid;
      await save(st);
      await this.ctx.storage.setAlarm(now + DONE_TTL_MS);
      return out({ ok: true });
    }
    return out({ ok: false, error: 'not_found' });
  }

  async alarm() { await this.ctx.storage.deleteAll(); }
}

// One per pair (named by a random 128-bit id). Holds only the two members.
export class PairRoom {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    const b = await request.json();
    const out = (o) => new Response(JSON.stringify(o));
    if (path === '/create') {
      if (await this.ctx.storage.get('m')) return out({ ok: false, error: 'exists' });
      await this.ctx.storage.put('m', { members: b.members, created: Date.now() });
      return out({ ok: true });
    }
    const m = await this.ctx.storage.get('m');
    if (!m || !m.members.some((x) => x.did === b.did)) return out({ ok: false, error: 'not_found' });
    if (path === '/info') {
      return out({ ok: true, members: m.members.map((x) => ({ did: x.did, name: x.name })), created: m.created });
    }
    if (path === '/unpair') {
      await this.ctx.storage.deleteAll();
      return out({ ok: true });
    }
    return out({ ok: false, error: 'not_found' });
  }
}

// Fixed-window counter per key (ip hash or device id).
export class Limiter {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }

  async fetch(request) {
    const { max, windowSec } = await request.json();
    const now = Date.now();
    let s = (await this.ctx.storage.get('w')) || { start: now, n: 0 };
    if (now - s.start > windowSec * 1000) s = { start: now, n: 0 };
    s.n += 1;
    await this.ctx.storage.put('w', s);
    await this.ctx.storage.setAlarm(s.start + windowSec * 1000 + 1000);
    return new Response(JSON.stringify({ ok: s.n <= max }));
  }

  async alarm() { await this.ctx.storage.deleteAll(); }
}
