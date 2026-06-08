package com.thievestrap

import android.content.Context
import android.util.Log
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
                        // Photo part
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
     * Poll Telegram for /start or /id commands and auto-reply with Chat ID.
     * Call periodically (e.g. every 30s when app is in foreground).
     */
    fun pollAndReply(context: Context) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
                val off = prefs.getLong("tg_offset", 0L)
                val apiUrl = BASE + "/getUpdates?offset=" + off + "&timeout=5"
                val conn = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 12000
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (!resp.contains("update_id")) return@Thread
                val blocks = resp.split("update_id").drop(1)
                for (block in blocks) {
                    val uid = block.substringAfter(":").substringBefore(",").trim().toLongOrNull() ?: continue
                    val fromIdx = block.indexOf("from")
                    if (fromIdx < 0) { prefs.edit().putLong("tg_offset", uid + 1L).apply(); continue }
                    val fromSlice = block.substring(fromIdx)
                    val idIdx = fromSlice.indexOf("id")
                    if (idIdx < 0) { prefs.edit().putLong("tg_offset", uid + 1L).apply(); continue }
                    val afterId = fromSlice.substring(idIdx + 2).trimStart()
                    val afterColon = if (afterId.startsWith(":")) afterId.substring(1).trimStart() else afterId
                    val chatId = afterColon.substringBefore(",").trim().filter { c -> c.isDigit() }
                    val textIdx = block.indexOf("text")
                    val cmd = if (textIdx >= 0) {
                        val afterText = block.substring(textIdx + 4).trimStart()
                        val afterTextColon = if (afterText.startsWith(":")) afterText.substring(1).trimStart() else afterText
                        afterTextColon.substringBefore(",").substringBefore("}")
                            .replace("\\u002F", "/").trim().uppercase()
                    } else ""
                    if (chatId.isNotBlank() && (cmd.contains("START") || cmd.contains("/ID"))) {
                        val reply = "Your Chat ID: " + chatId +
                            "\n\nCopy and paste into: Settings > Telegram Chat IDs."
                        sendReply(chatId, reply)
                        Log.i(TAG, "Sent ID to: " + chatId)
                    }
                    prefs.edit().putLong("tg_offset", uid + 1L).apply()
                }
            } catch (e: Exception) { Log.e(TAG, "poll: " + e.message) }
        }.start()
    }


    private fun sendReply(chatId: String, text: String) {
        try {
            val url = URL("$BASE/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            val body = """{"chat_id":"$chatId","text":"$text","parse_mode":"Markdown"}"""
            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body) }
            Log.i(TAG, "Reply sent to $chatId: HTTP ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) { Log.e(TAG, "sendReply: ${e.message}") }
    }
}
