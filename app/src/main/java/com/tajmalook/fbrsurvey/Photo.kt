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
    /** Applies a crop spec "rot;l,t,r,b" (fractions of the rotated photo). Returns a new bitmap (or the same if none). */
    fun applyCrop(src: Bitmap, spec: String): Bitmap {
        if (spec.isBlank()) return src
        return try {
            val (rotS, rectS) = spec.split(";")
            val rot = rotS.toFloat()
            var b = src
            if (rot != 0f) {
                b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot) }, true)
            }
            val f = rectS.split(",").map { it.toFloat() }
            val x = (f[0] * b.width).toInt().coerceIn(0, b.width - 1)
            val y = (f[1] * b.height).toInt().coerceIn(0, b.height - 1)
            val cw = ((f[2] - f[0]) * b.width).toInt().coerceIn(1, b.width - x)
            val ch = ((f[3] - f[1]) * b.height).toInt().coerceIn(1, b.height - y)
            val out = Bitmap.createBitmap(b, x, y, cw, ch)
            val res = if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true)
            if (b != src && b != res) b.recycle()
            if (src != res) src.recycle()
            res
        } catch (e: Exception) {
            src
        }
    }

    /** White details box (+ optional label line). */
    private fun detailsBox(r: Record, unit: Float, boxTextW: Int, label: String?): List<StaticLayout> {
        val dark = Color.rgb(25, 25, 25)
        val boxTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            textSize = unit * 0.030f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val boxText = TextPaint(boxTitle).apply { textSize = unit * 0.026f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        val boxLines = mutableListOf<StaticLayout>()
        if (r.businessName.isNotBlank()) boxLines += layout("Business: ${r.businessName}", boxTitle, boxTextW, 2)
        val ownerReg = android.text.SpannableStringBuilder()
        if (r.ownerName.isNotBlank()) ownerReg.append("Owner: ${r.ownerName}  |  ")
        val regStart = ownerReg.length
        ownerReg.append(if (r.fbrRegistered) "Reg: YES" else "Reg: NO")
        ownerReg.setSpan(android.text.style.StyleSpan(Typeface.BOLD), regStart, ownerReg.length, 0)
        ownerReg.setSpan(android.text.style.ForegroundColorSpan(
            if (r.fbrRegistered) Color.rgb(20, 140, 60) else Color.rgb(200, 30, 30)), regStart, ownerReg.length, 0)
        boxLines += layout(ownerReg, boxText, boxTextW, 2)
        val idLine = listOfNotNull(
            if (r.cnicNotProvided) "CNIC: Not provided" else r.cnic.takeIf { it.isNotBlank() }?.let { "CNIC: $it" },
            r.phone.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.let { "Cell: $it" },
        ).joinToString("  |  ")
        if (idLine.isNotEmpty()) boxLines += layout(idLine, boxText, boxTextW, 2)
        if (label != null) {
            val lp = TextPaint(boxText).apply {
                textSize = unit * 0.022f; color = Color.rgb(90, 90, 90)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            }
            boxLines += layout(label, lp, boxTextW, 1)
        }
        return boxLines
    }

    /**
     * Imported photo (already has another app's stamp): only the white details box, at the
     * bottom-left but raised by about a quarter of the photo height, so the other app's stamp stays visible.
     */
    private fun stampImported(photo: Bitmap, r: Record): Bitmap {
        val w = photo.width
        val unit = min(w, photo.height).toFloat()
        val pad = unit * 0.018f
        val inner = unit * 0.014f
        val lines = detailsBox(r, unit, (w - pad * 2 - inner * 2).toInt(), null)
        val gap = unit * 0.004f
        val boxH = lines.sumOf { it.height } + gap * (lines.size - 1) + inner * 2
        val contentW = lines.maxOf { l -> (0 until l.lineCount).maxOf { l.getLineWidth(it) } }
        val boxW = min(contentW + inner * 2, w - pad * 2)
        val c = Canvas(photo)
        val bottom = photo.height * 0.75f                      // free space below for the other app's stamp
        val top = max(pad, bottom - boxH)
        c.drawRoundRect(pad, top, pad + boxW, top + boxH, unit * 0.015f, unit * 0.015f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) })
        var y = top + inner
        for (l in lines) {
            c.save(); c.translate(pad + inner, y); l.draw(c); c.restore()
            y += l.height + gap
        }
        return photo
    }

    fun stamp(photo: Bitmap, map: Bitmap, r: Record): Bitmap {
        if (r.isImported()) return stampImported(photo, r)
        val w = photo.width
        val h = photo.height
        val unit = min(w, h).toFloat()
        val pad = unit * 0.018f
        val mapSize = unit * 0.19f
        val textLeft = pad * 2 + mapSize
        val textWidth = (w - textLeft - pad).toInt()

        // ---- bottom strip text
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.031f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }
        val small = TextPaint(big).apply { textSize = unit * 0.024f; color = Color.rgb(225, 225, 225) }
        val yellow = TextPaint(big).apply { color = Color.rgb(255, 214, 0) }

        val plus = PlusCode.encode(r.lat, r.lng)
        val shownAddress = if (r.stampUseManual && r.address.isNotBlank()) r.address else r.mapAddress
        val addressLine = if (shownAddress.isNotBlank()) "${plus.substring(4)}, $shownAddress" else plus
        val lines = mutableListOf<StaticLayout>()
        lines += layout(addressLine, big, textWidth, 3, center = true)
        lines += layout(String.format(Locale.ENGLISH, "Lat %.6f°  Long %.6f°", r.lat, r.lng), small, textWidth, 1, center = true)
        val dateLayout = layout(stampDate(r.createdAt), yellow, textWidth, 1, center = true)
        lines += dateLayout

        val gap = unit * 0.008f
        val textHeight = lines.sumOf { it.height } + gap * (lines.size - 1)
        val stripH = max(mapSize + pad * 2, textHeight + pad * 2)
        val stripTop = h - stripH

        // ---- white box text (compact)
        val dark = Color.rgb(25, 25, 25)
        val boxTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            textSize = unit * 0.030f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val boxText = TextPaint(boxTitle).apply { textSize = unit * 0.026f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        val boxX = pad
        val inner = unit * 0.014f
        val boxTextW = (w - pad * 2 - inner * 2).toInt()
        val boxLines = mutableListOf<StaticLayout>()
        if (r.businessName.isNotBlank()) boxLines += layout("Business: ${r.businessName}", boxTitle, boxTextW, 2)

        // Owner: NAME  |  Reg: YES   (Reg in bold green / red)
        val ownerReg = android.text.SpannableStringBuilder()
        if (r.ownerName.isNotBlank()) ownerReg.append("Owner: ${r.ownerName}  |  ")
        val regStart = ownerReg.length
        ownerReg.append(if (r.fbrRegistered) "Reg: YES" else "Reg: NO")
        ownerReg.setSpan(android.text.style.StyleSpan(Typeface.BOLD), regStart, ownerReg.length, 0)
        ownerReg.setSpan(android.text.style.ForegroundColorSpan(
            if (r.fbrRegistered) Color.rgb(20, 140, 60) else Color.rgb(200, 30, 30)), regStart, ownerReg.length, 0)
        boxLines += layout(ownerReg, boxText, boxTextW, 2)

        val idLine = listOfNotNull(
            if (r.cnicNotProvided) "CNIC: Not provided" else r.cnic.takeIf { it.isNotBlank() }?.let { "CNIC: $it" },
            r.phone.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.let { "Cell: $it" },
        ).joinToString("  |  ")
        if (idLine.isNotEmpty()) boxLines += layout(idLine, boxText, boxTextW, 2)
        val boxGap = unit * 0.004f
        val boxH = boxLines.sumOf { it.height } + boxGap * (boxLines.size - 1) + inner * 2
        // box only as wide as its text
        val contentW = boxLines.maxOf { l -> (0 until l.lineCount).maxOf { l.getLineWidth(it) } }
        val boxW = min(contentW + inner * 2, w - pad * 2)
        val boxTop = stripTop - unit * 0.012f - boxH

        val c = Canvas(photo)

        // white box
        c.drawRoundRect(boxX, boxTop, boxX + boxW, boxTop + boxH, unit * 0.015f, unit * 0.015f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) })
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
                val bx = unit * 0.015f
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

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int, center: Boolean = false): StaticLayout =
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

    /** True if the gallery photo still exists (not deleted or moved to the gallery's trash). */
    fun galleryExists(ctx: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "file") File(uri.path!!).exists()
        else ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    /** Picture for the app's screens: the gallery photo, or the app's own clean copy if it was deleted. */
    fun thumbFor(ctx: Context, r: Record, size: Int = 200): Bitmap? {
        val uri = Uri.parse(r.photoUri)
        if (r.photoUri.isNotBlank() && galleryExists(ctx, uri)) thumb(ctx, uri, size)?.let { return it }
        val raw = Store.rawPhoto(ctx, r.id)
        if (!raw.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.absolutePath, o)
            var s = 1
            while (min(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
            BitmapFactory.decodeFile(raw.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) {
            null
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
