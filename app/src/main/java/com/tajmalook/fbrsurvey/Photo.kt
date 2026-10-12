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
     * Draws the stamp like the reference:
     *  - one white box with the survey details (business, owner, CNIC, phone, FBR status),
     *    placed just above the strip;
     *  - a semi-transparent black strip with the map thumbnail on the left and
     *    Plus Code + address, coordinates and date/time (yellow on black) on the right.
     */
    fun stamp(photo: Bitmap, map: Bitmap, r: Record): Bitmap {
        val w = photo.width
        val h = photo.height
        val unit = min(w, h).toFloat()
        val pad = unit * 0.025f
        val mapSize = unit * 0.25f
        val textLeft = pad * 2 + mapSize
        val textWidth = (w - textLeft - pad).toInt()

        // ---- bottom strip text
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.042f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }
        val small = TextPaint(big).apply { textSize = unit * 0.031f; color = Color.rgb(225, 225, 225) }
        val yellow = TextPaint(big).apply { color = Color.rgb(255, 214, 0) }

        val plus = PlusCode.encode(r.lat, r.lng)
        val addressLine = if (r.address.isNotBlank()) "${plus.substring(4)}, ${r.address}" else plus
        val lines = mutableListOf<StaticLayout>()
        lines += layout(addressLine, big, textWidth, 3, center = true)
        lines += layout(String.format(Locale.ENGLISH, "Lat %.6f°  Long %.6f°", r.lat, r.lng), small, textWidth, 1, center = true)
        val dateLayout = layout(stampDate(r.createdAt), yellow, textWidth, 1, center = true)
        lines += dateLayout

        val gap = unit * 0.014f
        val textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
        val stripH = max(mapSize + pad * 2, textHeight + pad * 2)
        val stripTop = h - stripH

        // ---- white box text
        val dark = Color.rgb(25, 25, 25)
        val boxTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            textSize = unit * 0.044f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val boxText = TextPaint(boxTitle).apply { textSize = unit * 0.036f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        val boxStatus = TextPaint(boxTitle).apply {
            textSize = unit * 0.040f
            color = if (r.fbrRegistered) Color.rgb(20, 140, 60) else Color.rgb(200, 30, 30)
        }
        val boxX = pad
        val boxW = w - pad * 2
        val inner = unit * 0.025f
        val boxTextW = (boxW - inner * 2).toInt()
        val boxLines = mutableListOf<StaticLayout>()
        if (r.businessName.isNotBlank()) boxLines += layout("Business: ${r.businessName}", boxTitle, boxTextW, 2)
        if (r.ownerName.isNotBlank()) boxLines += layout("Owner: ${r.ownerName}", boxText, boxTextW, 2)
        val idLine = listOfNotNull(
            if (r.cnicNotProvided) "CNIC: Not provided" else r.cnic.takeIf { it.isNotBlank() }?.let { "CNIC: $it" },
            r.phone.takeIf { it.isNotBlank() }?.let { "Phone: $it" },
        ).joinToString("     ")
        if (idLine.isNotEmpty()) boxLines += layout(idLine, boxText, boxTextW, 2)
        boxLines += layout(if (r.fbrRegistered) "FBR Registered: YES" else "FBR Registered: NO", boxStatus, boxTextW, 1)
        val boxGap = unit * 0.006f
        val boxH = boxLines.sumOf { it.height } + boxGap * (boxLines.size - 1) + inner * 2
        val boxTop = stripTop - unit * 0.02f - boxH

        val c = Canvas(photo)

        // white box
        c.drawRoundRect(boxX, boxTop, boxX + boxW, boxTop + boxH, unit * 0.02f, unit * 0.02f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 255, 255, 255) })
        var by = boxTop + inner
        for (l in boxLines) {
            c.save(); c.translate(boxX + inner, by); l.draw(c); c.restore()
            by += l.height + boxGap
        }

        // black strip
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
            if (l === dateLayout) {
                // solid black box behind the date & time
                val bx = unit * 0.018f
                val byy = unit * 0.006f
                c.drawRoundRect(l.getLineLeft(0) - bx, -byy, l.getLineRight(0) + bx, l.height + byy,
                    unit * 0.008f, unit * 0.008f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
            l.draw(c)
            c.restore()
            y += l.height + gap
        }
        return photo
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, center: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
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

    /** Replaces the gallery photo's content after an edit. Returns false if the old file is gone. */
    fun overwriteGallery(ctx: Context, uri: Uri, bmp: Bitmap): Boolean = try {
        if (uri.scheme == "file") {
            FileOutputStream(File(uri.path!!)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(uri.path!!), arrayOf("image/jpeg"), null)
        } else {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
        true
    } catch (e: Exception) {
        false
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
