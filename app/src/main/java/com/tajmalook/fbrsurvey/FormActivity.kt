package com.tajmalook.fbrsurvey

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class FormActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private val io = Executors.newFixedThreadPool(3)

    private lateinit var preview: ImageView
    private lateinit var gpsStatus: TextView
    private lateinit var inSurveyor: TextInputEditText
    private lateinit var inFormation: TextInputEditText
    private lateinit var inArea: TextInputEditText
    private lateinit var inMarket: TextInputEditText
    private lateinit var inName: TextInputEditText
    private lateinit var inOwner: TextInputEditText
    private lateinit var inCnic: TextInputEditText
    private lateinit var inPhone: TextInputEditText
    private lateinit var cbNoCnic: android.widget.CheckBox
    private lateinit var inAddress: TextInputEditText
    private lateinit var rgFbr: RadioGroup

    private var editing: Record? = null
    private var initialCnic = ""
    private lateinit var fbrStatus: TextView
    private val captureFile by lazy { File(cacheDir, "capture.jpg") }

    private var photoTaken = false
    private var captureTime = 0L
    private var hasLocation = false
    private var lat = 0.0
    private var lng = 0.0
    private var accuracy = 0f
    private var autoAddress: String? = null
    private var mapFuture: Future<Bitmap>? = null
    private var waitingForLocationSettings = false

    // ---------- launchers ----------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camOk = result[Manifest.permission.CAMERA] ?: hasPerm(Manifest.permission.CAMERA)
            val locOk = (result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                (result[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false) || hasLocationPerm()
            if (!camOk || !locOk) {
                AlertDialog.Builder(this)
                    .setTitle("Permission needed")
                    .setMessage("The app needs Camera and Location permission to take geo-tagged shop photos.\n\nPlease allow them in Settings → Apps → FBR Shop Survey → Permissions.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        finish()
                    }
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                startLocation()
                openCamera()
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok && captureFile.exists() && captureFile.length() > 0) {
                photoTaken = true
                captureTime = System.currentTimeMillis()
                showPreview()
                if (!hasLocation) startLocation()
            } else if (!photoTaken) {
                finish() // cancelled the very first photo
            }
        }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)
        keepAboveSystemBars()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        preview = findViewById(R.id.preview)
        gpsStatus = findViewById(R.id.gpsStatus)
        inSurveyor = findViewById(R.id.inSurveyor)
        inFormation = findViewById(R.id.inFormation)
        inArea = findViewById(R.id.inArea)
        inMarket = findViewById(R.id.inMarket)
        inName = findViewById(R.id.inName)
        inOwner = findViewById(R.id.inOwner)
        inCnic = findViewById(R.id.inCnic)
        inPhone = findViewById(R.id.inPhone)
        cbNoCnic = findViewById(R.id.cbNoCnic)
        cbNoCnic.setOnCheckedChangeListener { _, checked ->
            if (checked) { inCnic.setText(""); inCnic.error = null }
            inCnic.isEnabled = !checked
            findViewById<View>(R.id.btnCheck).isEnabled = !checked
        }
        inAddress = findViewById(R.id.inAddress)
        rgFbr = findViewById(R.id.rgFbr)
        inCnic.addTextChangedListener(CnicFormatter(inCnic))
        fbrStatus = findViewById(R.id.fbrStatus)
        inCnic.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { checkCnicOffline() }
        })

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnCheck).setOnClickListener { checkOnFbr() }
        findViewById<MaterialButton>(R.id.btnCopyPhone).setOnClickListener { copyAndOpenBrowser(inPhone) }
        gpsStatus.setOnClickListener { if (!hasLocation && editing == null) startLocation() }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id != -1L) {
            setupEdit(id)
            return
        }

        title = "New survey"
        if (savedInstanceState == null) {
            val prefs = getSharedPreferences("form", MODE_PRIVATE)
            inSurveyor.setText(prefs.getString("surveyor", ""))
            inFormation.setText(prefs.getString("formation", "RTO Faisalabad"))
            inArea.setText(prefs.getString("area", ""))
            inMarket.setText(prefs.getString("market", ""))
        }
        findViewById<View>(R.id.btnRetake).setOnClickListener { openCamera() }

        if (savedInstanceState != null) {
            photoTaken = savedInstanceState.getBoolean("photoTaken")
            captureTime = savedInstanceState.getLong("captureTime")
            hasLocation = savedInstanceState.getBoolean("hasLocation")
            lat = savedInstanceState.getDouble("lat")
            lng = savedInstanceState.getDouble("lng")
            accuracy = savedInstanceState.getFloat("accuracy")
            autoAddress = savedInstanceState.getString("autoAddress")
            if (photoTaken) showPreview()
            if (hasLocation) onLocation(null) else if (hasLocationPerm()) startLocation()
            return
        }

        captureFile.delete()
        val needed = mutableListOf(Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.all { hasPerm(it) }) {
            startLocation()
            openCamera()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForLocationSettings) {
            waitingForLocationSettings = false
            startLocation()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("photoTaken", photoTaken)
        outState.putLong("captureTime", captureTime)
        outState.putBoolean("hasLocation", hasLocation)
        outState.putDouble("lat", lat)
        outState.putDouble("lng", lng)
        outState.putFloat("accuracy", accuracy)
        outState.putString("autoAddress", autoAddress)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------- edit existing record ----------

    private fun setupEdit(id: Long) {
        val r = Store.get(this, id)
        if (r == null) { finish(); return }
        editing = r
        title = "Edit record"
        findViewById<View>(R.id.btnRetake).visibility = View.GONE
        inSurveyor.setText(r.surveyor)
        inFormation.setText(r.formation)
        inArea.setText(r.area)
        inMarket.setText(r.market)
        inName.setText(r.businessName)
        inOwner.setText(r.ownerName)
        initialCnic = r.cnic.filter { it.isDigit() }
        inCnic.setText(r.cnic)
        inPhone.setText(r.phone)
        cbNoCnic.isChecked = r.cnicNotProvided
        inAddress.setText(r.address)
        rgFbr.check(if (r.fbrRegistered) R.id.rbYes else R.id.rbNo)
        gpsStatus.text = String.format(Locale.ENGLISH, "📍 %.6f, %.6f  •  %s", r.lat, r.lng,
            SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH).format(Date(r.createdAt)))
        preview.setOnClickListener {
            if (!Photo.galleryExists(this, Uri.parse(r.photoUri))) {
                toast("Photo was deleted from the gallery. Tap Save to put it back."); return@setOnClickListener
            }
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.photoUri), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        io.execute {
            val b = Photo.thumbFor(this, r, 900)
            runOnUiThread { if (b != null) preview.setImageBitmap(b) }
        }
    }

    // ---------- camera ----------

    private fun openCamera() {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", captureFile)
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
            if (!photoTaken) finish()
        }
    }

    private fun showPreview() {
        io.execute {
            val b = try { Photo.loadUpright(captureFile, 1200) } catch (e: Exception) { null }
            runOnUiThread { if (b != null) preview.setImageBitmap(b) }
        }
    }

    // ---------- location ----------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            gpsStatus.text = "⚠ Location is OFF. Tap here after turning it on."
            AlertDialog.Builder(this)
                .setTitle("Turn on Location")
                .setMessage("GPS is needed to stamp the shop's location on the photo.")
                .setPositiveButton("Turn on") { _, _ ->
                    waitingForLocationSettings = true
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        gpsStatus.text = "Getting GPS location…"
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null) onLocation(loc)
                else client.lastLocation.addOnSuccessListener { last ->
                    if (last != null) onLocation(last)
                    else gpsStatus.text = "⚠ Could not get GPS. Go outside / near a door and tap here to retry."
                }
            }
            .addOnFailureListener {
                gpsStatus.text = "⚠ GPS error. Tap here to retry."
            }
    }

    /** loc == null means restoring previously saved coordinates. */
    private fun onLocation(loc: Location?) {
        if (isFinishing || isDestroyed) return
        if (loc != null) {
            lat = loc.latitude; lng = loc.longitude; accuracy = loc.accuracy
            hasLocation = true
        }
        gpsStatus.text = String.format(Locale.ENGLISH, "📍 %.6f, %.6f (±%.0f m)  •  finding address…", lat, lng, accuracy)

        mapFuture = io.submit<Bitmap> { Geo.mapThumb(lat, lng) }

        val la = lat; val ln = lng
        io.execute {
            val addr = Geo.address(this, la, ln)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val base = String.format(Locale.ENGLISH, "📍 %.6f, %.6f (±%.0f m)", la, ln, accuracy)
                if (addr != null) {
                    val current = inAddress.text?.toString().orEmpty()
                    if (current.isBlank() || current == autoAddress) inAddress.setText(addr)
                    autoAddress = addr
                    gpsStatus.text = "$base  •  address found"
                } else {
                    gpsStatus.text = "$base  •  no internet: enter address manually or later"
                }
            }
        }
    }

    // ---------- save ----------

    private fun save() {
        val surveyor = inSurveyor.text?.toString()?.trim().orEmpty()
        val formation = inFormation.text?.toString()?.trim().orEmpty()
        val area = inArea.text?.toString()?.trim().orEmpty()
        val market = inMarket.text?.toString()?.trim().orEmpty()
        val name = inName.text?.toString()?.trim().orEmpty()
        val owner = inOwner.text?.toString()?.trim().orEmpty()
        val cnic = inCnic.text?.toString()?.trim().orEmpty()
        val phone = inPhone.text?.toString()?.trim().orEmpty()
        val address = inAddress.text?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        val fbrId = rgFbr.checkedRadioButtonId

        val cnicDigits = cnic.filter { it.isDigit() }
        val phoneDigits = phone.filter { it.isDigit() }
        when {
            editing == null && !photoTaken -> return toast("Take the shop photo first")
            editing == null && !hasLocation -> return toast("Waiting for GPS location. Please wait or tap the GPS line to retry.")
            name.isEmpty() -> { inName.error = "Required"; inName.requestFocus(); return }
            cnic.isNotEmpty() && cnicDigits.length != 13 -> { inCnic.error = "CNIC must have 13 digits"; inCnic.requestFocus(); return }
            phone.isNotEmpty() && phoneDigits.length !in 10..13 -> { inPhone.error = "Check phone number"; inPhone.requestFocus(); return }
            fbrId == -1 -> return toast("Select whether the shop is registered with FBR")
        }
        val fbr = fbrId == R.id.rbYes

        if (cnicDigits.length == 13) {
            val dup = Store.all(this).firstOrNull {
                it.id != editing?.id && it.cnic.filter { c -> c.isDigit() } == cnicDigits
            }
            if (dup != null) {
                AlertDialog.Builder(this)
                    .setTitle("Same CNIC already saved")
                    .setMessage("This CNIC is already recorded for:\n${dup.businessName}\n\nSave anyway?")
                    .setPositiveButton("Save anyway") { _, _ -> doSave(surveyor, Triple(formation, area, market), name, owner, cnic, phone, address, fbr) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        doSave(surveyor, Triple(formation, area, market), name, owner, cnic, phone, address, fbr)
    }

    private fun doSave(surveyor: String, place: Triple<String, String, String>, name: String, owner: String, cnic: String, phone: String, address: String, fbr: Boolean) {
        val (formation, area, market) = place
        getSharedPreferences("form", MODE_PRIVATE).edit()
            .putString("surveyor", surveyor).putString("formation", formation).putString("area", area).putString("market", market).apply()
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage("Stamping and saving photo…"); setCancelable(false); show()
        }
        val e = editing
        if (e != null) {
            e.cnicNotProvided = cbNoCnic.isChecked
            e.surveyor = surveyor; e.formation = formation; e.area = area; e.market = market
            e.businessName = name; e.ownerName = owner; e.cnic = cnic; e.phone = phone
            e.address = address; e.fbrRegistered = fbr
            io.execute {
                Store.upsert(this, e)
                // Redraw the stamp with the new details, if the original photo is kept.
                var restamped = false
                var restored = false
                val raw = Store.rawPhoto(this, e.id)
                if (raw.exists()) try {
                    val photo = Photo.loadUpright(raw)
                    val mapF = Store.mapFile(this, e.id)
                    val map = android.graphics.BitmapFactory.decodeFile(mapF.absolutePath)
                        ?: Geo.mapThumb(e.lat, e.lng, online = false)
                    Photo.stamp(photo, map, e)
                    val uri = Uri.parse(e.photoUri)
                    if (e.photoUri.isNotBlank() && Photo.galleryExists(this, uri)) {
                        restamped = Photo.overwriteGallery(this, uri, photo)
                    } else {
                        // deleted from gallery: save a fresh stamped copy and remember it
                        e.photoUri = Photo.saveToGallery(this, photo, e.photoName).toString()
                        Store.upsert(this, e)
                        restored = true
                    }
                    photo.recycle()
                } catch (ex: Throwable) { }
                runOnUiThread {
                    progress.dismiss()
                    toast(when {
                        restored -> "Updated ✓  Photo restored to Gallery → ${Photo.ALBUM}"
                        restamped -> "Updated ✓  Photo stamp updated too"
                        else -> "Updated ✓"
                    })
                    finish()
                }
            }
            return
        }

        val la = lat; val ln = lng; val acc = accuracy; val time = captureTime
        val noCnic = cbNoCnic.isChecked
        io.execute {
            try {
                val map = try {
                    mapFuture?.get(15, TimeUnit.SECONDS) ?: Geo.mapThumb(la, ln, online = false)
                } catch (ex: Exception) {
                    Geo.mapThumb(la, ln, online = false)
                }
                val fileName = "FBR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date(time)) +
                    "_" + Photo.safeFileName(name) + ".jpg"
                val draft = Record(
                    id = time, createdAt = time,
                    surveyor = surveyor, formation = formation, area = area, market = market,
                    cnicNotProvided = noCnic,
                    businessName = name, ownerName = owner, cnic = cnic, phone = phone, address = address,
                    fbrRegistered = fbr, lat = la, lng = ln, accuracy = acc,
                    photoUri = "", photoName = fileName,
                )

                val photo = Photo.loadUpright(captureFile)
                // keep an unstamped copy + the map so the stamp can be redrawn after edits
                Store.rawPhoto(this, time).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Store.mapFile(this, time).outputStream().use { map.compress(Bitmap.CompressFormat.PNG, 100, it) }

                Photo.stamp(photo, map, draft)
                val uri = Photo.saveToGallery(this, photo, fileName)
                photo.recycle()

                Store.upsert(this, draft.copy(photoUri = uri.toString()))
                captureFile.delete()
                runOnUiThread {
                    progress.dismiss()
                    toast("Saved ✓  Photo is in Gallery → ${Photo.ALBUM}")
                    finish()
                }
            } catch (ex: Throwable) {
                runOnUiThread {
                    progress.dismiss()
                    toast("Save failed: ${ex.message}")
                }
            }
        }
    }

    /** Copies the CNIC (digits only) and opens FBR IRIS in the browser. */
    private fun checkOnFbr() {
        val digits = inCnic.text?.toString().orEmpty().filter { it.isDigit() }
        if (digits.length != 13) {
            inCnic.error = "Enter full 13-digit CNIC first"
            inCnic.requestFocus()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("CNIC", digits))
        Toast.makeText(this, "CNIC $digits copied. Paste it on the FBR page.", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iris.fbr.gov.pk")))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }

    /** Looks the CNIC up in the imported FBR data; fills name and ticks Yes/No (always editable). */
    private fun checkCnicOffline() {
        val digits = inCnic.text?.toString().orEmpty().filter { it.isDigit() }
        if (digits.length != 13) { fbrStatus.visibility = View.GONE; return }
        if (io.isShutdown) return
        io.execute {
            val res = FbrLookup.lookup(this, digits)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (inCnic.text?.toString().orEmpty().filter { it.isDigit() } != digits) return@runOnUiThread
                fbrStatus.visibility = View.VISIBLE
                val auto = digits != initialCnic   // don't overwrite a saved record just by opening it
                when {
                    res == null -> {
                        fbrStatus.text = "FBR data not imported (Home → Menu → Import FBR data)"
                        fbrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    }
                    res.found -> {
                        val who = listOf(res.name, res.business).filter { it.isNotBlank() }.joinToString("  •  ")
                        fbrStatus.text = if (who.isNotBlank()) "✓ Registered: $who" else "✓ Registered (found in FBR data)"
                        fbrStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                        if (auto) {
                            rgFbr.check(R.id.rbYes)
                            if (res.name.isNotBlank() && inOwner.text.isNullOrBlank()) inOwner.setText(res.name)
                            // business name only if FBR has one; otherwise the field stays empty
                            if (res.business.isNotBlank() && inName.text.isNullOrBlank()) inName.setText(res.business)
                        }
                    }
                    else -> {
                        fbrStatus.text = "✗ Not found in FBR data"
                        fbrStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                        if (auto) rgFbr.check(R.id.rbNo)
                    }
                }
            }
        }
    }

    /** Copies the number and opens the phone's default browser on its start page. */
    private fun copyAndOpenBrowser(field: TextInputEditText) {
        val num = field.text?.toString().orEmpty().filter { it.isDigit() || it == '+' }
        if (num.isEmpty()) { field.error = "Enter number first"; return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Number", num))
        Toast.makeText(this, "Number $num copied", Toast.LENGTH_SHORT).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }

    // ---------- helpers ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPerm() =
        hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Formats CNIC as 12345-1234567-1 while typing. */
    private class CnicFormatter(private val field: TextInputEditText) : TextWatcher {
        private var busy = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (busy || s == null) return
            val d = s.filter { it.isDigit() }.take(13).toString()
            val f = buildString {
                d.forEachIndexed { i, c ->
                    if (i == 5 || i == 12) append('-')
                    append(c)
                }
            }
            if (f != s.toString()) {
                busy = true
                field.setText(f)
                field.setSelection(f.length)
                busy = false
            }
        }
    }
}
