import { Miniflare } from 'miniflare';
import { webcrypto as crypto } from 'node:crypto';
import assert from 'node:assert/strict';
const mf = new Miniflare({
  modules: true, scriptPath: 'worker.js', compatibilityDate: '2025-01-01',
  durableObjects: { RENDEZVOUS: 'Rendezvous', ROOMS: 'PairRoom', LIMITER: 'Limiter', BLEHUB: 'BleHub' },
  bindings: { TEST_MODE: '1' },
});
const b64 = (u8) => Buffer.from(u8).toString('base64');
const sha = async (u8) => Buffer.from(await crypto.subtle.digest('SHA-256', u8)).toString('hex');
async function dev() {
  const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', kp.publicKey));
  return { kp, spki, pub: b64(spki), did: (await sha(spki)).slice(0, 32) };
}
async function headers(d, method, path, body) {
  const ts = Math.floor(Date.now() / 1000), nonce = Math.random().toString(36).slice(2, 14) + 'abc';
  const msg = `TF1|${method}|${path}|${ts}|${nonce}|${await sha(new TextEncoder().encode(body))}`;
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, d.kp.privateKey, new TextEncoder().encode(msg)));
  return { 'x-tt-pub': d.pub, 'x-tt-ts': String(ts), 'x-tt-nonce': nonce, 'x-tt-sig': b64(sig), 'content-type': 'application/json' };
}
async function post(d, path, obj) {
  const body = JSON.stringify(obj);
  const r = await mf.dispatchFetch('https://w' + path, { method: 'POST', headers: await headers(d, 'POST', path, body), body });
  return { s: r.status, j: await r.json() };
}
async function openWs(d, pid) {
  const r = await mf.dispatchFetch('https://w/ws?pid=' + pid, { headers: { ...(await headers(d, 'GET', '/ws', '')), upgrade: 'websocket' } });
  assert.equal(r.status, 101); const ws = r.webSocket; ws.accept(); const got = [];
  ws.addEventListener('message', (e) => got.push(JSON.parse(e.data))); return { ws, got };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0; const ok = (c, m) => { assert.ok(c, m); pass++; console.log('ok -', m); };

const A = await dev(), B = await dev(), C = await dev();   // A = lost phone, B = family watcher, C = stranger helper
const pid = 'ab'.repeat(16);
const ns = await mf.getDurableObjectNamespace('ROOMS');
const room = ns.get(ns.idFromName(pid));
await (await room.fetch('https://do/create', { method: 'POST', body: JSON.stringify({ members: [{ did: A.did, pub: A.pub, name: 'A' }, { did: B.did, pub: B.pub, name: 'B' }] }) })).json();

// ---------- WebSocket live location
const wa = await openWs(A, pid), wb = await openWs(B, pid); await sleep(200);
const ct = b64(new Uint8Array(40).fill(7));
wa.ws.send(JSON.stringify({ t: 'loc', ct })); await sleep(300);
ok(wb.got.some((m) => m.t === 'loc' && m.ct === ct), 'WS loc pushed A -> B');
ok(!wa.got.some((m) => m.t === 'loc'), 'no echo to sender');
let g = await post(B, '/loc/get', { pid });
ok(g.j.ct === ct, 'WS-sent fix is in B\'s slot (HTTP /loc/get)');
wa.ws.send(JSON.stringify({ t: 'loc', ct: 'x' })); wa.ws.send('garbage'); wa.ws.send(JSON.stringify({ t: 'other' })); await sleep(200);
ok(wb.got.filter((m) => m.t === 'loc').length === 1, 'bad / other / too-fast messages ignored');
await sleep(1000);
const ct2 = b64(new Uint8Array(40).fill(9)); wa.ws.send(JSON.stringify({ t: 'loc', ct: ct2 })); await sleep(300);
ok(wb.got.filter((m) => m.t === 'loc').length === 2, 'second fix after 1 s accepted');
// stranger socket can not inject into the pair
const wc = await mf.dispatchFetch('https://w/ws?pid=' + pid, { headers: { ...(await headers(C, 'GET', '/ws', '')), upgrade: 'websocket' } });
ok(wc.status === 404, 'stranger cannot open the pair socket');

// ---------- BLE
const beacon = (ctr, seq, rid = [1, 2, 3, 4]) => { const u = new Uint8Array(23); u[0] = 1; u.set(rid, 1); u[5] = (ctr >> 16) & 255; u[6] = (ctr >> 8) & 255; u[7] = ctr & 255; u[8] = seq; u.fill(0x5a, 9); return b64(u); };
let r = await post(C, '/ble/register', { pid, rids: ['01020304'] });
ok(r.s === 404, 'stranger cannot register ids for a pair');
r = await post(A, '/ble/register', { pid, rids: ['01020304', 'aabbccdd'] });
ok(r.j.ok === true && r.j.count === 2, 'member registers rotating ids');
r = await post(A, '/ble/register', { pid, rids: ['zzzzzzzz'] }); ok(r.s === 400, 'bad id format refused');
r = await post(C, '/ble/report', { rid: '0badbad0', b: beacon(5, 1, [11, 22, 33, 44]) });
ok(r.j.ok && r.j.known === false, 'unknown id: accepted silently, nothing stored');
r = await post(C, '/ble/report', { rid: '01020304', b: beacon(1000, 1) });
ok(r.j.ok && r.j.known && r.j.stored, 'stranger helper relays a beacon for a registered id');
ok(wb.got.some((m) => m.t === 'ble'), 'beacon pushed live to the watcher B');
ok(!wa.got.some((m) => m.t === 'ble'), 'not pushed to the beacon owner');
g = await post(B, '/ble/get', { pid }); ok(g.j.ok && g.j.b === beacon(1000, 1), 'watcher B fetches the beacon');
g = await post(A, '/ble/get', { pid }); ok(g.j.none === true, 'owner A has no beacon slot (it is for B)');
r = await post(C, '/ble/report', { rid: '01020304', b: beacon(999, 9) }); ok(r.j.stored === false, 'older epoch cannot replace newer');
r = await post(C, '/ble/report', { rid: '01020304', b: beacon(1000, 1) }); ok(r.j.stored === false, 'replay of same beacon not stored again');
r = await post(C, '/ble/report', { rid: '01020304', b: beacon(1000, 2) }); ok(r.j.stored === true, 'newer sequence replaces');
r = await post(C, '/ble/report', { rid: '01020304', b: 'AAAA' }); ok(r.s === 400, 'wrong size refused');
const bad = new Uint8Array(23); bad[0] = 2; r = await post(C, '/ble/report', { rid: '01020304', b: b64(bad) }); ok(r.s === 400, 'wrong version refused');
g = await post(C, '/ble/get', { pid }); ok(g.s === 404, 'stranger cannot read the beacon slot');
const hub = (await mf.getDurableObjectNamespace('BLEHUB')); 
const many = Array.from({ length: 200 }, (_, i) => (0x10000000 + i).toString(16).padStart(8, '0'));
for (let k = 0; k < 4; k++) await post(A, '/ble/register', { pid, rids: many.map((x, i) => (0x20000000 + k * 1000 + i).toString(16).padStart(8, '0')) });
r = await post(C, '/ble/report', { rid: (0x20000000).toString(16), b: beacon(2000, 1, [0x20, 0, 0, 0]) });
ok(r.j.known === false, 'oldest ids are evicted beyond the per-device cap (600)');
r = await post(C, '/ble/report', { rid: (0x20000000 + 3000 + 199).toString(16), b: beacon(2000, 1, [0x20, 0, 0x0b, 0xc7]) });
ok(r.j.known === true, 'newest ids still valid');
console.log('\nALL', pass, 'checks passed'); await mf.dispose(); process.exit(0);
