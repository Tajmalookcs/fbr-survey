package com.tajmalook.fbrsurvey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** Fills missing address / real map from the SAVED coordinates and redraws the stamp. */
object Restamp {

    enum class Result { RESTAMPED, RESTORED, NO_ORIGINAL, FAILED, IMPORTED }

    /**
     * Needs internet. Looks up the address (only if empty) and the real map (only if the
     * stamp has the offline placeholder) for the record's saved lat/lng.
     * Coordinates, date and time are never changed. Returns true if anything new was found.
     */
    fun fillFromSavedLocation(ctx: Context, r: Record): Boolean {
        var changed = fillImported(ctx, r) == true
        changed = (fillNameFromBoard(ctx, r) == true) || changed
        if (r.lat == 0.0 && r.lng == 0.0) return changed   // saved without GPS
        if (r.mapAddress.isBlank()) {
            Geo.address(ctx, r.lat, r.lng)?.let {
                r.mapAddress = it                       // stamp address (real, from GPS)
                if (r.address.isBlank()) r.address = it // form/Excel address only if empty
                changed = true
            }
        }
        if (!r.mapReal || (r.isImported() && r.addStamp && !Store.mapFile(ctx, r.id).exists())) {
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

    /**
     * Imported photo still "pending": read coordinates / date (and name) from the stamp on the image.
     * true = something filled, false = nothing to do, null = try again later.
     */
    fun fillImported(ctx: Context, r: Record): Boolean? {
        if (r.source != "pending" || !Gemini.hasKey(ctx)) return false
        val raw = Store.rawPhoto(ctx, r.id)
        if (!raw.exists()) return false
        return when (val res = Gemini.readStamp(ctx, raw)) {
            is Gemini.Result.StampData -> {
                var changed = false
                if (res.lat != null && res.lng != null && !r.coordsManual && r.lat == 0.0 && r.lng == 0.0) {
                    r.lat = res.lat; r.lng = res.lng; r.mapAddress = ""; changed = true
                }
                if (res.time != null && r.photoTime == 0L) { r.photoTime = res.time; changed = true }
                if (res.name.isNotBlank() && r.businessName.isBlank()) { r.businessName = res.name; changed = true }
                r.source = if (res.lat != null || res.time != null) "stamp" else "photo"
                changed = true
                changed
            }
            Gemini.Result.NoName -> { r.source = "photo"; true }   // nothing on the image: stop waiting
            Gemini.Result.NoKey, is Gemini.Result.BadKey -> false
            else -> null
        }
    }

    /**
     * Empty business name + photo + Gemini key: read the name from the signboard.
     * true = name filled, false = nothing to do / no name on board, null = try again later.
     */
    fun fillNameFromBoard(ctx: Context, r: Record): Boolean? {
        if (r.businessName.isNotBlank() || !Gemini.hasKey(ctx)) return false
        val raw = Store.rawPhoto(ctx, r.id)
        if (!raw.exists()) return false
        return when (val res = Gemini.readName(ctx, raw)) {
            is Gemini.Result.Name -> {
                var changed = false
                if (res.name.isNotBlank()) { r.businessName = res.name; changed = true }
                if (r.phone.isBlank() && res.phones.isNotEmpty()) { r.phone = res.phones.joinToString("\n"); changed = true }
                if (r.ownerName.isBlank() && res.owner.isNotBlank()) { r.ownerName = res.owner; r.ownerFromBoard = true; changed = true }
                changed
            }
            Gemini.Result.NoName, Gemini.Result.NoKey, is Gemini.Result.BadKey -> false
            else -> null
        }
    }

    /** Redraws the stamp on the clean copy and updates (or restores) the gallery photo. Saves the record. */
    fun redraw(ctx: Context, r: Record): Result {
        val raw = Store.rawPhoto(ctx, r.id)
        if (!raw.exists()) { Store.upsert(ctx, r); return Result.NO_ORIGINAL }
        return try {
            val photo = Photo.applyCrop(Photo.loadUpright(raw), r.crop)
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
