package com.tajmalook.fbrsurvey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Geocoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object Geo {

    private val plusCode = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,3}$", RegexOption.IGNORE_CASE)

    /**
     * Most detailed readable address for a point (road, mohalla/area, village/town, city,
     * district, province, country), combining Android's geocoder (several results) and
     * OpenStreetMap's reverse geocoder. No Plus Code, no accent marks, country last.
     * Returns null when offline or nothing is found. Call off the main thread.
     */
    fun address(ctx: Context, lat: Double, lng: Double): String? {
        val specific = mutableListOf<String>()   // road, area, mohalla, village...
        var city: String? = null
        var district: String? = null
        var province: String? = null
        var country: String? = null

        // 1) OpenStreetMap (usually knows local roads / areas better)
        osm(lat, lng)?.let { o ->
            listOf("road", "neighbourhood", "quarter", "residential", "hamlet", "suburb", "village")
                .forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { specific += it } }
            city = listOf("town", "city", "municipality").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
            district = listOf("state_district", "county").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
            province = o.optString("state").takeIf { it.isNotBlank() }
            country = o.optString("country").takeIf { it.isNotBlank() }
        }

        // 2) Android geocoder: look at up to 5 results and take every detail they offer
        if (Geocoder.isPresent()) try {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lng, 5).orEmpty()
            for (a in list) {
                listOf(a.thoroughfare, a.subLocality, a.featureName).forEach { v ->
                    if (v != null && v.isNotBlank() && !v.contains("+") && !v.all { it.isDigit() || it == ' ' }) specific += v
                }
                if (city == null) city = a.locality
                if (district == null) district = a.subAdminArea
                if (province == null) province = a.adminArea
                if (country == null) country = a.countryName
            }
        } catch (e: Exception) { }

        val parts = mutableListOf<String>()
        fun add(v: String?) {
            val t = clean(v) ?: return
            val low = t.lowercase(Locale.ENGLISH)
            if (parts.any { it.lowercase(Locale.ENGLISH) == low }) return
            parts += t
        }
        specific.forEach { add(it) }
        add(city); add(district); add(province)
        // country always last
        clean(country)?.let { c -> parts.removeAll { it.equals(c, true) }; parts += c }
        // drop pieces that are only the city/country (e.g. street named "Tandlianwala")
        return parts.joinToString(", ").ifBlank { null }
    }

    /** Number of address parts, used to compare how detailed two addresses are. */
    fun detail(address: String?): Int = address?.split(",")?.count { it.isNotBlank() } ?: 0

    private fun clean(v: String?): String? {
        if (v.isNullOrBlank()) return null
        var t = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("\\s+(District|Tehsil|Division)$", RegexOption.IGNORE_CASE), "")
        if (t.contains("+") || t.isBlank() || t.all { it.isDigit() || it == ' ' }) return null
        return t
    }

    private fun osm(lat: Double, lng: Double): org.json.JSONObject? = try {
        val url = URL(String.format(Locale.ENGLISH,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.7f&lon=%.7f&zoom=18&addressdetails=1&accept-language=en",
            lat, lng))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "FBRShopSurvey/1.0 (Android field survey app)")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        org.json.JSONObject(body).optJSONObject("address")
    } catch (e: Exception) {
        null
    }

    /**
     * Square map thumbnail centred on the point, with a red pin.
     * Uses OpenStreetMap tiles when online; draws a plain placeholder map when offline.
     */
    /** bitmap + whether it contains real map tiles (false = offline placeholder). */
    class MapResult(val bitmap: Bitmap, val real: Boolean)

    fun mapThumb(lat: Double, lng: Double, size: Int = 512, online: Boolean = true): Bitmap =
        mapThumbResult(lat, lng, size, online).bitmap

    fun mapThumbResult(lat: Double, lng: Double, size: Int = 512, online: Boolean = true): MapResult {
        val zoom = 17
        val n = 1 shl zoom
        val xf = (lng + 180.0) / 360.0 * n
        val latRad = lat * PI / 180.0
        val yf = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n

        // Point position in global pixels (256px tiles)
        val px = xf * 256
        val py = yf * 256
        val left = px - 128
        val top = py - 128

        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        var gotAny = false
        val tx0 = floor(left / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        if (online) for (tx in tx0..tx0 + 1) for (ty in ty0..ty0 + 1) {
            val tile = fetchTile(zoom, tx, ty) ?: continue
            gotAny = true
            c.drawBitmap(tile, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
            tile.recycle()
        }
        if (!gotAny) drawPlaceholder(c)

        val scaled = Bitmap.createScaledBitmap(out, size, size, true)
        if (scaled != out) out.recycle()
        drawPin(Canvas(scaled), size)
        if (gotAny) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 60, 60, 60); textSize = size * 0.045f
            }
            val label = "© OpenStreetMap"
            val bg = Paint().apply { color = Color.argb(170, 255, 255, 255) }
            val w = p.measureText(label)
            Canvas(scaled).apply {
                drawRect(size - w - size * 0.03f, size - size * 0.07f, size.toFloat(), size.toFloat(), bg)
                drawText(label, size - w - size * 0.015f, size - size * 0.02f, p)
            }
        }
        return MapResult(scaled, gotAny)
    }

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? = try {
        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "FBRShopSurvey/1.0 (Android field survey app)")
        conn.inputStream.use { BitmapFactory.decodeStream(it) }.also { conn.disconnect() }
    } catch (e: Exception) {
        null
    }

    private fun drawPlaceholder(c: Canvas) {
        c.drawColor(Color.rgb(236, 238, 234))
        val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 14f }
        val block = Paint().apply { color = Color.rgb(222, 226, 218) }
        for (i in 0..3) for (j in 0..3) {
            c.drawRect(i * 70f + 8, j * 70f + 8, i * 70f + 58, j * 70f + 58, block)
        }
        c.drawLine(0f, 180f, 256f, 120f, road)
        c.drawLine(90f, 0f, 150f, 256f, road)
    }

    private fun drawPin(c: Canvas, size: Int) {
        val cx = size / 2f
        val tipY = size / 2f
        val r = size * 0.11f
        val headY = tipY - r * 2.1f
        val path = Path().apply {
            moveTo(cx, tipY)
            cubicTo(cx - r * 0.6f, tipY - r * 1.0f, cx - r, headY + r * 0.6f, cx - r, headY)
            arcTo(cx - r, headY - r, cx + r, headY + r, 180f, 180f, false)
            cubicTo(cx + r, headY + r * 0.6f, cx + r * 0.6f, tipY - r * 1.0f, cx, tipY)
            close()
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        c.drawOval(cx - r * 0.5f, tipY - r * 0.15f, cx + r * 0.5f, tipY + r * 0.15f, shadow)
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(234, 67, 53) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(165, 32, 25); style = Paint.Style.STROKE; strokeWidth = size * 0.008f
        })
        c.drawCircle(cx, headY, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 32, 25) })
    }
}
