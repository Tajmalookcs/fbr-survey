package com.tajmalook.fbrsurvey

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object Photo {

    const val ALBUM = "FBR Survey"

    fun stampDate(time: Long): String =
        SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH).format(Date(time))
            .replace("AM", "am").replace("PM", "pm")

    /** Loads the camera file, scaled down to a safe size and turned upright. */
    fun loadUpright(file: File, maxSide: Int = 2560): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not read photo")

        val rotation = when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        var out = bmp
        if (rotation != 0f) {
            out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            if (out != bmp) bmp.recycle()
        }
        val longSide = max(out.width, out.height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(out, (out.width * s).toInt(), (out.height * s).toInt(), true)
            if (scaled != out) out.recycle()
            out = scaled
        }
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    /**
     * Draws the bottom stamp like the reference: map thumbnail on the left,
     * address + coordinates (white) and date/time (yellow) on the right,
     * over a semi-transparent black strip.
     */
    fun stamp(photo: Bitmap, map: Bitmap, address: String?, lat: Double, lng: Double, time: Long): Bitmap {
        val w = photo.width
        val h = photo.height
        val unit = min(w, h).toFloat()
        val pad = unit * 0.025f
        val mapSize = unit * 0.25f
        val textLeft = pad * 2 + mapSize
        val textWidth = (w - textLeft - pad).toInt()

        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.042f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }
        val small = TextPaint(big).apply { textSize = unit * 0.032f; color = Color.rgb(230, 230, 230) }
        val yellow = TextPaint(big).apply { color = Color.rgb(255, 214, 0) }

        val coords = String.format(Locale.ENGLISH, "Lat %.6f°  Long %.6f°", lat, lng)
        val lines = mutableListOf<StaticLayout>()
        if (!address.isNullOrBlank()) lines += layout(address, big, textWidth, 3)
        lines += layout(coords, small, textWidth, 1)
        lines += layout(stampDate(time), yellow, textWidth, 1)

        val gap = unit * 0.012f
        val textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
        val stripH = max(mapSize + pad * 2, textHeight + pad * 2)
        val stripTop = h - stripH

        val c = Canvas(photo)
        c.drawRect(0f, stripTop, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(165, 0, 0, 0) })

        // map thumbnail with white border
        val mapTop = stripTop + (stripH - mapSize) / 2
        val border = unit * 0.004f
        c.drawRect(pad - border, mapTop - border, pad + mapSize + border, mapTop + mapSize + border,
            Paint().apply { color = Color.WHITE })
        c.drawBitmap(map, null, RectF(pad, mapTop, pad + mapSize, mapTop + mapSize),
            Paint(Paint.FILTER_BITMAP_FLAG))

        var y = stripTop + (stripH - textHeight) / 2
        for (l in lines) {
            c.save()
            c.translate(textLeft, y)
            l.draw(c)
            c.restore()
            y += l.height + gap
        }
        return photo
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    /** Saves the finished JPEG into Pictures/FBR Survey so it shows in the gallery. Returns its Uri. */
    fun saveToGallery(ctx: Context, bmp: Bitmap, name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create gallery file")
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
            return Uri.fromFile(f)
        }
    }

    /** Small thumbnail for the list screen. */
    fun thumb(ctx: Context, uri: Uri, size: Int = 200): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            ctx.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (min(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }
    } catch (e: Exception) {
        null
    }

    fun safeFileName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40)
}
