package com.tajmalook.fbrsurvey

import kotlin.math.floor

/** Open Location Code (Google Plus Code), 11 digits (~3 m), computed offline. e.g. 8J3J3X49+WPM */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"

    fun encode(latitude: Double, longitude: Double): String {
        val lat = latitude.coerceIn(-90.0, 89.9999999)
        var lng = (longitude + 180.0) % 360.0
        if (lng < 0) lng += 360.0
        // units of 1/8000 degree, refined by the 5x4 grid of the 11th digit
        val latV = floor((lat + 90.0) * 8000.0 * 5.0).toLong()
        val lngV = floor(lng * 8000.0 * 4.0).toLong()
        val la = latV / 5
        val lo = lngV / 4
        val sb = StringBuilder()
        for (place in longArrayOf(160000, 8000, 400, 20, 1)) {
            sb.append(ALPHABET[((la / place) % 20).toInt()])
            sb.append(ALPHABET[((lo / place) % 20).toInt()])
        }
        sb.append(ALPHABET[((latV % 5) * 4 + (lngV % 4)).toInt()])
        sb.insert(8, '+')
        return sb.toString()
    }
}
