package com.tajmalook.fbrsurvey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Record(
    val id: Long,
    val createdAt: Long,
    var businessName: String,
    var cnic: String,
    var phone: String,
    var address: String,
    var fbrRegistered: Boolean,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val photoUri: String,
    val photoName: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("businessName", businessName)
        put("cnic", cnic)
        put("phone", phone)
        put("address", address)
        put("fbrRegistered", fbrRegistered)
        put("lat", lat)
        put("lng", lng)
        put("accuracy", accuracy.toDouble())
        put("photoUri", photoUri)
        put("photoName", photoName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Record(
            id = o.getLong("id"),
            createdAt = o.getLong("createdAt"),
            businessName = o.optString("businessName"),
            cnic = o.optString("cnic"),
            phone = o.optString("phone"),
            address = o.optString("address"),
            fbrRegistered = o.optBoolean("fbrRegistered"),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            accuracy = o.optDouble("accuracy", 0.0).toFloat(),
            photoUri = o.optString("photoUri"),
            photoName = o.optString("photoName"),
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
    }

    fun get(ctx: Context, id: Long): Record? = all(ctx).firstOrNull { it.id == id }
}
