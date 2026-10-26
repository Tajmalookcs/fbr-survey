package com.tajmalook.fbrsurvey

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Copies a photo picked from the gallery and reads the GPS / date saved inside it (EXIF). */
object Importer {

    class Exif(val lat: Double?, val lng: Double?, val time: Long?)

    /** Copies the original photo (with its location data when allowed) to [dest]. Call off the main thread. */
    fun copy(ctx: Context, uri: Uri, dest: File): Exif {
        val src = original(ctx, uri)
        val input = try { ctx.contentResolver.openInputStream(src) } catch (e: Exception) { null }
            ?: ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the photo")
        input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        return readExif(dest)
    }

    private fun original(ctx: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            uri.authority == MediaStore.AUTHORITY
        ) {
            return try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
        }
        return uri
    }

    fun readExif(file: File): Exif = try {
        val ex = ExifInterface(file.absolutePath)
        val ll = ex.latLong?.takeIf { !(it[0] == 0.0 && it[1] == 0.0) }
        val t = (ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME))
            ?.let {
                try { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ENGLISH).parse(it)?.time } catch (e: Exception) { null }
            }
        Exif(ll?.get(0), ll?.get(1), t)
    } catch (e: Exception) {
        Exif(null, null, null)
    }
}
