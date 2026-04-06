package com.oleg.telegrambeacon

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin wrapper around Telegram Bot API.
 * All calls are synchronous — wrap in Thread{} or coroutine at call site.
 */
class TelegramSender(private val prefs: SharedPreferences) {

    private val token  get() = prefs.getString(Config.KEY_BOT_TOKEN, "") ?: ""
    private val chatId get() = prefs.getString(Config.KEY_CHAT_ID,   "") ?: ""

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun sendMessage(text: String) {
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("chat_id",    chatId)
                    put("text",       text)
                    put("parse_mode", "HTML")
                    put("disable_web_page_preview", true)
                }.toString()
                post("sendMessage", body.toByteArray(), "application/json")
            } catch (e: Exception) {
                Log.e("TG", "sendMessage failed", e)
            }
        }.start()
    }

    fun sendPhoto(file: File, caption: String = "") {
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val boundary = "----Boundary${System.currentTimeMillis()}"
                val url = URL("https://api.telegram.org/bot$token/sendPhoto")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout    = 20_000
                }
                conn.outputStream.use { os ->
                    fun part(name: String, value: String) {
                        os.write("--$boundary\r\n".toByteArray())
                        os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                        os.write("$value\r\n".toByteArray())
                    }
                    part("chat_id", chatId)
                    if (caption.isNotBlank()) {
                        os.write("--$boundary\r\n".toByteArray())
                        os.write("Content-Disposition: form-data; name=\"caption\"\r\n".toByteArray())
                        os.write("Content-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray())
                        os.write("$caption\r\n".toByteArray())
                    }
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\n".toByteArray())
                    os.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                    os.write(file.readBytes())
                    os.write("\r\n--$boundary--\r\n".toByteArray())
                    os.flush()
                }
                val code = conn.responseCode
                if (code != 200) Log.w("TG", "sendPhoto HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("TG", "sendPhoto failed", e)
            }
        }.start()
    }

    /** Synchronous — call from background thread only. */
    fun getUpdates(offset: Long): List<Update> {
        if (token.isBlank()) return emptyList()
        return try {
            val url = URL("https://api.telegram.org/bot$token/getUpdates?offset=$offset&limit=10&timeout=0")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 10_000
            }
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseUpdates(json)
        } catch (e: Exception) {
            Log.e("TG", "getUpdates failed", e)
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun post(method: String, body: ByteArray, contentType: String) {
        val url = URL("https://api.telegram.org/bot$token/$method")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", contentType)
            doOutput = true
            connectTimeout = 15_000
            readTimeout    = 15_000
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code != 200) Log.w("TG", "$method HTTP $code")
        conn.disconnect()
    }

    private fun parseUpdates(json: String): List<Update> {
        val result = mutableListOf<Update>()
        return try {
            val root = JSONObject(json)
            if (!root.getBoolean("ok")) return result
            val arr = root.getJSONArray("result")
            for (i in 0 until arr.length()) {
                val upd     = arr.getJSONObject(i)
                val updateId = upd.getLong("update_id")
                val message  = upd.optJSONObject("message") ?: continue
                val text     = message.optString("text", "").trim()
                val fromId   = message.optJSONObject("from")?.optLong("id") ?: 0L
                if (text.isNotEmpty()) result.add(Update(updateId, text, fromId))
            }
            result
        } catch (e: Exception) {
            result
        }
    }

    data class Update(val updateId: Long, val text: String, val fromId: Long)
}
