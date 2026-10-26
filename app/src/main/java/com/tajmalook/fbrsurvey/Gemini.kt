package com.tajmalook.fbrsurvey

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Tasks
import org.chromium.net.CronetEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max

/**
 * Reads the shop's business name from the signboard in a photo using Google Gemini
 * (free API key from aistudio.google.com, stored only on this phone).
 * Connects through Cronet (Chrome's network engine, QUIC) when available.
 */
object Gemini {

    sealed class Result {
        data class Name(val name: String) : Result()
        object NoName : Result()
        object NoKey : Result()
        data class BadKey(val msg: String) : Result()
        object Limit : Result()
        object Busy : Result()
        object Slow : Result()
        data class Offline(val detail: String) : Result()
        data class Error(val msg: String) : Result()
    }

    /** Fast "Lite" models first; bigger ones only if the Lite ones are busy. */
    private val MODELS = listOf(
        "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-3.5-flash-lite",
        "gemini-flash-latest", "gemini-2.5-flash",
    )

    /** Total time allowed for one name reading. */
    private const val TOTAL_MS = 30_000L

    private const val PROMPT = """This is a photo of a shop front in Pakistan, taken for a tax survey.
Read the shop's business name from its signboard. The signboard may be in Urdu or English.
Give the name transliterated into English letters, in CAPITAL LETTERS, the way it is usually
written in English in Pakistan (for example: ZAIN GENERAL STORE, MADINA KARYANA STORE,
AL-SHIFA MEDICAL STORE, HAJI ZULFIQAR MACHINERY STORE).
Ignore phone numbers, addresses, slogans, owner names written as contact persons,
product or brand advertisements (e.g. battery or drink brands), and any app stamp or text boxes.
If no shop name is clearly readable, return an empty name.
Reply with JSON only: {"name": "..."}"""

    private val pool = Executors.newCachedThreadPool()

    @Volatile private var cronet: CronetEngine? = null
    @Volatile private var cronetTried = false

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
        val small = scaleDown(bmp, 768)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (small != bmp) small.recycle()
        bmp.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))

        return when (val r = call(ctx, k, parts, json = true)) {
            is Raw.Ok -> {
                val name = parseName(r.text)
                if (name.isNullOrBlank()) Result.NoName else Result.Name(name)
            }
            is Raw.Fail -> r.result
        }
    }

    /** Checks that a key works (text-only request). Call off the main thread. */
    fun test(ctx: Context, k: String): Result {
        if (k.isBlank()) return Result.NoKey
        val parts = JSONArray().put(JSONObject().put("text", "Reply with the single word OK"))
        return when (val r = call(ctx, k.trim(), parts, json = false)) {
            is Raw.Ok -> Result.Name(r.text.trim())
            is Raw.Fail -> r.result
        }
    }

    fun message(r: Result): String = when (r) {
        is Result.Name -> "✓ Read from signboard: please check"
        Result.NoName -> "No shop name found on the board"
        Result.NoKey -> "No Gemini key. Add it in ⋮ → Settings"
        is Result.BadKey -> "Gemini key not accepted: ${r.msg}"
        Result.Limit -> "Gemini free limit reached. Will try again later"
        Result.Busy -> "Gemini busy right now. Will try again later"
        Result.Slow -> "Gemini did not answer in time. Will try again later"
        is Result.Offline -> "Connection error: ${r.detail}"
        is Result.Error -> "Could not read name (${r.msg})"
    }

    // ---------- internals ----------

    private sealed class Raw {
        data class Ok(val text: String) : Raw()
        data class Fail(val result: Result) : Raw()
    }

    /** Chrome's network engine (QUIC) from Google Play Services; null if not available. */
    private fun engine(ctx: Context): CronetEngine? {
        if (cronetTried) return cronet
        synchronized(this) {
            if (cronetTried) return cronet
            cronet = try {
                Tasks.await(CronetProviderInstaller.installProvider(ctx.applicationContext), 10, TimeUnit.SECONDS)
                CronetEngine.Builder(ctx.applicationContext)
                    .enableQuic(true)
                    .enableHttp2(true)
                    .build()
            } catch (e: Throwable) {
                null
            }
            cronetTried = true
            return cronet
        }
    }

    private fun call(ctx: Context, k: String, parts: JSONArray, json: Boolean): Raw {
        val start = System.currentTimeMillis()
        var busy = false
        var limited = false
        var lastError: Raw? = null
        for (model in MODELS) {
            val left = TOTAL_MS - (System.currentTimeMillis() - start)
            if (left < 2000) return Raw.Fail(Result.Slow)
            var r = timed(left) { callModel(ctx, k, model, body(parts, json, model, thinkingOff = true)) }
            // a model that does not accept the "no thinking" setting: ask again without it
            if (r is Raw.Fail && r.result is Result.Error && (r.result as Result.Error).msg.contains("thinking", true)) {
                val left2 = TOTAL_MS - (System.currentTimeMillis() - start)
                if (left2 < 2000) return Raw.Fail(Result.Slow)
                r = timed(left2) { callModel(ctx, k, model, body(parts, json, model, thinkingOff = false)) }
            }
            when {
                r is Raw.Ok -> return r
                r is Raw.Fail && r.result is Result.Slow -> return r
                r is Raw.Fail && r.result is Result.Busy -> busy = true
                r is Raw.Fail && r.result is Result.Limit -> limited = true
                r is Raw.Fail && r.result is Result.Error && (r.result as Result.Error).msg.startsWith("HTTP 404") -> lastError = r
                else -> return r   // bad key, connection error, other error: other models won't help
            }
        }
        return when {
            busy -> Raw.Fail(Result.Busy)
            limited -> Raw.Fail(Result.Limit)
            else -> lastError ?: Raw.Fail(Result.Error("No Gemini model available"))
        }
    }

    private fun timed(ms: Long, block: () -> Raw): Raw {
        val f = pool.submit<Raw> { block() }
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            Raw.Fail(Result.Slow)
        } catch (e: Exception) {
            Raw.Fail(Result.Error(e.cause?.message ?: e.message ?: "error"))
        }
    }

    private fun body(parts: JSONArray, json: Boolean, model: String, thinkingOff: Boolean): JSONObject {
        val gen = JSONObject().put("temperature", 0)
        if (json) gen.put("responseMimeType", "application/json")
        if (thinkingOff) {
            // answer directly, without long "thinking"
            if (model.contains("2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            else gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun callModel(ctx: Context, k: String, model: String, body: JSONObject): Raw {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = (engine(ctx)?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", k)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            when {
                code in 200..299 -> Raw.Ok(extractText(text))
                code == 404 -> Raw.Fail(Result.Error("HTTP 404 model $model: ${errMsg(text)}"))
                code == 429 -> Raw.Fail(Result.Limit)
                code == 400 && text.contains("API_KEY", true) -> Raw.Fail(Result.BadKey(errMsg(text)))
                code == 401 || code == 403 -> Raw.Fail(Result.BadKey(errMsg(text)))
                code >= 500 -> Raw.Fail(Result.Busy)
                else -> Raw.Fail(Result.Error("HTTP $code: ${errMsg(text).ifBlank { text.take(200) }}"))
            }
        } catch (e: InterruptedException) {
            Raw.Fail(Result.Slow)
        } catch (e: IOException) {
            Raw.Fail(Result.Offline("${e.javaClass.simpleName}: ${e.message ?: "no details"}"))
        } catch (e: Exception) {
            Raw.Fail(Result.Error("${e.javaClass.simpleName}: ${e.message ?: "no details"}"))
        }
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
