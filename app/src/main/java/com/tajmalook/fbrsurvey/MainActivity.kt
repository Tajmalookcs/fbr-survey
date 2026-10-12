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
                .setMessage("${r.businessName}\n\nThe record will be removed from the app. The stamped photo stays in your gallery.")
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
        thumbs.evictAll()
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
        val dayFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)
        val byDay = records.groupBy { dayFmt.format(Date(it.createdAt)) }
        val days = byDay.keys.sortedByDescending { dayFmt.parse(it)?.time ?: 0L }
        val choices = days.map { "$it  (${byDay[it]!!.size} shops)" } + "All dates  (${records.size} shops)"

        val prefs = getSharedPreferences("export", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad + pad / 2, pad / 2, pad + pad / 2, 0)
        }
        val label1 = TextView(this).apply { text = "Report title" }
        val titleIn = android.widget.EditText(this).apply {
            setText(prefs.getString("title", "SURVEY REPORT SMALL TRADERS SCHEME, RTO, FAISALABAD"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val label2 = TextView(this).apply { text = "Which shops?"; setPadding(0, pad, 0, 0) }
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, choices)
        }
        box.addView(label1); box.addView(titleIn); box.addView(label2); box.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("Export Excel")
            .setView(box)
            .setPositiveButton("Export") { _, _ ->
                val title = titleIn.text.toString().trim()
                prefs.edit().putString("title", title).apply()
                val pos = spinner.selectedItemPosition
                val day = days.getOrNull(pos)
                val chosen = if (day != null) byDay[day]!! else records
                writeExcel(title, day, chosen.sortedBy { it.createdAt })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeExcel(title: String, day: String?, data: List<Record>) {
        io.execute {
            try {
                val dayFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)
                val place = data.firstOrNull { it.area.isNotBlank() }?.area?.split(",")?.first()?.trim().orEmpty()
                val datePart = day ?: ("ALL_" + dayFmt.format(Date()))
                val name = listOf("SURVEY", datePart, Photo.safeFileName(place))
                    .filter { it.isNotBlank() }.joinToString("_") + ".xlsx"
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, name)

                val header = listOf("Sr. No.", "Name of Field Formation", "Area", "Market Name", "Business Name",
                    "Name of Shopkeeper", "Business Address", "CNIC of Person Operating Shop", "Mobile Number",
                    "Whether Registered or No", "Dated", "Google Location")
                val rows = data.mapIndexed { i, r ->
                    val url = "https://maps.google.com/?q=${r.lat},${r.lng}"
                    listOf(
                        Xlsx.Cell(i + 1, Xlsx.CENTER),
                        Xlsx.Cell(r.formation, Xlsx.LEFT),
                        Xlsx.Cell(r.area, Xlsx.LEFT),
                        Xlsx.Cell(r.market, Xlsx.LEFT),
                        Xlsx.Cell(r.businessName.uppercase(Locale.ENGLISH), Xlsx.LEFT),
                        Xlsx.Cell(r.ownerName.uppercase(Locale.ENGLISH), Xlsx.LEFT),
                        Xlsx.Cell(r.address, Xlsx.LEFT),
                        Xlsx.Cell(r.cnic.filter { it.isDigit() }, Xlsx.TEXT_CENTER),
                        Xlsx.Cell(r.phone.filter { it.isDigit() }, Xlsx.TEXT_CENTER),
                        Xlsx.Cell(if (r.fbrRegistered) "Yes" else "No", Xlsx.CENTER),
                        Xlsx.Cell(dayFmt.format(Date(r.createdAt)), Xlsx.CENTER),
                        Xlsx.Cell(url, Xlsx.LINK, url),
                    )
                }
                val widths = listOf(7.14, 20.86, 14.14, 15.29, 44.0, 25.29, 26.57, 17.86, 18.71, 15.86, 11.86, 42.0)
                file.outputStream().use {
                    Xlsx.writeReport(it, day ?: "Survey", title, header, rows, widths)
                }

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
                listOf(r.ownerName, dateFmt.format(Date(r.createdAt)), r.cnic).filter { it.isNotBlank() }.joinToString("  •  ")

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
