package com.tajmalook.fbrsurvey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** Fills missing address / real map from the SAVED coordinates and redraws the stamp. */
object Restamp {

    enum class Result { RESTAMPED, RESTORED, NO_ORIGINAL, FAILED }

    /**
     * Needs internet. Looks up the address (only if empty) and the real map (only if the
     * stamp has the offline placeholder) for the record's saved lat/lng.
     * Coordinates, date and time are never changed. Returns true if anything new was found.
     */
    fun fillFromSavedLocation(ctx: Context, r: Record): Boolean {
        if (r.lat == 0.0 && r.lng == 0.0) return false   // saved without GPS
        var changed = false
        if (r.mapAddress.isBlank()) {
            Geo.address(ctx, r.lat, r.lng)?.let {
                r.mapAddress = it                       // stamp address (real, from GPS)
                if (r.address.isBlank()) r.address = it // form/Excel address only if empty
                changed = true
            }
        }
        if (!r.mapReal) {
            val m = Geo.mapThumbResult(r.lat, r.lng)
            if (m.real) {
                Store.mapFile(ctx, r.id).outputStream().use { m.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                r.mapReal = true
                changed = true
            }
            m.bitmap.recycle()
        }
        return changed
    }

    /** Redraws the stamp on the clean copy and updates (or restores) the gallery photo. Saves the record. */
    fun redraw(ctx: Context, r: Record): Result {
        val raw = Store.rawPhoto(ctx, r.id)
        if (!raw.exists()) { Store.upsert(ctx, r); return Result.NO_ORIGINAL }
        return try {
            val photo = Photo.loadUpright(raw)
            val map = BitmapFactory.decodeFile(Store.mapFile(ctx, r.id).absolutePath)
                ?: Geo.mapThumb(r.lat, r.lng, online = false)
            Photo.stamp(photo, map, r)
            val uri = Uri.parse(r.photoUri)
            val res = if (r.photoUri.isNotBlank() && Photo.galleryExists(ctx, uri)) {
                if (Photo.overwriteGallery(ctx, uri, photo)) Result.RESTAMPED else Result.FAILED
            } else {
                r.photoUri = Photo.saveToGallery(ctx, photo, r.photoName).toString()
                Result.RESTORED
            }
            photo.recycle()
            Store.upsert(ctx, r)
            res
        } catch (e: Throwable) {
            Store.upsert(ctx, r)
            Result.FAILED
        }
    }
}
