package com.tajmalook.fbrsurvey

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(60)
    private var records: List<Record> = emptyList()
    private lateinit var adapter: RecordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.elevation = 0f

        adapter = RecordAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, FormActivity::class.java).putExtra(FormActivity.EXTRA_ID, records[pos].id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = records[pos]
            AlertDialog.Builder(this)
                .setTitle("Delete record?")
                .setMessage("${r.businessName}\n\nThe record will be removed from the app. The photo stays in your gallery.")
                .setPositiveButton("Delete") { _, _ -> Store.delete(this, r.id); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        findViewById<View>(R.id.btnNew).setOnClickListener {
            startActivity(Intent(this, FormActivity::class.java))
        }
        findViewById<View>(R.id.btnExport).setOnClickListener { export() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        records = Store.all(this)
        findViewById<TextView>(R.id.statTotal).text = records.size.toString()
        findViewById<TextView>(R.id.statYes).text = records.count { it.fbrRegistered }.toString()
        findViewById<TextView>(R.id.statNo).text = records.count { !it.fbrRegistered }.toString()
        findViewById<View>(R.id.emptyText).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ---------- Excel export ----------

    private fun export() {
        if (records.isEmpty()) {
            Toast.makeText(this, "No records to export yet", Toast.LENGTH_SHORT).show(); return
        }
        val data = records.sortedBy { it.createdAt }
        io.execute {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(Date())
                val name = "FBR_Survey_$stamp.xlsx"
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, name)

                val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
                val timeFmt = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
                val header = listOf("Sr", "Date", "Time", "Business Name", "CNIC", "Phone", "Business Address",
                    "FBR Registered", "Latitude", "Longitude", "Google Maps Link", "Photo File")
                val rows = data.mapIndexed { i, r ->
                    listOf(
                        i + 1,
                        dateFmt.format(Date(r.createdAt)),
                        timeFmt.format(Date(r.createdAt)),
                        r.businessName,
                        r.cnic,
                        r.phone,
                        r.address,
                        if (r.fbrRegistered) "Yes" else "No",
                        r.lat,
                        r.lng,
                        "https://maps.google.com/?q=${r.lat},${r.lng}",
                        r.photoName,
                    )
                }
                val widths = listOf(5, 12, 10, 28, 17, 14, 45, 15, 12, 12, 42, 40)
                file.outputStream().use { Xlsx.write(it, "Survey", header, rows, widths) }

                val savedTo = try { copyToDownloads(file) } catch (e: Exception) { null }

                runOnUiThread {
                    if (savedTo != null) Toast.makeText(this, "Saved to $savedTo", Toast.LENGTH_LONG).show()
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share Excel file"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Keeps a copy in Downloads/FBR Survey so the file is easy to find later. */
    private fun copyToDownloads(src: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Photo.ALBUM)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException()
            contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Photo.ALBUM)
            dir.mkdirs()
            src.copyTo(File(dir, src.name), overwrite = true)
        }
        return "Download/${Photo.ALBUM}/${src.name}"
    }

    // ---------- list ----------

    private inner class RecordAdapter : BaseAdapter() {
        private val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = records[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_record, parent, false)
            val r = records[position]
            v.findViewById<TextView>(R.id.name).text = r.businessName.ifBlank { "(no name)" }
            v.findViewById<TextView>(R.id.address).text =
                r.address.ifBlank { String.format(Locale.ENGLISH, "%.5f, %.5f (address not entered)", r.lat, r.lng) }
            v.findViewById<TextView>(R.id.date).text =
                listOf(dateFmt.format(Date(r.createdAt)), r.cnic, r.phone).filter { it.isNotBlank() }.joinToString("  •  ")

            val status = v.findViewById<TextView>(R.id.status)
            status.text = if (r.fbrRegistered) "FBR ✓" else "Not reg."
            status.background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(ContextCompat.getColor(this@MainActivity, if (r.fbrRegistered) R.color.green else R.color.red))
            }

            val img = v.findViewById<ImageView>(R.id.thumb)
            img.tag = r.photoUri
            val cached = thumbs.get(r.photoUri)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                io.execute {
                    val b = Photo.thumb(this@MainActivity, Uri.parse(r.photoUri)) ?: return@execute
                    thumbs.put(r.photoUri, b)
                    runOnUiThread { if (img.tag == r.photoUri) img.setImageBitmap(b) }
                }
            }
            return v
        }
    }
}
