package com.tajmalook.fbrsurvey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Record(
    val id: Long,
    var createdAt: Long,
    var surveyor: String,
    var formation: String,
    var area: String,
    var market: String,
    var businessName: String,
    var ownerName: String,
    var cnic: String,
    var phone: String,
    var address: String,
    var fbrRegistered: Boolean,
    var lat: Double,
    var lng: Double,
    var accuracy: Float,
    var photoUri: String,
    var photoName: String,
    var cnicNotProvided: Boolean = false,
    var boardPhone: String = "",
    var mapReal: Boolean = false,
    /** Address found from GPS (used ONLY on the photo stamp). `address` is the form/Excel address. */
    var mapAddress: String = "",
    /** true = photo stamp shows the typed address instead of the real GPS address. */
    var stampUseManual: Boolean = false,
    /** "camera" (app camera) | "stamp" / "photo" / "pending" (imported from gallery) */
    var source: String = "camera",
    /** imported photos: date/time read from the image (0 = not found) */
    var photoTime: Long = 0L,
    /** imported photos: coordinates typed/corrected by hand */
    var coordsManual: Boolean = false,
    /** crop of the clean photo: "rot;left,top,right,bottom" (fractions), "" = none */
    var crop: String = "",
    /** imported photos: the original picked from the gallery (never changed) */
    var origUri: String = "",
) {
    fun isImported() = source == "stamp" || source == "photo" || source == "pending"

    /** Date shown in list / Excel: imported photos use the date read from the image (0 = unknown). */
    fun shownTime(): Long = if (isImported()) photoTime else createdAt

    fun sourceLabel(): String = when (source) {
        "stamp" -> "Imported (read from stamp)"
        "photo" -> "Imported (photo data)"
        "pending" -> "Imported (pending)"
        else -> "App camera"
    } + if (isImported() && coordsManual) " (coordinates entered manually)" else ""

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("surveyor", surveyor)
        put("formation", formation)
        put("area", area)
        put("market", market)
        put("businessName", businessName)
        put("ownerName", ownerName)
        put("cnic", cnic)
        put("phone", phone)
        put("address", address)
        put("fbrRegistered", fbrRegistered)
        put("lat", lat)
        put("lng", lng)
        put("accuracy", accuracy.toDouble())
        put("photoUri", photoUri)
        put("photoName", photoName)
        put("cnicNotProvided", cnicNotProvided)
        put("boardPhone", boardPhone)
        put("mapReal", mapReal)
        put("mapAddress", mapAddress)
        put("stampUseManual", stampUseManual)
        put("source", source)
        put("photoTime", photoTime)
        put("coordsManual", coordsManual)
        put("crop", crop)
        put("origUri", origUri)
    }

    companion object {
        fun fromJson(o: JSONObject) = parse(o).apply {
            // imports saved by v1.0.33–v1.0.38 pointed at the original photo: make a separate copy instead
            if (isImported() && !o.has("origUri") && photoUri.isNotBlank() && !photoName.startsWith("FBR_")) {
                origUri = photoUri
                photoUri = ""
                photoName = "FBR_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ENGLISH)
                    .format(java.util.Date(id)) + "_" + Photo.safeFileName(businessName) + ".jpg"
            }
        }

        private fun parse(o: JSONObject) = Record(
            id = o.getLong("id"),
            createdAt = o.getLong("createdAt"),
            surveyor = o.optString("surveyor"),
            formation = o.optString("formation"),
            area = o.optString("area"),
            market = o.optString("market"),
            businessName = o.optString("businessName"),
            ownerName = o.optString("ownerName"),
            cnic = o.optString("cnic"),
            phone = o.optString("phone"),
            address = o.optString("address"),
            fbrRegistered = o.optBoolean("fbrRegistered"),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            accuracy = o.optDouble("accuracy", 0.0).toFloat(),
            photoUri = o.optString("photoUri"),
            photoName = o.optString("photoName"),
            cnicNotProvided = o.optBoolean("cnicNotProvided"),
            boardPhone = o.optString("boardPhone"),
            mapReal = o.optBoolean("mapReal"),
            // older records: fall back to the form address once
            stampUseManual = o.optBoolean("stampUseManual"),
            source = o.optString("source", "camera").ifBlank { "camera" },
            photoTime = o.optLong("photoTime", 0L),
            coordsManual = o.optBoolean("coordsManual"),
            crop = o.optString("crop"),
            origUri = o.optString("origUri"),
            mapAddress = if (o.has("mapAddress")) o.optString("mapAddress") else o.optString("address"),
        )
    }
}

/** Saves all survey records as one JSON file inside the app's private storage. */
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "records.json")

    @Synchronized
    fun all(ctx: Context): MutableList<Record> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { Record.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(ctx: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "records.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    @Synchronized
    fun upsert(ctx: Context, r: Record) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list.add(0, r)
        write(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        write(ctx, all(ctx).filter { it.id != id })
        rawPhoto(ctx, id).delete()
        mapFile(ctx, id).delete()
    }

    /** Unstamped original photo, kept so the stamp can be redrawn after edits. */
    fun rawPhoto(ctx: Context, id: Long) = File(File(ctx.filesDir, "raw").apply { mkdirs() }, "$id.jpg")

    /** Map thumbnail used in the stamp. */
    fun mapFile(ctx: Context, id: Long) = File(File(ctx.filesDir, "raw").apply { mkdirs() }, "$id-map.png")

    fun get(ctx: Context, id: Long): Record? = all(ctx).firstOrNull { it.id == id }
}
