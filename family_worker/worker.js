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
const MAX_PHOTO_BODY = 700000;      // base64 ciphertext of a photo of at most ~450 KB
const CMD_TTL_MS = 5 * 60 * 1000;   // an undelivered command is dropped after 5 minutes
const PHOTO_TTL_MS = 24 * 60 * 60 * 1000;   // a photo nobody picked up is deleted after 24 hours
const MAX_PENDING_PHOTOS = 5;       // per recipient
const MAX_MSGS = 10;
const LOC_TTL_MS = 2 * 60 * 1000;    // the single latest location slot; an old fix is never served
const CHUNK = 100000;               // characters per stored piece (DO values stay far below the limit)
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

    // v2.9.19: live channel. A phone keeps one WebSocket per pair so commands, locations and photo
    // notices arrive at once instead of at the next poll. Signed like every request (empty body).
    if (request.method === 'GET' && path === '/ws') {
      if (request.headers.get('upgrade') !== 'websocket') return err('bad_request');
      const pid = url.searchParams.get('pid') || '';
      if (!PID_RE.test(pid)) return err('bad_request');
      const who = await authenticate(request, '/ws', '');
      if (!who) return err('bad_signature', 401);
      if (who.error) return err(who.error, 400);
      if (!(await rateLimit(env, 'dev-ws:' + who.did, 60, 600))) return err('rate_limited', 429);
      const stub = env.ROOMS.get(env.ROOMS.idFromName(pid));
      return stub.fetch('https://do/ws', { headers: { upgrade: 'websocket', 'x-did': who.did } });
    }
    if (request.method !== 'POST') return err('not_found', 404);
    const routes = ['/pair/open', '/pair/join', '/pair/poll', '/pair/decide', '/pair/info', '/pair/unpair'];
    const roomOps = { '/room/send': '/send', '/room/poll': '/poll', '/photo/put': '/photo/put', '/photo/get': '/photo/get', '/photo/ack': '/photo/ack', '/loc/put': '/loc/put', '/loc/get': '/loc/get', '/loc/clear': '/loc/clear' };
    if (!routes.includes(path) && !roomOps[path]) return err('not_found', 404);

    const limit = path === '/photo/put' ? MAX_PHOTO_BODY : MAX_BODY;
    const len = parseInt(request.headers.get('content-length') || '0', 10);
    if (len > limit) return err('too_large', 413);
    const bodyText = await request.text();
    if (bodyText.length > limit) return err('too_large', 413);
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

    // ---- v2.9.17: commands and photos between the two members of a pair
    if (roomOps[path]) {
      if (!PID_RE.test(String(body.pid || ''))) return err('bad_request');
      const payload = { did: who.did };
      if (path === '/room/send') {
        if (!okB64(body.ct, 2000)) return err('bad_request');
        if (!(await rateLimit(env, 'dev-send:' + who.did, 60, 600))) return err('rate_limited', 429);
        payload.ct = body.ct;
      } else if (path === '/photo/put') {
        if (!/^[0-9a-f]{16}$/.test(String(body.id || '')) || !okB64(body.ct, MAX_PHOTO_BODY - 200)) return err('bad_request');
        if (!(await rateLimit(env, 'dev-photo:' + who.did, 30, 600))) return err('rate_limited', 429);
        payload.id = body.id; payload.ct = body.ct;
      } else if (path === '/loc/put') {
        if (!okB64(body.ct, 2000)) return err('bad_request');
        payload.ct = body.ct;
      } else if (path === '/photo/get' || path === '/photo/ack') {
        if (!/^[0-9a-f]{16}$/.test(String(body.id || ''))) return err('bad_request');
        payload.id = body.id;
      }
      const r = await callDO(env.ROOMS, body.pid, roomOps[path], payload);
      return json(r, r.ok ? 200 : (r.error === 'not_found' ? 404 : 400));
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

// One per pair (named by a random 128-bit id). Holds the two members, plus short-lived relay data:
//   i:<did>        commands waiting for that member (delivered once, dropped after CMD_TTL)
//   pm:<id>        metadata of an encrypted photo waiting for its recipient
//   pc:<id>:<n>    the ciphertext pieces of that photo
// Photos are deleted the moment the recipient acknowledges them, or after PHOTO_TTL at the latest.
// Everything stored here is ciphertext the server cannot read.
export class PairRoom {
  constructor(ctx, env) {
    this.ctx = ctx; this.env = env;
    // keep-alive pings are answered by the runtime without waking this object
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  // Sends a small JSON notice to every open socket of that member (ciphertext only, never plaintext).
  push(did, obj) {
    const text = JSON.stringify(obj);
    for (const ws of this.ctx.getWebSockets(did)) { try { ws.send(text); } catch (e) { /* closed */ } }
  }

  async webSocketMessage(ws, msg) { /* the phones only send the auto-answered ping */ }
  async webSocketClose(ws, code) { try { ws.close(code && code !== 1005 ? code : 1000, 'bye'); } catch (e) { /* already closed */ } }
  async webSocketError(ws) { try { ws.close(1011, 'error'); } catch (e) { /* already closed */ } }

  cmdTtl() { return Number(this.env.CMD_TTL_MS) || CMD_TTL_MS; }
  photoTtl() { return Number(this.env.PHOTO_TTL_MS) || PHOTO_TTL_MS; }
  locTtl() { return Number(this.env.LOC_TTL_MS) || LOC_TTL_MS; }

  async dropPhoto(id, n) {
    const keys = ['pm:' + id];
    for (let k = 0; k < n; k++) keys.push('pc:' + id + ':' + k);
    await this.ctx.storage.delete(keys);
  }

  // Deletes everything expired and arms the alarm for the next expiry (if anything is left).
  async sweep() {
    const now = Date.now();
    let next = 0;
    const pm = await this.ctx.storage.list({ prefix: 'pm:' });
    for (const [k, v] of pm) {
      if (now - v.ts >= this.photoTtl()) await this.dropPhoto(k.slice(3), v.n);
      else next = next ? Math.min(next, v.ts + this.photoTtl()) : v.ts + this.photoTtl();
    }
    for (const [k, v] of await this.ctx.storage.list({ prefix: 'l:' })) {
      if (now - v.ts >= this.locTtl()) await this.ctx.storage.delete(k);
      else next = next ? Math.min(next, v.ts + this.locTtl()) : v.ts + this.locTtl();
    }
    const inb = await this.ctx.storage.list({ prefix: 'i:' });
    for (const [k, arr] of inb) {
      const fresh = arr.filter((m) => now - m.ts < this.cmdTtl());
      if (fresh.length === 0) await this.ctx.storage.delete(k);
      else {
        if (fresh.length !== arr.length) await this.ctx.storage.put(k, fresh);
        const t = fresh[0].ts + this.cmdTtl();
        next = next ? Math.min(next, t) : t;
      }
    }
    if (next) await this.ctx.storage.setAlarm(Math.max(next, now + 1000));
    else await this.ctx.storage.deleteAlarm();
  }

  async alarm() { await this.sweep(); }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === '/ws') {
      const did = request.headers.get('x-did') || '';
      const mem = await this.ctx.storage.get('m');
      if (!mem || !mem.members.some((x) => x.did === did)) return new Response(JSON.stringify({ ok: false, error: 'not_found' }), { status: 404 });
      for (const old of this.ctx.getWebSockets(did)) { try { old.close(1000, 'replaced'); } catch (e) { /* ignore */ } }
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1], [did]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    const b = await request.json();
    const out = (o) => new Response(JSON.stringify(o));
    if (path === '/create') {
      if (await this.ctx.storage.get('m')) return out({ ok: false, error: 'exists' });
      await this.ctx.storage.put('m', { members: b.members, created: Date.now() });
      return out({ ok: true });
    }
    if (path === '/_count' && this.env.TEST_MODE === '1') return out({ keys: [...(await this.ctx.storage.list()).keys()].length });   // tests only
    const m = await this.ctx.storage.get('m');
    if (!m || !m.members.some((x) => x.did === b.did)) return out({ ok: false, error: 'not_found' });
    const peer = m.members.find((x) => x.did !== b.did);
    const now = Date.now();

    if (path === '/info') {
      return out({ ok: true, members: m.members.map((x) => ({ did: x.did, name: x.name })), created: m.created });
    }
    if (path === '/unpair') {
      await this.ctx.storage.deleteAlarm();
      await this.ctx.storage.deleteAll();
      return out({ ok: true });
    }
    if (path === '/send') {
      const key = 'i:' + peer.did;
      const arr = ((await this.ctx.storage.get(key)) || []).filter((x) => now - x.ts < this.cmdTtl());
      arr.push({ id: hex(crypto.getRandomValues(new Uint8Array(6))), ts: now, ct: b.ct });
      await this.ctx.storage.put(key, arr.slice(-MAX_MSGS));
      await this.sweep();
      this.push(peer.did, { t: 'cmd', ts: now, ct: b.ct });
      return out({ ok: true });
    }
    if (path === '/poll') {
      const key = 'i:' + b.did;
      const arr = ((await this.ctx.storage.get(key)) || []).filter((x) => now - x.ts < this.cmdTtl());
      if (arr.length) await this.ctx.storage.delete(key);        // delivered once
      const photos = [];
      for (const [k, v] of await this.ctx.storage.list({ prefix: 'pm:' })) {
        if (v.to === b.did && now - v.ts < this.photoTtl()) photos.push({ id: k.slice(3), ts: v.ts, size: v.size });
      }
      return out({ ok: true, msgs: arr.map((x) => ({ id: x.id, ts: x.ts, ct: x.ct })), photos });
    }
    if (path === '/photo/put') {
      if (await this.ctx.storage.get('pm:' + b.id)) return out({ ok: true, dup: true });   // retry of the same upload
      let pending = 0;
      for (const [, v] of await this.ctx.storage.list({ prefix: 'pm:' })) if (v.to === peer.did) pending++;
      if (pending >= MAX_PENDING_PHOTOS) return out({ ok: false, error: 'full' });
      const n = Math.ceil(b.ct.length / CHUNK);
      for (let k = 0; k < n; k++) await this.ctx.storage.put('pc:' + b.id + ':' + k, b.ct.slice(k * CHUNK, (k + 1) * CHUNK));
      await this.ctx.storage.put('pm:' + b.id, { to: peer.did, ts: now, size: b.ct.length, n });
      await this.sweep();
      this.push(peer.did, { t: 'photo', id: b.id });
      return out({ ok: true });
    }
    if (path === '/photo/get') {
      const meta = await this.ctx.storage.get('pm:' + b.id);
      if (!meta || meta.to !== b.did || now - meta.ts >= this.photoTtl()) return out({ ok: false, error: 'not_found' });
      let ct = '';
      for (let k = 0; k < meta.n; k++) ct += (await this.ctx.storage.get('pc:' + b.id + ':' + k)) || '';
      return out({ ok: true, ct });
    }
    // Live location: ONE slot per recipient holding only the latest encrypted fix, overwritten by the next
    // one and never kept longer than LOC_TTL. The server therefore has no location history at all.
    if (path === '/loc/put') {
      await this.ctx.storage.put('l:' + peer.did, { ts: now, ct: b.ct });
      const cur = await this.ctx.storage.getAlarm();
      if (!cur || cur > now + this.locTtl()) await this.ctx.storage.setAlarm(now + this.locTtl());
      this.push(peer.did, { t: 'loc', ts: now, ct: b.ct });
      return out({ ok: true });
    }
    if (path === '/loc/get') {
      const rec = await this.ctx.storage.get('l:' + b.did);
      if (!rec || now - rec.ts >= this.locTtl()) return out({ ok: true, none: true });
      return out({ ok: true, ts: rec.ts, ct: rec.ct });
    }
    if (path === '/loc/clear') {
      await this.ctx.storage.delete('l:' + peer.did);
      return out({ ok: true });
    }
    if (path === '/photo/ack') {
      const meta = await this.ctx.storage.get('pm:' + b.id);
      if (meta && meta.to === b.did) { await this.dropPhoto(b.id, meta.n); await this.sweep(); }
      return out({ ok: true });                                    // idempotent
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
