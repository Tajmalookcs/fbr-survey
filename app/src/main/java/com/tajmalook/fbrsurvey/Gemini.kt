package com.tajmalook.fbrsurvey

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

/**
 * Reads the shop's business name from the signboard in a photo using Google Gemini
 * (free API key from aistudio.google.com, stored only on this phone).
 */
object Gemini {

    sealed class Result {
        data class Name(val name: String) : Result()
        object NoName : Result()
        object NoKey : Result()
        data class BadKey(val msg: String) : Result()
        object Limit : Result()
        object Offline : Result()
        data class Error(val msg: String) : Result()
    }

    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash")

    private const val PROMPT = """This is a photo of a shop front in Pakistan, taken for a tax survey.
Read the shop's business name from its signboard. The signboard may be in Urdu or English.
Give the name transliterated into English letters, in CAPITAL LETTERS, the way it is usually
written in English in Pakistan (for example: ZAIN GENERAL STORE, MADINA KARYANA STORE,
AL-SHIFA MEDICAL STORE, HAJI ZULFIQAR MACHINERY STORE).
Ignore phone numbers, addresses, slogans, owner names written as contact persons,
product or brand advertisements (e.g. battery or drink brands), and any app stamp or text boxes.
If no shop name is clearly readable, return an empty name.
Reply with JSON only: {"name": "..."}"""

    fun key(ctx: Context): String =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("geminiKey", "").orEmpty().trim()

    fun setKey(ctx: Context, k: String) =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("geminiKey", k.trim()).apply()

    fun hasKey(ctx: Context) = key(ctx).isNotEmpty()

    /** Reads the name from an (unstamped) photo file. Call off the main thread. */
    fun readName(ctx: Context, photoFile: File): Result {
        val k = key(ctx)
        if (k.isEmpty()) return Result.NoKey
        if (!photoFile.exists()) return Result.Error("Photo not found")
        val bmp = try { Photo.loadUpright(photoFile, 1280) } catch (e: Exception) { return Result.Error("Could not open photo") }
        val small = scaleDown(bmp, 1024)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small != bmp) small.recycle()
        bmp.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))

        return when (val r = call(k, body)) {
            is Raw.Ok -> {
                val name = parseName(r.text)
                if (name.isNullOrBlank()) Result.NoName else Result.Name(name)
            }
            is Raw.Fail -> r.result
        }
    }

    /** Checks that a key works (text-only request). Call off the main thread. */
    fun test(k: String): Result {
        if (k.isBlank()) return Result.NoKey
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", "Reply with the single word OK")))))
        return when (val r = call(k.trim(), body)) {
            is Raw.Ok -> Result.Name(r.text.trim())
            is Raw.Fail -> r.result
        }
    }

    fun message(r: Result): String = when (r) {
        is Result.Name -> "✓ Read from signboard: please check"
        Result.NoName -> "No shop name found on the board"
        Result.NoKey -> "No Gemini key. Add it in ⋮ → Settings"
        is Result.BadKey -> "Gemini key not accepted. Check it in ⋮ → Settings"
        Result.Limit -> "Gemini free limit reached. Will try again later"
        Result.Offline -> "No internet. Name will be read when internet is back"
        is Result.Error -> "Could not read name (${r.msg})"
    }

    // ---------- internals ----------

    private sealed class Raw {
        data class Ok(val text: String) : Raw()
        data class Fail(val result: Result) : Raw()
    }

    private fun call(k: String, body: JSONObject): Raw {
        var last: Raw = Raw.Fail(Result.Error("No model available"))
        for (model in MODELS) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", k)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                when {
                    code in 200..299 -> return Raw.Ok(extractText(text))
                    code == 404 -> { last = Raw.Fail(Result.Error("model $model not found")); continue }
                    code == 429 -> return Raw.Fail(Result.Limit)
                    code == 400 && text.contains("API_KEY", true) -> return Raw.Fail(Result.BadKey(errMsg(text)))
                    code == 401 || code == 403 -> return Raw.Fail(Result.BadKey(errMsg(text)))
                    code >= 500 -> return Raw.Fail(Result.Error("Gemini busy, try again"))
                    else -> return Raw.Fail(Result.Error(errMsg(text).ifBlank { "HTTP $code" }))
                }
            } catch (e: IOException) {
                return Raw.Fail(Result.Offline)
            } catch (e: Exception) {
                return Raw.Fail(Result.Error(e.message ?: "error"))
            }
        }
        return last
    }

    private fun extractText(json: String): String = try {
        val parts = JSONObject(json).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        buildString {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought")) append(p.optString("text"))
            }
        }
    } catch (e: Exception) {
        ""
    }

    private fun parseName(text: String): String? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val raw = try {
            JSONObject(t).optString("name")
        } catch (e: Exception) {
            Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: return null
        }
        return raw.replace(Regex("\\s+"), " ").trim().uppercase(Locale.ENGLISH).take(80)
    }

    private fun errMsg(json: String): String = try {
        JSONObject(json).getJSONObject("error").optString("message")
    } catch (e: Exception) {
        ""
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val l = max(b.width, b.height)
        if (l <= maxSide) return b
        val s = maxSide.toFloat() / l
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
