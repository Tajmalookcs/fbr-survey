package com.tajmalook.fbrsurvey

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Arrays

/**
 * Offline CNIC -> registered + taxpayer name lookup, using FBR_Lookup.fbr
 * made on the PC by make_fbr_lookup.py.
 *
 * File: "FBRLKP2\n" | count:int64 | created:int64 | count x (hash:int64, offset:int32) sorted by hash
 *       | blob of (len:uint8, name bytes XOR key)
 * CNICs are stored only as hashes; each name is unlocked with its own CNIC.
 */
object FbrLookup {

    private const val MAGIC = "FBRLKP3\n"
    private const val HEADER = 24
    private val ID_SALT = "fbr-survey-id-v1|".toByteArray()

    class Result(val found: Boolean, val name: String, val business: String = "")
    class Info(val count: Long, val createdSec: Long)

    private var hashes: LongArray? = null
    private var offsets: IntArray? = null
    private var blobStart = 0L

    fun file(ctx: Context) = File(ctx.filesDir, "fbr_lookup.fbr")

    /** Count and creation time from the imported file, without loading it. */
    fun info(ctx: Context): Info? = try {
        DataInputStream(file(ctx).inputStream().buffered()).use { readHeader(it) }
    } catch (e: Exception) {
        null
    }

    private fun readHeader(input: DataInputStream): Info {
        val magic = ByteArray(8)
        input.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not an FBR lookup file (or made with an old script version)" }
        val count = input.readLong()
        val created = input.readLong()
        require(count in 1..50_000_000) { "Bad record count" }
        return Info(count, created)
    }

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (hashes != null) return true
        val f = file(ctx)
        if (!f.exists()) return false
        return try {
            DataInputStream(f.inputStream().buffered(1 shl 16)).use { input ->
                val info = readHeader(input)
                val n = info.count.toInt()
                val raw = ByteArray(n * 12)
                input.readFully(raw)
                val buf = ByteBuffer.wrap(raw)
                val h = LongArray(n)
                val o = IntArray(n)
                for (i in 0 until n) {
                    h[i] = buf.long
                    o[i] = buf.int
                }
                hashes = h
                offsets = o
                blobStart = HEADER + n * 12L
            }
            true
        } catch (e: Exception) {
            hashes = null; offsets = null
            false
        }
    }

    /** null = no FBR data imported. */
    @Synchronized
    fun lookup(ctx: Context, cnic13: String): Result? {
        if (!load(ctx)) return null
        val h = hashes!!
        val c = cnic13.toByteArray()
        val idx = Arrays.binarySearch(h, ByteBuffer.wrap(sha256(ID_SALT, c), 0, 8).long)
        if (idx < 0) return Result(false, "")
        return try {
            RandomAccessFile(file(ctx), "r").use { raf ->
                raf.seek(blobStart + offsets!![idx].toLong())
                val len = raf.readUnsignedByte()
                val enc = ByteArray(len)
                raf.readFully(enc)
                val ks = (0 until 4).fold(ByteArray(0)) { acc, i ->
                    acc + sha256("fbr-survey-name-${'a' + i}|".toByteArray(), c)
                }
                for (i in 0 until len) enc[i] = (enc[i].toInt() xor ks[i].toInt()).toByte()
                val parts = String(enc, Charsets.UTF_8).split("\n", limit = 2)
                Result(true, parts[0].trim(), parts.getOrElse(1) { "" }.trim())
            }
        } catch (e: Exception) {
            Result(true, "")
        }
    }

    /** Copies a picked file into private storage after checking it. Returns the CNIC count. */
    @Synchronized
    fun import(ctx: Context, uri: Uri): Long {
        val tmp = File(ctx.filesDir, "fbr_lookup.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        try {
            val info = DataInputStream(tmp.inputStream().buffered()).use { readHeader(it) }
            require(tmp.length() >= HEADER + info.count * 12) { "File is incomplete" }
            hashes = null; offsets = null
            if (!tmp.renameTo(file(ctx))) {
                tmp.copyTo(file(ctx), overwrite = true)
            }
            require(load(ctx)) { "Could not read file" }
            return info.count
        } finally {
            tmp.delete()
        }
    }

    private fun sha256(salt: ByteArray, data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { update(salt); update(data); digest() }
}
