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
     * Full readable address (street, area, city, district, province, country) without the
     * Plus Code. Returns null when offline or when nothing is found. Call off the main thread.
     */
    @Suppress("DEPRECATION")
    fun address(ctx: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val list = Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lng, 1)
            val a = list?.firstOrNull() ?: return null

            // Prefer the full address line, minus any Plus Code part.
            val line = a.getAddressLine(0)
            val fromLine = line?.split(",")
                ?.map { part ->
                    // "3X49+WPM Ghausia Colony" style -> drop the Plus Code word
                    val t = part.trim()
                    val first = t.split(" ").first()
                    if (plusCode.matches(first) || first.contains("+")) t.removePrefix(first).trim() else t
                }
                ?.filter { it.isNotEmpty() }

            val parts = mutableListOf<String>()
            fun add(s: String?) {
                val t = s?.trim().orEmpty()
                if (t.isEmpty() || t.contains("+")) return
                if (parts.none { it.equals(t, ignoreCase = true) }) parts.add(t)
            }

            if (!fromLine.isNullOrEmpty()) {
                fromLine.forEach { add(it) }
                // Make sure district & province are present even if the line skips them.
                if (parts.none { it.equals(a.subAdminArea, true) } && a.subAdminArea != null) {
                    val idx = parts.indexOfFirst { it.equals(a.adminArea, true) }
                    if (idx >= 0) parts.add(idx, a.subAdminArea) else add(a.subAdminArea)
                }
            } else {
                add(listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" "))
                add(a.subLocality)
                add(a.locality)
                add(a.subAdminArea)
                add(a.adminArea)
                add(a.countryName)
            }
            parts.joinToString(", ").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Square map thumbnail centred on the point, with a red pin.
     * Uses OpenStreetMap tiles when online; draws a plain placeholder map when offline.
     */
    fun mapThumb(lat: Double, lng: Double, size: Int = 512, online: Boolean = true): Bitmap {
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
        return scaled
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
