package com.thievestrap

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v2.9.19: the live channel. While MonitorService (armed) or the Family screen is open, this phone keeps one
 * WebSocket per pair to the family Worker, so a ring request, a "start tracking" request, a new location or
 * a photo notice arrives within about a second instead of at the next 20 s poll. The polling loop stays as a
 * fallback, and every command still goes through the same checks (decrypt, 120 s window, nonce, MY permission),
 * so receiving one twice (push + poll) does nothing twice.
 *
 * Uses are reference counted: acquire() / release() from each owner; sockets exist while at least one owner does.
 * The connection is signed like every request; the server keeps nothing but ciphertext.
 */
object FamilySocket {

    private const val TAG = "FamilySocket"
    private val client by lazy {
        OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val worker = Executors.newSingleThreadExecutor()          // handles pushed messages off OkHttp's thread
    private val sockets = HashMap<String, WebSocket>()
    private val attempts = HashMap<String, Int>()
    private var holders = 0

    /** Set by the Family screen: (pid, decrypted location JSON) for the pair being watched. */
    @Volatile var locListener: ((String, String) -> Unit)? = null
    /** v2.9.23: set by the Family screen: (pid, base64 beacon) when a nearby phone relayed an offline beacon of the pair. */
    @Volatile var bleListener: ((String, String) -> Unit)? = null

    // v2.9.22: whether the PEER of each pair currently has a live socket open (server-reported, real-time).
    // Unknown until the first presence_init/presence message for that pid arrives; treated as offline until then.
    private val presence = HashMap<String, Boolean>()
    @Volatile var presenceListener: (() -> Unit)? = null

    /**
     * v2.9.23: pushes one encrypted live-location fix up the open socket (the Worker stores it in the single
     * location slot and forwards it to the peer, exactly like POST /loc/put). False = no open socket or the
     * message could not be queued: the caller then falls back to HTTP.
     */
    @Synchronized
    fun sendLoc(pid: String, ct: String): Boolean {
        val ws = sockets[pid] ?: return false
        return try { ws.send(JSONObject().put("t", "loc").put("ct", ct).toString()) } catch (e: Exception) { false }
    }

    @Synchronized
    fun isOnline(pid: String): Boolean = presence[pid] == true

    @Synchronized
    private fun setOnline(pid: String, online: Boolean) { presence[pid] = online }

    @Synchronized
    fun acquire(ctx: Context) { holders++; ensure(ctx) }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) { sockets.values.forEach { try { it.close(1000, "bye") } catch (e: Exception) {} }; sockets.clear(); attempts.clear() }
    }

    /** Opens what is missing and closes sockets of pairs that no longer exist. Cheap: call it often. */
    @Synchronized
    fun ensure(ctx: Context) {
        if (holders == 0) return
        val app = ctx.applicationContext
        val pairs = FamilyBridge.pairs(app)
        sockets.keys.filter { k -> pairs.none { it.pid == k } }.forEach { sockets.remove(it)?.close(1000, "unpaired") }
        pairs.forEach { if (!sockets.containsKey(it.pid)) open(app, it.pid) }
    }

    private fun open(app: Context, pid: String) {
        val headers = FamilyBridge.signedHeaders("GET", "/ws", "") ?: return
        val url = FamilyBridge.API_URL.replaceFirst("https://", "wss://") + "/ws?pid=" + pid
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        val ws = client.newWebSocket(rb.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(FamilySocket) { attempts[pid] = 0 }
                // a socket that just (re)connected proves nothing about the PEER yet: presence_init settles it
                // catch up with anything that arrived while the socket was down
                worker.execute { try { FamilyRelay.pollOnce(app) } catch (e: Exception) { Log.w(TAG, "catch-up failed: ${e.message}") } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { worker.execute { handle(app, pid, text) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropAndRetry(app, pid, webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { dropAndRetry(app, pid, webSocket) }
        })
        sockets[pid] = ws
    }

    private fun dropAndRetry(app: Context, pid: String, ws: WebSocket) {
        synchronized(this) {
            if (sockets[pid] === ws) sockets.remove(pid) else return
            if (holders == 0) return
            val n = (attempts[pid] ?: 0) + 1
            attempts[pid] = n
            val delay = minOf(60L, 1L shl minOf(n, 6))
            scheduler.schedule({ try { ensure(app) } catch (e: Exception) {} }, delay, TimeUnit.SECONDS)
        }
    }

    private fun handle(app: Context, pid: String, text: String) {
        try {
            val o = JSONObject(text)
            val pr = FamilyBridge.pairs(app).firstOrNull { it.pid == pid } ?: return
            val key = FamilyBridge.pairKey(app, pr) ?: return
            when (o.optString("t")) {
                "cmd" -> FamilyRelay.handleCommand(app, pr, key, o.optString("ct"))
                "photo" -> FamilyRelay.pollOnce(app)                    // downloads, saves, acknowledges
                "loc" -> FamilyBridge.open(key, "loc|$pid", o.optString("ct"))?.let { locListener?.invoke(pid, String(it)) }
                "ble" -> bleListener?.invoke(pid, o.optString("b"))
                "presence", "presence_init" -> { setOnline(pid, o.optBoolean("online", false)); presenceListener?.invoke() }
            }
        } catch (e: Exception) { Log.w(TAG, "bad push: ${e.message}") }
    }
}
