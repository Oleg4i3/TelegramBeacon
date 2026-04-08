package com.oleg.telegrambeacon

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TelegramSender(private val prefs: SharedPreferences) {

    private val token      get() = prefs.getString(Config.KEY_BOT_TOKEN, "") ?: ""
    private val ownerChatId get() = prefs.getString(Config.KEY_CHAT_ID,   "") ?: ""

    // =========================================================================
    // Send to OWNER (beacon's configured chat)
    // =========================================================================

    fun sendMessageToOwner(text: String, keyboard: JSONArray? = null) =
        sendToChat(ownerChatId, text, keyboard)

    fun sendPhotoToOwner(file: File, caption: String = "") =
        sendPhotoToChat(ownerChatId, file, caption)

    // =========================================================================
    // Send to SPECIFIC chat (used for guest sessions)
    // =========================================================================

    fun sendToChat(chatId: String, text: String, keyboard: JSONArray? = null) {
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("chat_id",    chatId)
                    put("text",       text)
                    put("parse_mode", "HTML")
                    put("disable_web_page_preview", true)
                    if (keyboard != null)
                        put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
                }.toString()
                post("sendMessage", body.toByteArray(), "application/json")
            } catch (e: Exception) { Log.e("TG", "sendMessage failed", e) }
        }.start()
    }

    fun sendPhotoToChat(chatId: String, file: File, caption: String = "") =
        sendFile(chatId, "sendPhoto", "photo", "photo.jpg", "image/jpeg", file, caption)

    fun sendVideoToChat(chatId: String, file: File, caption: String = "") =
        sendFile(chatId, "sendVideo", "video", "video.mp4", "video/mp4", file, caption, readTimeout = 60_000)

    fun sendAudioToChat(chatId: String, file: File, caption: String = "") =
        sendFile(chatId, "sendAudio", "audio", "audio.m4a", "audio/mp4", file, caption)

    // Keep old names as convenience wrappers for owner
    fun sendPhoto(file: File, caption: String = "") = sendPhotoToOwner(file, caption)

    // =========================================================================
    // getUpdates / answerCallback
    // =========================================================================

    fun getUpdates(offset: Long): List<Update> {
        if (token.isBlank()) return emptyList()
        return try {
            val url = URL("https://api.telegram.org/bot$token/getUpdates?offset=$offset&limit=20&timeout=0")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseUpdates(json)
        } catch (e: Exception) {
            Log.e("TG", "getUpdates failed", e); emptyList()
        }
    }

    fun answerCallback(callbackQueryId: String) {
        if (token.isBlank()) return
        Thread {
            try {
                post("answerCallbackQuery",
                    JSONObject().put("callback_query_id", callbackQueryId).toString().toByteArray(),
                    "application/json")
            } catch (e: Exception) { Log.e("TG", "answerCallback failed", e) }
        }.start()
    }

    // =========================================================================
    // Keyboard builder
    // =========================================================================

    companion object {
        fun mainKeyboard(alarmOn: Boolean): JSONArray {
            fun btn(label: String, data: String) =
                JSONObject().put("text", label).put("callback_data", data)

            return JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("📷 Photo", "/foto"))
                    put(btn("📷 Front cam", "/foto front"))
                })
                put(JSONArray().apply {
                    put(btn("🎥 Video 5s", "/video"))
                    put(btn("🎥 Video front", "/video front"))
                })
                put(JSONArray().apply {
                    put(btn("🎙 Audio 10s", "/audio"))
                    put(btn("📍 GPS", "/gps"))
                })
                put(JSONArray().apply {
                    put(btn("🎙 30s", "/audio 30"))
                    put(btn("🎙 60s", "/audio 60"))
                    put(btn("🎙 120s", "/audio 120"))
                })
                put(JSONArray().apply {
                    if (alarmOn) {
                        put(btn("✅ Alarm ON", "/on"))
                        put(btn("▶ Turn OFF", "/off"))
                    } else {
                        put(btn("▶ Turn ON", "/on"))
                        put(btn("🔕 Alarm OFF", "/off"))
                    }
                })
                put(JSONArray().apply {
                    put(btn("🔍 Find device", "/find"))
                    put(btn("📊 Status", "/status"))
                    put(btn("❓ Help", "/help"))
                })
            }
        }
    }

    // =========================================================================
    // Private
    // =========================================================================

    private fun sendFile(
        chatId: String, method: String, fieldName: String,
        fileName: String, mimeType: String, file: File,
        caption: String = "", readTimeout: Int = 30_000
    ) {
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val boundary = "----Boundary${System.currentTimeMillis()}"
                val conn = (URL("https://api.telegram.org/bot$token/$method")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    doOutput = true; connectTimeout = 15_000; this.readTimeout = readTimeout
                }
                conn.outputStream.use { os ->
                    fun textPart(name: String, value: String) {
                        os.write("--$boundary\r\n".toByteArray())
                        os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                        os.write("$value\r\n".toByteArray())
                    }
                    textPart("chat_id", chatId)
                    if (caption.isNotBlank()) {
                        os.write("--$boundary\r\n".toByteArray())
                        os.write("Content-Disposition: form-data; name=\"caption\"\r\n".toByteArray())
                        os.write("Content-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray())
                        os.write("$caption\r\n".toByteArray())
                    }
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n".toByteArray())
                    os.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                    os.write(file.readBytes())
                    os.write("\r\n--$boundary--\r\n".toByteArray())
                    os.flush()
                }
                val code = conn.responseCode
                if (code != 200)
                    Log.w("TG", "$method HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()}")
                conn.disconnect()
            } catch (e: Exception) { Log.e("TG", "$method failed", e) }
        }.start()
    }

    private fun post(method: String, body: ByteArray, contentType: String) {
        val conn = (URL("https://api.telegram.org/bot$token/$method")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", contentType)
            doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
        }
        conn.outputStream.use { it.write(body) }
        if (conn.responseCode != 200) Log.w("TG", "$method HTTP ${conn.responseCode}")
        conn.disconnect()
    }

    private fun parseUpdates(json: String): List<Update> {
        val result = mutableListOf<Update>()
        return try {
            val root = JSONObject(json)
            if (!root.getBoolean("ok")) return result
            val arr = root.getJSONArray("result")
            for (i in 0 until arr.length()) {
                val upd      = arr.getJSONObject(i)
                val updateId = upd.getLong("update_id")
                val message  = upd.optJSONObject("message")
                if (message != null) {
                    val text   = message.optString("text", "").trim()
                    val fromId = message.optJSONObject("from")?.optLong("id") ?: 0L
                    if (text.isNotEmpty())
                        result.add(Update(updateId, text, fromId, null))
                    continue
                }
                val cq = upd.optJSONObject("callback_query")
                if (cq != null) {
                    val data   = cq.optString("data", "").trim()
                    val fromId = cq.optJSONObject("from")?.optLong("id") ?: 0L
                    val cqId   = cq.optString("id", "")
                    if (data.isNotEmpty())
                        result.add(Update(updateId, data, fromId, cqId))
                }
            }
            result
        } catch (e: Exception) {
            Log.e("TG", "parseUpdates failed", e); result
        }
    }

    data class Update(
        val updateId: Long, val text: String,
        val fromId: Long, val callbackQueryId: String?
    )
}
