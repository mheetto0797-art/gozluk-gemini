package com.metin.gozlukgemini

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Google Gemini REST istemcisi (ek kütüphane gerektirmez). */
class GeminiClient(var apiKey: String, var model: String) {

    class GeminiException(val code: Int, message: String) : Exception(message)

    suspend fun describe(jpeg: ByteArray?, prompt: String, system: String): String =
        withContext(Dispatchers.IO) {
            val parts = JSONArray()
            if (jpeg != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                    ),
                )
            }
            parts.put(JSONObject().put("text", prompt))

            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
                )
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject().put("maxOutputTokens", 200).put("temperature", 0.4),
                )

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrDefault(text.take(200))
                    throw GeminiException(code, msg)
                }
                val candidates = JSONObject(text).optJSONArray("candidates") ?: return@withContext ""
                if (candidates.length() == 0) return@withContext ""
                val outParts = candidates.getJSONObject(0)
                    .optJSONObject("content")?.optJSONArray("parts") ?: return@withContext ""
                buildString {
                    for (i in 0 until outParts.length()) append(outParts.getJSONObject(i).optString("text"))
                }.trim()
            } finally {
                conn.disconnect()
            }
        }
}
