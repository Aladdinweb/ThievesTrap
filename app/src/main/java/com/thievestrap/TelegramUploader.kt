package com.thievestrap

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelegramUploader {
    private const val TAG = "TT-Telegram"
    const val BOT_TOKEN = "8582138133:AAH3YlVpUR1WARnVV08fQJYFA2-vQghWsS4"
    private const val BASE = "https://api.telegram.org/bot$BOT_TOKEN"

    /** Send text message to all configured chat IDs */
    fun sendMessage(context: Context, text: String) {
        val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("telegram_enabled", false)) { Log.d(TAG, "Telegram disabled"); return }
        val chatIds = getChatIds(context)
        if (chatIds.isEmpty()) { Log.w(TAG, "No Telegram chat IDs configured"); return }
        chatIds.forEach { chatId ->
            Thread {
                try {
                    val url = URL("$BASE/sendMessage")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 10_000
                    val escaped = text.replace("\"", "\\\"")
                    val body = """{"chat_id":"$chatId","text":"$escaped","parse_mode":"HTML"}"""
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    Log.i(TAG, "sendMessage → $chatId: HTTP $code")
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "sendMessage failed: ${e.message}") }
            }.start()
        }
    }

    /** Send photo file to all configured chat IDs */
    fun sendPhoto(context: Context, photoFile: File, caption: String) {
        val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("telegram_enabled", false)) { Log.d(TAG, "Telegram disabled"); return }
        val chatIds = getChatIds(context)
        if (chatIds.isEmpty()) { Log.w(TAG, "No Telegram chat IDs"); return }
        if (!photoFile.exists() || photoFile.length() == 0L) {
            Log.w(TAG, "Photo not ready: ${photoFile.path}"); return
        }
        chatIds.forEach { chatId ->
            Thread {
                try {
                    val boundary = "TT_${System.currentTimeMillis()}"
                    val url = URL("$BASE/sendPhoto")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.doOutput = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout  = 30_000

                    conn.outputStream.use { os ->
                        fun part(name: String, value: String) {
                            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                        }
                        part("chat_id", chatId)
                        part("caption", caption)
                        os.write("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"intruder.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                        photoFile.inputStream().use { it.copyTo(os) }
                        os.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    val code = conn.responseCode
                    Log.i(TAG, "sendPhoto → $chatId: HTTP $code")
                    conn.disconnect()
                } catch (e: Exception) { Log.e(TAG, "sendPhoto failed: ${e.message}") }
            }.start()
        }
    }

    private fun getChatIds(context: Context): List<String> {
        val raw = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
            .getString("telegram_chat_ids", "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Poll Telegram getUpdates for /start or /id commands.
     * Parses the JSON response properly and replies with Chat ID.
     * Call from SettingsActivity when user taps "Open Bot" button.
     */
    fun pollAndReply(context: Context) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
                val offset = prefs.getLong("tg_offset", 0L)

                val conn = (URL("$BASE/getUpdates?offset=$offset&timeout=10&allowed_updates=[\"message\"]")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "getUpdates HTTP $responseCode")
                    conn.disconnect()
                    return@Thread
                }

                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(resp)
                if (!json.optBoolean("ok", false)) {
                    Log.w(TAG, "getUpdates not ok: $resp")
                    return@Thread
                }

                val results = json.optJSONArray("result") ?: return@Thread
                var maxUpdateId = offset

                for (i in 0 until results.length()) {
                    val update = results.getJSONObject(i)
                    val updateId = update.optLong("update_id", 0L)
                    if (updateId >= maxUpdateId) maxUpdateId = updateId + 1

                    val message = update.optJSONObject("message") ?: continue
                    val from = message.optJSONObject("from") ?: continue
                    val chatId = from.optLong("id", 0L)
                    if (chatId == 0L) continue

                    val text = message.optString("text", "").uppercase().trim()
                    // Respond to /start, /id, or any start variant
                    if (text.startsWith("/START") || text.startsWith("/ID") || text == "START") {
                        val firstName = from.optString("first_name", "")
                        val reply = buildIdReply(chatId.toString(), firstName)
                        sendReply(chatId.toString(), reply)
                        Log.i(TAG, "Sent Chat ID to: $chatId")
                    }
                }

                // Persist offset so we don't reprocess old updates
                if (maxUpdateId > offset) {
                    prefs.edit().putLong("tg_offset", maxUpdateId).apply()
                    Log.i(TAG, "tg_offset updated to $maxUpdateId")
                }

            } catch (e: Exception) {
                Log.e(TAG, "pollAndReply: ${e.message}")
            }
        }.start()
    }

    private fun buildIdReply(chatId: String, firstName: String): String {
        val greeting = if (firstName.isNotBlank()) "Hello $firstName!\n\n" else ""
        return "${greeting}✅ Your Telegram Chat ID is:\n\n" +
               "`$chatId`\n\n" +
               "👉 Copy this number and paste it in:\n" +
               "Thieves Trap → Settings → Telegram Alerts → Paste Chat ID field\n\n" +
               "Once saved, you will receive security alerts and intruder photos directly here."
    }

    private fun sendReply(chatId: String, text: String) {
        try {
            val url = URL("$BASE/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            // Use JSON body — escape properly
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
            }.toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            Log.i(TAG, "sendReply → $chatId: HTTP $code")
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "sendReply error: $err")
            }
            conn.disconnect()
        } catch (e: Exception) { Log.e(TAG, "sendReply: ${e.message}") }
    }

    /** Share bot link text — used by Share Bot Link button */
    fun getBotShareText(): String =
        "🛡️ Thieves Trap Security Bot\n\n" +
        "Tap the link below to open our Telegram bot, then press START to get your Chat ID:\n\n" +
        "https://t.me/ThievesTrap_Alert_bot\n\n" +
        "Once you have your ID, paste it in: Settings → Telegram Alerts"

    /** Share chat ID text — used by Share Bot ID button */
    fun getChatIdShareText(context: Context): String {
        val ids = getChatIds(context)
        return if (ids.isEmpty())
            "No Chat ID configured yet. Open Thieves Trap → Settings → Telegram to set it up."
        else
            "My Thieves Trap Chat ID: ${ids.joinToString(", ")}\n\n" +
            "Add this to your Thieves Trap app under Settings → Telegram Alerts."
    }
}
