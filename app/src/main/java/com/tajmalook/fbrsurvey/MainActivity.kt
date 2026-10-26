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
    private var allRecords: List<Record> = emptyList()   // everything (stats, export)
    private var records: List<Record> = emptyList()      // shown in the list (search filter)
    private var query = ""
    private val namePass = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var adapter: RecordAdapter

    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFbrData(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepAboveSystemBars()
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

        findViewById<View>(R.id.btnNew).setOnClickListener { startNewSurvey() }
        findViewById<View>(R.id.btnExport).setOnClickListener { export() }
        findViewById<android.widget.EditText>(R.id.inSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); applySearch() }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        catchUpNames()
    }

    /** Shops saved with an empty name: read it from the signboard in the background when possible. */
    private fun catchUpNames() {
        if (!Gemini.hasKey(this) || !namePass.compareAndSet(false, true)) return
        val app = applicationContext
        Bg.io.execute {
            var filled = 0
            try {
                // imported photos still waiting: read coordinates / date / name from the stamp
                for (r in Store.all(app).filter { it.source == "pending" }) {
                    when (Restamp.fillImported(app, r)) {
                        true -> { Restamp.fillFromSavedLocation(app, r); Restamp.redraw(app, r); filled++ }
                        null -> break
                        false -> {}
                    }
                }
                for (r in Store.all(app).filter { it.businessName.isBlank() && it.photoName.isNotBlank() }) {
                    when (Restamp.fillNameFromBoard(app, r)) {
                        true -> { Restamp.redraw(app, r); filled++ }
                        null -> break        // offline or free limit: stop, try next time
                        false -> {}
                    }
                }
            } catch (e: Throwable) { }
            namePass.set(false)
            if (filled > 0) runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refresh()
                Toast.makeText(this, "Details read from photos for $filled shop(s) ✓", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun geminiSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val info = TextView(this).apply {
            text = android.text.Html.fromHtml(
                "Used to read the shop name from the signboard photo.<br><br>" +
                    "Get a free key (no card needed): <a href=\"https://aistudio.google.com/apikey\">aistudio.google.com/apikey</a> " +
                    "→ Create API key → copy, then paste below.<br><br>The key is saved only on this phone.",
                android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        val input = android.widget.EditText(this).apply {
            hint = "Paste Gemini API key"
            setText(Gemini.key(this@MainActivity))
            isSingleLine = true
        }
        val result = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info); addView(input); addView(result)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Settings: Gemini key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Gemini.setKey(this, input.text.toString())
                Toast.makeText(this, if (input.text.isBlank()) "Gemini key removed" else "Gemini key saved ✓", Toast.LENGTH_SHORT).show()
                catchUpNames()
            }
            .setNeutralButton("Test", null)
            .setNegativeButton("Cancel", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val k = input.text.toString()
            result.text = "Testing…"
            Bg.io.execute {
                val r = Gemini.test(applicationContext, k)
                runOnUiThread {
                    result.text = when (r) {
                        is Gemini.Result.Name -> "✓ Key works. Tap Save."
                        else -> "✗ " + Gemini.message(r)
                    }
                }
            }
        }
    }

    private fun refresh() {
        allRecords = Store.all(this)
        thumbs.evictAll()
        findViewById<TextView>(R.id.statTotal).text = allRecords.size.toString()
        findViewById<TextView>(R.id.statYes).text = allRecords.count { it.fbrRegistered }.toString()
        findViewById<TextView>(R.id.statNo).text = allRecords.count { !it.fbrRegistered }.toString()
        applySearch()
    }

    /** Filters the list by business name (any part, upper/lower case ignored). */
    private fun applySearch() {
        val q = query.trim()
        records = if (q.isEmpty()) allRecords else allRecords.filter { it.businessName.contains(q, ignoreCase = true) }
        val empty = findViewById<TextView>(R.id.emptyText)
        when {
            allRecords.isEmpty() -> { empty.text = "No shops surveyed yet.\nTap New survey to start."; empty.visibility = View.VISIBLE }
            records.isEmpty() -> { empty.text = "No shop found for \"$q\""; empty.visibility = View.VISIBLE }
            else -> empty.visibility = View.GONE
        }
        adapter.notifyDataSetChanged()
    }

    // ---------- fill address / map for shops saved offline ----------

    private fun updateOfflineRecords() {
        val todo = Store.all(this).filter { it.mapAddress.isBlank() || !it.mapReal || it.source == "pending" || (it.businessName.isBlank() && it.photoName.isNotBlank()) }
        if (todo.isEmpty()) {
            AlertDialog.Builder(this).setMessage("All shops already have an address and map.").setPositiveButton("OK", null).show()
            return
        }
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Updating 0 / ${todo.size}…"); setCancelable(false); show()
        }
        io.execute {
            var updated = 0
            todo.forEachIndexed { i, r ->
                runOnUiThread { progress.setMessage("Updating ${i + 1} / ${todo.size}…") }
                if (Restamp.fillFromSavedLocation(this, r)) {
                    Restamp.redraw(this, r)
                    updated++
                }
            }
            val left = Store.all(this).count { it.mapAddress.isBlank() || !it.mapReal || it.source == "pending" || (it.businessName.isBlank() && it.photoName.isNotBlank()) }
            runOnUiThread {
                progress.dismiss()
                refresh()
                val msg = if (updated == 0) "Nothing could be updated. Check the internet connection and try again."
                    else "Updated $updated shop(s) using their saved location.\nPhotos in the gallery were redrawn." +
                        (if (left > 0) "\n\n$left still missing (no internet or no address found)." else "")
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun updateOne(r: Record) {
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Updating from saved location…"); setCancelable(false); show()
        }
        io.execute {
            val found = Restamp.fillFromSavedLocation(this, r)
            if (found) Restamp.redraw(this, r)
            runOnUiThread {
                progress.dismiss()
                refresh()
                Toast.makeText(this,
                    if (found) "Updated ✓  ${r.businessName}" else "No internet or no address found. Try again later.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- FBR data import ----------

    private fun importFbrData(uri: Uri) {
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Importing FBR data…"); setCancelable(false); show()
        }
        io.execute {
            val msg = try {
                val n = FbrLookup.import(this, uri)
                String.format(Locale.ENGLISH, "FBR data imported ✓\n%,d CNICs ready for checking.", n)
            } catch (e: Throwable) {
                "Import failed: this is not a valid FBR_Lookup.fbr file.\n\n(${e.message})"
            }
            runOnUiThread {
                progress.dismiss()
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------- About ----------

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "About").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Import FBR data").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 2, "Update offline records").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 4, 3, "Start new survey with…").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 5, 4, "Settings (Gemini key)").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) { showAbout(); return true }
        if (item.itemId == 3) { updateOfflineRecords(); return true }
        if (item.itemId == 4) { chooseStartMode(); return true }
        if (item.itemId == 5) { geminiSettings(); return true }
        if (item.itemId == 2) {
            AlertDialog.Builder(this)
                .setTitle("Import FBR data")
                .setMessage("Choose the FBR_Lookup.fbr file made on the PC.\n\nIt replaces any FBR data already in the app.")
                .setPositiveButton("Choose file") { _, _ -> importLauncher.launch(arrayOf("*/*")) }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- new survey: photo first or details first ----------

    private fun startNewSurvey() {
        when (getSharedPreferences("app", MODE_PRIVATE).getString("startMode", "")) {
            "photo" -> openForm(detailsFirst = false)
            "details" -> openForm(detailsFirst = true)
            "import" -> openImport()
            else -> {
                val pad = (20 * resources.displayMetrics.density).toInt()
                val remember = android.widget.CheckBox(this).apply { text = "Remember my choice" }
                val box = android.widget.LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(remember) }
                fun pick(mode: String) {
                    if (remember.isChecked) getSharedPreferences("app", MODE_PRIVATE).edit().putString("startMode", mode).apply()
                    if (mode == "import") openImport() else openForm(detailsFirst = mode == "details")
                }
                AlertDialog.Builder(this)
                    .setTitle("New survey")
                    .setMessage("How do you want to start?")
                    .setView(box)
                    .setPositiveButton("📷 Photo first") { _, _ -> pick("photo") }
                    .setNegativeButton("📝 Details first") { _, _ -> pick("details") }
                    .setNeutralButton("🖼 Import") { _, _ -> pick("import") }
                    .show()
            }
        }
    }

    private fun openForm(detailsFirst: Boolean) {
        startActivity(Intent(this, FormActivity::class.java).putExtra(FormActivity.EXTRA_DETAILS_FIRST, detailsFirst))
    }

    private fun openImport() {
        startActivity(Intent(this, FormActivity::class.java).putExtra(FormActivity.EXTRA_IMPORT, true))
    }

    private fun chooseStartMode() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val modes = arrayOf("photo", "details", "import", "")
        val labels = arrayOf("📷 Photo first", "📝 Details first", "🖼 Import from gallery", "Ask every time")
        val current = modes.indexOf(prefs.getString("startMode", "")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Start new survey with…")
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.edit().putString("startMode", modes[which]).apply()
                Toast.makeText(this, "New survey: ${labels[which]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .show()
    }

    private fun showAbout() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        val email = "tajmalookcs@hotmail.com"
        val pad = (20 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            text = android.text.SpannableStringBuilder().apply {
                append("Version $version\n\n")
                append("Field survey app for checking whether shops are registered with FBR. ")
                append("Takes shop photos stamped with map, address, GPS coordinates, date and time, ")
                append("records shop details, and exports a report in Excel.\n\n")
                val start = length
                append("Developer")
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                append("\nTaj Malook\n")
                val es = length
                append(email)
                setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        try {
                            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                                .putExtra(Intent.EXTRA_SUBJECT, "FBR Shop Survey app"))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, email, Toast.LENGTH_LONG).show()
                        }
                    }
                }, es, length, 0)
                val fbr = FbrLookup.info(this@MainActivity)
                append("\n\nFBR data: ")
                append(if (fbr == null) "not imported (Menu → Import FBR data)" else
                    String.format(Locale.ENGLISH, "%,d CNICs, file made on %s", fbr.count,
                        SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH).format(Date(fbr.createdSec * 1000))))
                append("\n\nMap data © OpenStreetMap contributors.")
            }
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setIcon(R.mipmap.ic_launcher)
            .setView(body)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Excel export ----------

    private fun export() {
        if (allRecords.isEmpty()) {
            Toast.makeText(this, "No records to export yet", Toast.LENGTH_SHORT).show(); return
        }
        val dayFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)
        val byDay = allRecords.groupBy { dayFmt.format(Date(it.shownTime().takeIf { t -> t > 0 } ?: it.createdAt)) }
        val days = byDay.keys.sortedByDescending { dayFmt.parse(it)?.time ?: 0L }
        val choices = days.map { "$it  (${byDay[it]!!.size} shops)" } + "All dates  (${allRecords.size} shops)"

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
                val chosen = if (day != null) byDay[day]!! else allRecords
                writeExcel(title, day, chosen.sortedBy { it.shownTime().takeIf { t -> t > 0 } ?: it.createdAt })
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
                    "Whether Registered or No", "Dated", "Google Location", "Source")
                val rows = data.mapIndexed { i, r ->
                    val url = if (r.lat == 0.0 && r.lng == 0.0) "" else "https://maps.google.com/?q=${r.lat},${r.lng}"
                    listOf(
                        Xlsx.Cell(i + 1, Xlsx.CENTER),
                        Xlsx.Cell(r.formation, Xlsx.LEFT),
                        Xlsx.Cell(r.area, Xlsx.LEFT),
                        Xlsx.Cell(r.market, Xlsx.LEFT),
                        Xlsx.Cell(r.businessName.uppercase(Locale.ENGLISH), Xlsx.LEFT),
                        Xlsx.Cell(r.ownerName.uppercase(Locale.ENGLISH), Xlsx.LEFT),
                        Xlsx.Cell(r.address, Xlsx.LEFT),
                        Xlsx.Cell(if (r.cnicNotProvided) "Not provided" else r.cnic.filter { it.isDigit() }, Xlsx.TEXT_CENTER),
                        Xlsx.Cell(r.phone.filter { it.isDigit() }, Xlsx.TEXT_CENTER),
                        Xlsx.Cell(if (r.fbrRegistered) "Yes" else "No", Xlsx.CENTER),
                        Xlsx.Cell(if (r.shownTime() > 0) dayFmt.format(Date(r.shownTime())) else "", Xlsx.CENTER),
                        if (url.isEmpty()) Xlsx.Cell("", Xlsx.CENTER) else Xlsx.Cell(url, Xlsx.LINK, url),
                        Xlsx.Cell(r.sourceLabel(), Xlsx.LEFT),
                    )
                }
                val widths = listOf(7.14, 20.86, 14.14, 15.29, 44.0, 25.29, 26.57, 17.86, 18.71, 15.86, 11.86, 42.0, 30.0)
                file.outputStream().use {
                    val names = data.map { it.surveyor.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
                    Xlsx.writeReport(it, day ?: "Survey", title, "Survey Conducted by: $names", header, rows, widths)
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
                (listOf(r.ownerName, if (r.shownTime() > 0) dateFmt.format(Date(r.shownTime())) else "", r.cnic) +
                    (if (r.photoName.isBlank()) listOf("📷 No photo") else emptyList()) +
                    (when (r.source) { "pending" -> listOf("⏳ Pending"); "stamp", "photo" -> listOf("🖼 Imported"); else -> emptyList() })).filter { it.isNotBlank() }.joinToString("  •  ")

            val status = v.findViewById<TextView>(R.id.status)
            status.text = if (r.fbrRegistered) "FBR ✓" else "Not reg."
            status.background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(ContextCompat.getColor(this@MainActivity, if (r.fbrRegistered) R.color.green else R.color.red))
            }

            val upd = v.findViewById<View>(R.id.btnRowUpdate)
            upd.visibility = if (r.photoName.isNotBlank() && (r.mapAddress.isBlank() || !r.mapReal || r.businessName.isBlank() || r.source == "pending")) View.VISIBLE else View.GONE
            upd.setOnClickListener { updateOne(r) }

            val img = v.findViewById<ImageView>(R.id.thumb)
            img.tag = r.photoUri
            val cached = thumbs.get(r.photoUri)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                io.execute {
                    val b = Photo.thumbFor(this@MainActivity, r) ?: return@execute
                    thumbs.put(r.photoUri, b)
                    runOnUiThread { if (img.tag == r.photoUri) img.setImageBitmap(b) }
                }
            }
            return v
        }
    }
}
