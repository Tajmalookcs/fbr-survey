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
        /** name and/or cell numbers read from the signboard */
        data class Name(val name: String, val phones: List<String> = emptyList(), val owner: String = "") : Result()
        /** read from another GPS camera app's stamp; null = not found */
        data class StampData(val name: String, val lat: Double?, val lng: Double?, val time: Long?) : Result()
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
Read the signboard (Urdu and/or English) and reply with JSON only:
{"name": "...", "owner": "...", "phones": ["..."]}

name: the shop's business name, transliterated into English letters, in CAPITAL LETTERS, the way it is
usually written in English in Pakistan (e.g. ZAIN GENERAL STORE, MADINA KARYANA STORE,
AL-SHIFA MEDICAL STORE, HAJI MUHAMMAD ALI TRADERS). Ignore phone numbers, addresses, slogans,
product or brand advertisements (e.g. battery or drink brands), and any GPS app stamp or text boxes.

owner: the proprietor's personal name, in English CAPITAL LETTERS (e.g. HAJI MUHAMMAD ALI). Rules, in order:
 1. A name labelled Prop / Proprietor / پروپرائٹر / مالک → use it.
 2. Otherwise, if only one person's name appears on the board (even inside the shop name,
    e.g. "Haji Muhammad Ali Traders" → HAJI MUHAMMAD ALI) → use it.
 3. Otherwise, with several person names: take the first one in reading order of the board's main
    language: Urdu boards from the RIGHT side, English boards from the LEFT side.
 Leave "" if no person's name is clearly readable.

phones: mobile / phone numbers written on the board or shop front (at most 4), digits only,
e.g. "03001234567". Include a number ONLY if every digit is clearly readable; skip any number that is
blurry, cut off or uncertain. Never guess digits. Ignore numbers in a GPS app stamp or brand advertisements.

Use "" or [] for anything not clearly readable."""

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
        // sharper picture so small numbers / names are readable
        val b64 = encode(photoFile, 1600, 82) ?: return Result.Error("Could not open photo")

        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))

        return when (val r = call(ctx, k, parts, json = true)) {
            is Raw.Ok -> {
                val name = parseName(r.text).orEmpty()
                val phones = parsePhones(r.text)
                val owner = parseField(r.text, "owner")
                if (name.isBlank() && phones.isEmpty() && owner.isBlank()) Result.NoName else Result.Name(name, phones, owner)
            }
            is Raw.Fail -> r.result
        }
    }

    private const val STAMP_PROMPT = """This photo was taken with a GPS camera app. A stamp printed on the photo (usually at the
bottom) shows the latitude, longitude and the date and time when the photo was taken.
Read them EXACTLY as printed. Latitude is the north value (in Pakistan about 23 to 37),
longitude is the east value (in Pakistan about 60 to 78). Never guess digits that are not printed.
Also read the shop's business name from its signboard (Urdu or English), transliterated into English
CAPITAL LETTERS the way it is usually written in Pakistan (e.g. ZAIN GENERAL STORE); ignore phone
numbers, slogans, brand advertisements and the stamp text itself. If no shop name is readable, use "".
Reply with JSON only:
{"lat": number or null, "lng": number or null, "datetime": "YYYY-MM-DD HH:MM" (24-hour) or "", "name": "..."}"""

    /** Reads coordinates, date/time and name from another app's stamp on an imported photo. */
    fun readStamp(ctx: Context, photoFile: File): Result {
        val k = key(ctx)
        if (k.isEmpty()) return Result.NoKey
        if (!photoFile.exists()) return Result.Error("Photo not found")
        val b64 = encode(photoFile, 1600, 82) ?: return Result.Error("Could not open photo")
        val parts = JSONArray()
            .put(JSONObject().put("text", STAMP_PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        return when (val r = call(ctx, k, parts, json = true, totalMs = 45_000L)) {
            is Raw.Ok -> parseStamp(r.text)
            is Raw.Fail -> r.result
        }
    }

    private fun parseStamp(text: String): Result {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = try { JSONObject(t) } catch (e: Exception) { return Result.NoName }
        val la = if (o.isNull("lat")) null else o.optDouble("lat", Double.NaN).takeIf { !it.isNaN() && it in -90.0..90.0 && it != 0.0 }
        val ln = if (o.isNull("lng")) null else o.optDouble("lng", Double.NaN).takeIf { !it.isNaN() && it in -180.0..180.0 && it != 0.0 }
        val time = o.optString("datetime").trim().takeIf { it.isNotEmpty() }?.let { d ->
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).apply { isLenient = false }.parse(d)?.time
            } catch (e: Exception) { null }
        }
        val name = o.optString("name").replace(Regex("\\s+"), " ").trim().uppercase(Locale.ENGLISH).take(80)
        if (la == null && ln == null && time == null && name.isBlank()) return Result.NoName
        return Result.StampData(name, if (la != null && ln != null) la else null, if (la != null && ln != null) ln else null, time)
    }

    private fun encode(file: File, maxSide: Int, quality: Int): String? {
        val bmp = try { Photo.loadUpright(file, maxSide + 400) } catch (e: Exception) { return null }
        val small = scaleDown(bmp, maxSide)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (small != bmp) small.recycle()
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
        is Result.StampData -> "✓ Read from image: please check"
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

    private fun call(ctx: Context, k: String, parts: JSONArray, json: Boolean, totalMs: Long = TOTAL_MS): Raw {
        val start = System.currentTimeMillis()
        var busy = false
        var limited = false
        var lastError: Raw? = null
        for (model in MODELS) {
            val left = totalMs - (System.currentTimeMillis() - start)
            if (left < 2000) return Raw.Fail(Result.Slow)
            var r = timed(left) { callModel(ctx, k, model, body(parts, json, model, thinkingOff = true)) }
            // a model that does not accept the "no thinking" setting: ask again without it
            if (r is Raw.Fail && r.result is Result.Error && (r.result as Result.Error).msg.contains("thinking", true)) {
                val left2 = totalMs - (System.currentTimeMillis() - start)
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

    /** Cell numbers from the reply, cleaned: 0300-1234567 / +92 300 1234567 → 03001234567. */
    private fun parsePhones(text: String): List<String> {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val raw = mutableListOf<String>()
        try {
            val arr = JSONObject(t).optJSONArray("phones")
            if (arr != null) for (i in 0 until arr.length()) raw += arr.optString(i)
        } catch (e: Exception) { }
        return cleanPhones(raw)
    }

    /** Keeps only valid Pakistani numbers: mobile 03 + 9 digits, landline 0 + city code (10–11 digits). */
    fun cleanPhones(raw: List<String>): List<String> = raw.mapNotNull { s ->
        var d = s.filter { it.isDigit() }
        if (d.startsWith("0092")) d = "0" + d.substring(4)
        else if (d.startsWith("92") && d.length in 11..12) d = "0" + d.substring(2)
        else if (d.length == 10 && d.startsWith("3")) d = "0$d"
        d.takeIf { MOBILE.matches(it) || LANDLINE.matches(it) }
    }.distinct().take(4)

    private val MOBILE = Regex("03\\d{9}")
    private val LANDLINE = Regex("0[1-24-9]\\d{8,9}")

    private fun parseField(text: String, key: String): String {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val raw = try { JSONObject(t).optString(key) } catch (e: Exception) { "" }
        return raw.replace(Regex("\\s+"), " ").trim().uppercase(Locale.ENGLISH).take(60)
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
