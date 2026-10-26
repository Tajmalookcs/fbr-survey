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
        const val EXTRA_DETAILS_FIRST = "detailsFirst"
        const val EXTRA_IMPORT = "import"
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
    /** a new photo was taken for an already-saved shop; applied on Save */
    private var retakePending = false
    /** New survey opened in 'Enter details first' mode (camera not opened automatically). */
    private var detailsFirst = false
    private lateinit var nameNote: TextView
    private lateinit var btnSave: MaterialButton
    private var useManualOnPhoto = false
    private lateinit var btnStampManual: MaterialButton
    private lateinit var roRealAddress: TextInputEditText
    private lateinit var roCoords: TextInputEditText
    private var mapFuture: Future<Geo.MapResult>? = null
    private var waitingForLocationSettings = false

    // ---- imported photo (from gallery) + crop
    /** New survey opened with "Import from gallery". */
    private var importStart = false
    /** the current photo is an imported one (coordinates/date come from the image, not GPS) */
    private var importMode = false
    /** "stamp" | "photo" | "pending" */
    private var impSource = "camera"
    /** date/time read from the image (0 = not found); never typed by hand */
    private var impTime = 0L
    private var impTimeFrom = ""
    private var coordsManual = false
    /** coordinates found automatically (stamp / photo data); null = none */
    private var autoLat: Double? = null
    private var autoLng: Double? = null
    /** coordinates from the Latitude/Longitude boxes, filled at Save (0 = none) */
    private var formLat = 0.0
    private var formLng = 0.0
    private var stampNote: String? = null
    private var pendingCrop = ""
    private var wantCamera = false
    private lateinit var importBox: View
    private lateinit var tvImportDate: TextView
    private lateinit var inLat: TextInputEditText
    private lateinit var inLng: TextInputEditText
    private lateinit var tvCoordSource: TextView

    // ---------- launchers ----------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camOk = result[Manifest.permission.CAMERA] ?: hasPerm(Manifest.permission.CAMERA)
            val locOk = (result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                (result[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false) || hasLocationPerm()
            if ((!camOk || !locOk) && (photoTaken || editing != null || importStart)) {
                toast("Camera and Location permission are needed to take a photo")
            } else if (!camOk || !locOk) {
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
                if (detailsFirst && !wantCamera) startLocation() else openCamera()
            }
        }

    /** In-app camera with live stamp preview. */
    private val inAppCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data?.getBooleanExtra(CameraActivity.RES_USE_SYSTEM, false) == true) {
                openSystemCamera()
                return@registerForActivityResult
            }
            if (res.resultCode == RESULT_OK && captureFile.exists() && captureFile.length() > 0) {
                val wasImport = leaveImportMode()
                photoTaken = true
                captureTime = System.currentTimeMillis()
                showPreview()
                readNameFromBoard(force = false)
                val retake = markRetake() || wasImport
                if (data != null && data.hasExtra(CameraActivity.RES_LAT)) {
                    lat = data.getDoubleExtra(CameraActivity.RES_LAT, 0.0)
                    lng = data.getDoubleExtra(CameraActivity.RES_LNG, 0.0)
                    accuracy = data.getFloatExtra(CameraActivity.RES_ACC, 0f)
                    hasLocation = true
                    data.getStringExtra(CameraActivity.RES_ADDR)?.let { a ->
                        val cur = inAddress.text?.toString().orEmpty()
                        if (cur.isBlank() || cur == autoAddress) inAddress.setText(a)
                        autoAddress = a
                        updateRealViews()
                    }
                    onLocation(null)
                } else {
                    if (retake) hasLocation = false   // new photo needs its own GPS fix
                    if (!hasLocation) startLocation()
                }
            } else if (!photoTaken && editing == null && !detailsFirst && !importStart) {
                finish() // cancelled the very first photo
            }
            updatePhotoButton()
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok && captureFile.exists() && captureFile.length() > 0) {
                val wasImport = leaveImportMode()
                photoTaken = true
                captureTime = System.currentTimeMillis()
                showPreview()
                readNameFromBoard(force = false)
                if (markRetake() || wasImport) hasLocation = false
                if (!hasLocation) startLocation()
            } else if (!photoTaken && editing == null && !detailsFirst && !importStart) {
                finish() // cancelled the very first photo
            }
            updatePhotoButton()
        }

    /** Gallery picker (keeps the photo's own location data when allowed). */
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data
            if (res.resultCode == RESULT_OK && uri != null) handleImport(uri)
            else if (!photoTaken && editing == null && importStart) finish()
        }

    private val mediaLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchGallery() }

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            pendingCrop = res.data?.getStringExtra(CropActivity.EXTRA_SPEC).orEmpty()
            showPreview()
            toast(if (pendingCrop.isBlank()) "Crop removed. Tap Save to apply." else "Cropped. Tap Save to apply.")
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
        nameNote = findViewById(R.id.nameNote)
        findViewById<MaterialButton>(R.id.btnReadName).setOnClickListener { readNameFromBoard(force = true) }
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
        btnStampManual = findViewById(R.id.btnStampManual)
        roRealAddress = findViewById(R.id.roRealAddress)
        roCoords = findViewById(R.id.roCoords)
        btnStampManual.setOnClickListener {
            if (!useManualOnPhoto && inAddress.text.isNullOrBlank()) { toast("Type an address first"); return@setOnClickListener }
            useManualOnPhoto = !useManualOnPhoto
            updateRealViews()
            toast(if (useManualOnPhoto) "Photo will show your typed address" else "Photo will show the real GPS address")
        }
        rgFbr = findViewById(R.id.rgFbr)
        inCnic.addTextChangedListener(CnicFormatter(inCnic))
        fbrStatus = findViewById(R.id.fbrStatus)
        inCnic.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { checkCnicOffline() }
        })

        btnSave = findViewById(R.id.btnSave)
        btnSave.setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editing == null && (photoTaken || !inName.text.isNullOrBlank())) {
                    AlertDialog.Builder(this@FormActivity)
                        .setTitle("Discard this survey?")
                        .setMessage("This shop has not been saved yet.")
                        .setPositiveButton("Discard") { _, _ -> finish() }
                        .setNegativeButton("Keep editing", null)
                        .show()
                } else finish()
            }
        })
        findViewById<MaterialButton>(R.id.btnCheck).setOnClickListener { checkOnFbr() }
        findViewById<MaterialButton>(R.id.btnCopyPhone).setOnClickListener { copyAndOpenBrowser(inPhone) }
        gpsStatus.setOnClickListener { if (!hasLocation && editing == null && !importMode) startLocation() }

        findViewById<View>(R.id.btnRetake).setOnClickListener { openCamera() }
        importBox = findViewById(R.id.importBox)
        tvImportDate = findViewById(R.id.tvImportDate)
        inLat = findViewById(R.id.inLat)
        inLng = findViewById(R.id.inLng)
        tvCoordSource = findViewById(R.id.tvCoordSource)
        findViewById<View>(R.id.btnGallery).setOnClickListener { pickFromGallery() }
        findViewById<View>(R.id.btnCrop).setOnClickListener { openCrop() }
        findViewById<View>(R.id.btnReadImage).setOnClickListener { readStampFromImage(manual = true) }
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id != -1L) {
            setupEdit(id)
            return
        }

        title = "New survey"
        detailsFirst = intent.getBooleanExtra(EXTRA_DETAILS_FIRST, false)
        importStart = intent.getBooleanExtra(EXTRA_IMPORT, false)
        updateRealViews()
        updatePhotoButton()
        if (savedInstanceState == null) {
            rgFbr.check(R.id.rbNo)   // default: Not registered (FBR data check can switch it to Yes)
            val prefs = getSharedPreferences("form", MODE_PRIVATE)
            inSurveyor.setText(prefs.getString("surveyor", ""))
            inFormation.setText(prefs.getString("formation", "RTO Faisalabad"))
            inArea.setText(prefs.getString("area", ""))
            inMarket.setText(prefs.getString("market", ""))
        }

        if (savedInstanceState != null) {
            photoTaken = savedInstanceState.getBoolean("photoTaken")
            captureTime = savedInstanceState.getLong("captureTime")
            hasLocation = savedInstanceState.getBoolean("hasLocation")
            lat = savedInstanceState.getDouble("lat")
            lng = savedInstanceState.getDouble("lng")
            accuracy = savedInstanceState.getFloat("accuracy")
            autoAddress = savedInstanceState.getString("autoAddress")
            useManualOnPhoto = savedInstanceState.getBoolean("useManual")
            importMode = savedInstanceState.getBoolean("importMode")
            impSource = savedInstanceState.getString("impSource") ?: "camera"
            impTime = savedInstanceState.getLong("impTime")
            impTimeFrom = savedInstanceState.getString("impTimeFrom").orEmpty()
            coordsManual = savedInstanceState.getBoolean("coordsManual")
            pendingCrop = savedInstanceState.getString("pendingCrop").orEmpty()
            if (savedInstanceState.containsKey("autoLat")) {
                autoLat = savedInstanceState.getDouble("autoLat"); autoLng = savedInstanceState.getDouble("autoLng")
            }
            updateImportBox()
            if (photoTaken) showPreview()
            updatePhotoButton()
            if (hasLocation) onLocation(null) else if (hasLocationPerm() && !importMode && !importStart) startLocation()
            return
        }

        captureFile.delete()
        if (importStart) { pickFromGallery(); return }
        val needed = mutableListOf(Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.all { hasPerm(it) }) {
            if (detailsFirst) startLocation() else openCamera()
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
        outState.putBoolean("useManual", useManualOnPhoto)
        outState.putBoolean("importMode", importMode)
        outState.putString("impSource", impSource)
        outState.putLong("impTime", impTime)
        outState.putString("impTimeFrom", impTimeFrom)
        outState.putBoolean("coordsManual", coordsManual)
        outState.putString("pendingCrop", pendingCrop)
        autoLat?.let { outState.putDouble("autoLat", it); outState.putDouble("autoLng", autoLng ?: 0.0) }
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
        useManualOnPhoto = r.stampUseManual
        updateRealViews()
        updatePhotoButton()
        rgFbr.check(if (r.fbrRegistered) R.id.rbYes else R.id.rbNo)
        pendingCrop = r.crop
        loadImportState(r)
        gpsStatus.text = when {
            !r.isImported() -> String.format(Locale.ENGLISH, "📍 %.6f, %.6f  •  %s", r.lat, r.lng,
                SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH).format(Date(r.createdAt)))
            r.lat == 0.0 && r.lng == 0.0 -> "🖼 Imported photo  •  no coordinates yet"
            else -> String.format(Locale.ENGLISH, "🖼 Imported photo  •  📍 %.6f, %.6f", r.lat, r.lng)
        }
        enableEditControls()
        if ((r.address.isBlank() || r.mapAddress.isBlank()) && !(r.lat == 0.0 && r.lng == 0.0)) io.execute {
            val addr = Geo.address(this, r.lat, r.lng)
            runOnUiThread {
                if (isFinishing || isDestroyed || addr == null) return@runOnUiThread
                if (r.mapAddress.isBlank()) r.mapAddress = addr      // photo stamp address
                updateRealViews()
                if (inAddress.text.isNullOrBlank()) inAddress.setText(addr)
                gpsStatus.text = gpsStatus.text.toString() + "\n✓ Address found from saved location. Tap Save to update the photo."
            }
        }
        io.execute {
            val b = Photo.thumbFor(this, r, 900)
            runOnUiThread { if (b != null) preview.setImageBitmap(b) }
        }
    }

    // ---------- camera ----------

    private fun openCamera() {
        if (!hasPerm(Manifest.permission.CAMERA) || !hasLocationPerm()) {
            wantCamera = true
            val needed = mutableListOf(Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        try {
            inAppCameraLauncher.launch(Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_OUT, captureFile.absolutePath))
        } catch (e: Exception) {
            openSystemCamera()
        }
    }

    /** Phone's own camera app (no live stamp). */
    private fun openSystemCamera() {
        if (!hasLocation) startLocation()
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", captureFile)
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
            if (!photoTaken) finish()
        }
    }

    /** Shows the new photo (or the saved clean photo) with the current crop. */
    private fun showPreview() {
        val file = cleanPhotoFile() ?: return
        val crop = pendingCrop
        if (io.isShutdown) return
        io.execute {
            val b = try { Photo.applyCrop(Photo.loadUpright(file, 1200), crop) } catch (e: Exception) { null }
            runOnUiThread { if (b != null && !isDestroyed) preview.setImageBitmap(b) }
        }
    }

    /** The clean (unstamped, full) photo: the new one not saved yet, or the saved shop's copy. */
    private fun cleanPhotoFile(): File? = when {
        photoTaken && captureFile.exists() && (editing == null || retakePending) -> captureFile
        editing != null && Store.rawPhoto(this, editing!!.id).exists() -> Store.rawPhoto(this, editing!!.id)
        photoTaken && captureFile.exists() -> captureFile
        else -> null
    }

    // ---------- import from gallery ----------

    private fun pickFromGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPerm(Manifest.permission.ACCESS_MEDIA_LOCATION) &&
            !getSharedPreferences("app", MODE_PRIVATE).getBoolean("askedMediaLoc", false)
        ) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("askedMediaLoc", true).apply()
            mediaLocationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        launchGallery()
    }

    private fun launchGallery() {
        val pick = Intent(Intent.ACTION_PICK).setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        try {
            galleryLauncher.launch(pick)
        } catch (e: Exception) {
            try {
                galleryLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE))
            } catch (e2: Exception) {
                toast("No gallery app found")
                if (!photoTaken && editing == null && importStart) finish()
            }
        }
    }

    private fun handleImport(uri: Uri) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply { setMessage("Opening photo…"); setCancelable(false); show() }
        io.execute {
            val ex = try { Importer.copy(this, uri, captureFile) } catch (e: Exception) { null }
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ex == null || !captureFile.exists() || captureFile.length() == 0L) {
                    toast("Could not open this photo")
                    if (!photoTaken && editing == null && importStart) finish()
                    return@runOnUiThread
                }
                photoTaken = true
                importMode = true
                captureTime = System.currentTimeMillis()
                pendingCrop = ""
                coordsManual = false
                autoLat = null; autoLng = null
                impTime = ex.time ?: 0L
                impTimeFrom = if (ex.time != null) "photo data" else ""
                impSource = if (ex.lat != null || ex.time != null) "photo" else "pending"
                hasLocation = false; lat = 0.0; lng = 0.0; accuracy = 0f
                autoAddress = null; mapFuture = null
                inLat.setText(""); inLng.setText("")
                stampNote = null
                if (ex.lat != null && ex.lng != null) setImportCoords(ex.lat, ex.lng)
                showPreview()
                markRetake()
                updateRealViews()
                updatePhotoButton()
                updateImportBox()
                if (!hasLocation) gpsStatus.text = "🖼 Imported photo  •  reading coordinates from the image…"
                readStampFromImage(manual = false)
            }
        }
    }

    /** Coordinates found automatically (stamp or photo data). */
    private fun setImportCoords(la: Double, ln: Double) {
        autoLat = la; autoLng = ln
        inLat.setText(String.format(Locale.ENGLISH, "%.6f", la))
        inLng.setText(String.format(Locale.ENGLISH, "%.6f", ln))
        coordsManual = false
        if (editing != null && !retakePending) return   // saved shop: applied on Save
        lat = la; lng = ln; accuracy = 0f
        hasLocation = true
        onLocation(null)
    }

    /** Asks Gemini to read coordinates, date/time and name from the other app's stamp. */
    private fun readStampFromImage(manual: Boolean) {
        val file = cleanPhotoFile()
        if (file == null) { if (manual) toast("No photo"); return }
        if (!Gemini.hasKey(this)) {
            stampNote = "Add your Gemini key (Home → ⋮ → Settings) to read the stamp."
            updateImportBox(); return
        }
        stampNote = "Reading coordinates, date and name from the image…"
        updateImportBox()
        if (io.isShutdown) return
        io.execute {
            val res = Gemini.readStamp(this, file)
            runOnUiThread {
                if (isFinishing || isDestroyed || !importMode) return@runOnUiThread
                when (res) {
                    is Gemini.Result.StampData -> {
                        if (res.lat != null && res.lng != null) setImportCoords(res.lat, res.lng)
                        if (res.time != null) { impTime = res.time; impTimeFrom = "stamp" }
                        if (res.lat != null || res.time != null) impSource = "stamp"
                        else if (impSource == "pending") impSource = "photo"
                        if (res.name.isNotBlank() && inName.text.isNullOrBlank()) {
                            inName.setText(res.name)
                            nameNote.text = "✓ Read from image: please check"
                            nameNote.setTextColor(ContextCompat.getColor(this, R.color.green))
                        }
                        stampNote = if (res.lat == null && res.time == null) "No coordinates or date found on the image"
                            else Gemini.message(res)
                    }
                    Gemini.Result.NoName -> {
                        if (impSource == "pending") impSource = "photo"
                        stampNote = "No coordinates or date found on the image"
                    }
                    else -> stampNote = Gemini.message(res) +
                        if (impSource == "pending") "\n⏳ You can save now; it will be read later when online." else ""
                }
                if (!hasLocation && editing == null) gpsStatus.text = "🖼 Imported photo  •  no coordinates"
                updateRealViews()
                updateImportBox()
            }
        }
    }

    /** Shows/hides the imported-photo box (date read-only, coordinates editable). */
    private fun updateImportBox() {
        importBox.visibility = if (importMode) View.VISIBLE else View.GONE
        if (!importMode) return
        val fmt = SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH)
        tvImportDate.text = when {
            impTime > 0 -> "Date & time: ${fmt.format(Date(impTime))} (read from ${impTimeFrom.ifBlank { "image" }})"
            impSource == "pending" -> "Date & time: ⏳ will be read from the image"
            else -> "Date not found on image"
        }
        val src = when {
            coordsManual -> "Coordinates entered manually"
            autoLat != null && impSource == "stamp" -> "Coordinates read from the stamp: please check (fix only if wrong)"
            autoLat != null -> "Coordinates from the photo's own data (fix only if wrong)"
            impSource == "pending" -> "⏳ Pending: coordinates will be read when online"
            else -> "No coordinates found: enter them if you have them"
        }
        tvCoordSource.text = listOfNotNull(stampNote, src).joinToString("\n")
    }

    /** Leaves import mode when a camera photo replaces the imported one. Returns true if it was imported. */
    private fun leaveImportMode(): Boolean {
        val was = importMode
        importMode = false
        impSource = "camera"; impTime = 0L; impTimeFrom = ""; coordsManual = false
        autoLat = null; autoLng = null; stampNote = null
        pendingCrop = ""
        updateImportBox()
        return was
    }

    private fun loadImportState(r: Record) {
        importMode = r.isImported()
        impSource = r.source
        impTime = r.photoTime
        impTimeFrom = when { r.photoTime == 0L -> ""; r.source == "stamp" -> "stamp"; else -> "photo data" }
        coordsManual = r.coordsManual
        val has = !(r.lat == 0.0 && r.lng == 0.0)
        autoLat = if (has && !r.coordsManual) r.lat else null
        autoLng = if (has && !r.coordsManual) r.lng else null
        inLat.setText(if (has) String.format(Locale.ENGLISH, "%.6f", r.lat) else "")
        inLng.setText(if (has) String.format(Locale.ENGLISH, "%.6f", r.lng) else "")
        stampNote = null
        updateImportBox()
    }

    /** Reads the Latitude/Longitude boxes. false = invalid (error shown). */
    private fun readImportFields(): Boolean {
        val ts = inLat.text?.toString()?.trim().orEmpty()
        val gs = inLng.text?.toString()?.trim().orEmpty()
        if (ts.isEmpty() && gs.isEmpty()) {
            formLat = 0.0; formLng = 0.0
            coordsManual = false
            return true
        }
        val la = ts.toDoubleOrNull()
        val ln = gs.toDoubleOrNull()
        if (la == null || la !in -90.0..90.0 || la == 0.0) { inLat.error = "Check latitude"; inLat.requestFocus(); return false }
        if (ln == null || ln !in -180.0..180.0 || ln == 0.0) { inLng.error = "Check longitude"; inLng.requestFocus(); return false }
        formLat = la; formLng = ln
        val a = autoLat; val b = autoLng
        coordsManual = a == null || b == null || kotlin.math.abs(a - la) > 1e-6 || kotlin.math.abs(b - ln) > 1e-6
        if (editing == null || retakePending) {
            if (kotlin.math.abs(lat - la) > 1e-6 || kotlin.math.abs(lng - ln) > 1e-6) {
                autoAddress = null; mapFuture = null   // address will be found for the new coordinates
            }
            lat = la; lng = ln; accuracy = 0f; hasLocation = true
        }
        return true
    }

    // ---------- crop ----------

    private fun openCrop() {
        val file = cleanPhotoFile()
        if (file == null) { toast("Take or import a photo first"); return }
        cropLauncher.launch(Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_FILE, file.absolutePath)
            .putExtra(CropActivity.EXTRA_SPEC, pendingCrop))
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
        if (loc != null && importMode) return   // imported photo: coordinates come from the image
        if (loc != null) {
            lat = loc.latitude; lng = loc.longitude; accuracy = loc.accuracy
            hasLocation = true
        }
        val accTxt = if (importMode) "" else String.format(Locale.ENGLISH, " (±%.0f m)", accuracy)
        val pre = if (importMode) "🖼 Imported  •  " else ""
        gpsStatus.text = String.format(Locale.ENGLISH, "%s📍 %.6f, %.6f%s  •  finding address…", pre, lat, lng, accTxt)
        updateRealViews()

        mapFuture = io.submit<Geo.MapResult> { Geo.mapThumbResult(lat, lng) }

        val la = lat; val ln = lng
        io.execute {
            val addr = Geo.address(this, la, ln)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (la != lat || ln != lng) return@runOnUiThread   // coordinates changed meanwhile
                val base = String.format(Locale.ENGLISH, "%s📍 %.6f, %.6f%s", pre, la, ln, accTxt)
                if (addr != null) {
                    val current = inAddress.text?.toString().orEmpty()
                    if (current.isBlank() || current == autoAddress) inAddress.setText(addr)
                    autoAddress = addr
                    updateRealViews()
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
        if (importMode && !readImportFields()) return
        when {
            editing == null && photoTaken && !hasLocation && !importMode -> return toast("Waiting for GPS location. Please wait or tap the GPS line to retry.")
            retakePending && !hasLocation && !importMode -> return toast("Waiting for GPS for the new photo. Please wait a moment.")
            name.isEmpty() && !(hasAnyPhoto() && Gemini.hasKey(this)) -> { inName.error = "Required"; inName.requestFocus(); return }
            cnic.isNotEmpty() && cnicDigits.length != 13 -> { inCnic.error = "CNIC must have 13 digits"; inCnic.requestFocus(); return }
            phone.isNotEmpty() && phoneDigits.length !in 10..13 -> { inPhone.error = "Check phone number"; inPhone.requestFocus(); return }
            fbrId == -1 -> return toast("Select whether the shop is registered with FBR")
        }
        val fbr = fbrId == R.id.rbYes

        val proceed = { saveAfterChecks(surveyor, formation, area, market, name, owner, cnic, phone, address, fbr, cnicDigits) }
        val noPhotoYet = !photoTaken && (editing == null || editing?.photoName.isNullOrBlank())
        if (noPhotoYet && !retakePending) {
            AlertDialog.Builder(this)
                .setTitle("No photo taken")
                .setMessage("Save this shop without a photo?\n\nYou can add the photo later: open the shop, tap Take photo, then Save.")
                .setPositiveButton("Save anyway") { _, _ -> proceed() }
                .setNegativeButton("Take photo") { _, _ -> openCamera() }
                .show()
            return
        }
        proceed()
    }

    private fun saveAfterChecks(surveyor: String, formation: String, area: String, market: String, name: String,
                                owner: String, cnic: String, phone: String, address: String, fbr: Boolean, cnicDigits: String) {
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
            setMessage("Saving…"); setCancelable(false); show()
        }
        btnSave.isEnabled = false
        val e = editing
        if (e != null) {
            e.cnicNotProvided = cbNoCnic.isChecked
            e.surveyor = surveyor; e.formation = formation; e.area = area; e.market = market
            e.businessName = name; e.ownerName = owner; e.cnic = cnic; e.phone = phone
            e.address = address; e.fbrRegistered = fbr
            e.stampUseManual = useManualOnPhoto && address.isNotBlank()
            val retake = retakePending
            val hadPhoto = e.photoName.isNotBlank()
            val nla = lat; val nln = lng; val nacc = accuracy; val ntime = captureTime
            val nAddr = autoAddress.orEmpty(); val mf = mapFuture
            val imp = importMode; val src = impSource; val pTime = impTime; val cm = coordsManual
            val fLat = formLat; val fLng = formLng
            e.crop = pendingCrop
            if (!retake && imp && e.isImported()) {
                if (kotlin.math.abs(fLat - e.lat) > 1e-7 || kotlin.math.abs(fLng - e.lng) > 1e-7) {
                    e.lat = fLat; e.lng = fLng; e.mapAddress = ""
                }
                e.coordsManual = cm && !(fLat == 0.0 && fLng == 0.0)
                e.source = src
                e.photoTime = pTime
            }
            io.execute {
                if (retake) try {
                    // replace the photo: new clean copy, new map, new time + GPS of the new photo
                    val mr = try {
                        mf?.get(3, TimeUnit.SECONDS) ?: Geo.mapThumbResult(nla, nln, online = false)
                    } catch (ex: Exception) {
                        Geo.mapThumbResult(nla, nln, online = false)
                    }
                    val photo = Photo.loadUpright(captureFile)
                    Store.rawPhoto(this, e.id).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    photo.recycle()
                    Store.mapFile(this, e.id).outputStream().use { mr.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    e.createdAt = ntime; e.lat = nla; e.lng = nln; e.accuracy = nacc
                    e.mapAddress = nAddr; e.mapReal = imp || mr.real
                    e.source = if (imp) src else "camera"
                    e.photoTime = if (imp) pTime else 0L
                    e.coordsManual = imp && cm && !(nla == 0.0 && nln == 0.0)
                    // keep the old photo in the gallery: the new one is saved as a separate file
                    e.photoUri = ""
                    e.photoName = "FBR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date(ntime)) +
                        "_" + Photo.safeFileName(name) + ".jpg"
                    captureFile.delete()
                    runOnUiThread { retakePending = false }
                } catch (ex: Throwable) {
                    runOnUiThread { toast("Could not use the new photo: ${ex.message}") }
                }
                // quick: redraw with what we have now (no waiting for internet)
                val res = Restamp.redraw(this, e)
                runOnUiThread {
                    progress.dismiss()
                    btnSave.isEnabled = true
                    toast(when (res) {
                        Restamp.Result.RESTORED -> when {
                            retake && hadPhoto -> "Saved ✓  New photo added. Old photo kept in Gallery"
                            retake -> "Saved ✓  Photo added to Gallery → ${Photo.ALBUM}"
                            else -> "Saved ✓  Photo restored to Gallery → ${Photo.ALBUM}"
                        }
                        Restamp.Result.RESTAMPED -> "Saved ✓  Photo updated"
                        else -> "Saved ✓"
                    })
                    reloadPreview()
                    updatePhotoButton()
                }
                if (!e.mapReal || e.mapAddress.isBlank() || e.businessName.isBlank() || e.source == "pending") backgroundFill(e)
            }
            return
        }

        if (!photoTaken) {
            // details saved without a photo: keep the current GPS (if any) for Excel
            val now = System.currentTimeMillis()
            val rec = Record(
                id = now, createdAt = now,
                surveyor = surveyor, formation = formation, area = area, market = market,
                cnicNotProvided = cbNoCnic.isChecked, mapReal = false, mapAddress = autoAddress.orEmpty(),
                stampUseManual = useManualOnPhoto && address.isNotBlank(),
                businessName = name, ownerName = owner, cnic = cnic, phone = phone, address = address,
                fbrRegistered = fbr,
                lat = if (hasLocation) lat else 0.0, lng = if (hasLocation) lng else 0.0,
                accuracy = if (hasLocation) accuracy else 0f,
                photoUri = "", photoName = "",
            )
            io.execute {
                Store.upsert(this, rec)
                runOnUiThread {
                    progress.dismiss()
                    btnSave.isEnabled = true
                    toast(if (hasLocation) "Saved ✓  without photo" else "Saved ✓  without photo and without GPS")
                    switchToEdit(rec)
                }
            }
            return
        }

        val la = if (hasLocation) lat else 0.0; val ln = if (hasLocation) lng else 0.0
        val acc = accuracy; val time = captureTime
        val imp = importMode; val src = impSource; val pTime = impTime
        val cm = coordsManual && hasLocation; val crop = pendingCrop
        val noCnic = cbNoCnic.isChecked
        val useManual = useManualOnPhoto && address.isNotBlank()
        val stampAddr = autoAddress.orEmpty()   // real address from GPS, not the edited field
        io.execute {
            try {
                // wait a little for the map, never long
                val mr = try {
                    mapFuture?.get(3, TimeUnit.SECONDS) ?: Geo.mapThumbResult(la, ln, online = false)
                } catch (ex: Exception) {
                    Geo.mapThumbResult(la, ln, online = false)
                }
                val map = mr.bitmap
                val fileName = "FBR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date(time)) +
                    "_" + Photo.safeFileName(name) + ".jpg"
                val draft = Record(
                    id = time, createdAt = time,
                    surveyor = surveyor, formation = formation, area = area, market = market,
                    cnicNotProvided = noCnic, mapReal = imp || mr.real, mapAddress = stampAddr, stampUseManual = useManual,
                    source = if (imp) src else "camera", photoTime = if (imp) pTime else 0L,
                    coordsManual = imp && cm, crop = crop,
                    businessName = name, ownerName = owner, cnic = cnic, phone = phone, address = address,
                    fbrRegistered = fbr, lat = la, lng = ln, accuracy = acc,
                    photoUri = "", photoName = fileName,
                )

                val photo = Photo.loadUpright(captureFile)
                // keep an unstamped copy + the map so the stamp can be redrawn after edits
                Store.rawPhoto(this, time).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Store.mapFile(this, time).outputStream().use { map.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val shown = Photo.applyCrop(photo, crop)   // full photo kept above; crop only the gallery copy
                Photo.stamp(shown, map, draft)
                val uri = Photo.saveToGallery(this, shown, fileName)
                shown.recycle()

                val saved = draft.copy(photoUri = uri.toString())
                Store.upsert(this, saved)
                captureFile.delete()
                runOnUiThread {
                    progress.dismiss()
                    btnSave.isEnabled = true
                    toast("Saved ✓  Photo is in Gallery → ${Photo.ALBUM}")
                    switchToEdit(saved)
                }
                if (!saved.mapReal || saved.mapAddress.isBlank() || saved.businessName.isBlank() || saved.source == "pending") backgroundFill(saved)
            } catch (ex: Throwable) {
                runOnUiThread {
                    progress.dismiss()
                    btnSave.isEnabled = true
                    toast("Save failed: ${ex.message}")
                }
            }
        }
    }

    /** After the first save, the form stays open as an edit of the saved shop (no duplicates). */
    private fun switchToEdit(r: Record) {
        editing = r
        title = "Edit record"
        initialCnic = r.cnic.filter { it.isDigit() }
        enableEditControls()
        pendingCrop = r.crop
        loadImportState(r)
        updateRealViews()
        updatePhotoButton()
    }

    private fun enableEditControls() {
        preview.setOnClickListener {
            val r = editing ?: return@setOnClickListener
            if (!Photo.galleryExists(this, Uri.parse(r.photoUri))) {
                toast("Photo was deleted from the gallery. Tap Save to put it back."); return@setOnClickListener
            }
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.photoUri), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        findViewById<MaterialButton>(R.id.btnGetAddress).apply {
            visibility = View.VISIBLE
            setOnClickListener { editing?.let { getFromSavedLocation(it) } }
        }
    }

    private fun reloadPreview() {
        val r = editing ?: return
        if (io.isShutdown) return
        io.execute {
            val b = Photo.thumbFor(this, r, 900)
            runOnUiThread { if (b != null && !isDestroyed) preview.setImageBitmap(b) }
        }
    }

    /** With internet, fills real map/address from the SAVED coordinates after saving; never blocks the screen. */
    private fun backgroundFill(r: Record) {
        val app = applicationContext
        Bg.io.execute {
            try {
                if (Restamp.fillFromSavedLocation(app, r)) {
                    Restamp.redraw(app, r)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (inAddress.text.isNullOrBlank() && r.address.isNotBlank()) inAddress.setText(r.address)
                        if (inName.text.isNullOrBlank() && r.businessName.isNotBlank()) inName.setText(r.businessName)
                        if (editing?.id == r.id && !retakePending) {
                            editing = r
                            if (r.isImported()) loadImportState(r)
                        }
                        updateRealViews()
                        reloadPreview()
                        toast("Updated from saved photo/location ✓")
                    }
                }
            } catch (e: Throwable) { }
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

    /** Looks up address + real map for the record's SAVED coordinates (edit screen button). */
    private fun getFromSavedLocation(r: Record) {
        if (r.lat == 0.0 && r.lng == 0.0) { toast("This shop has no coordinates yet"); return }
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage("Getting address & map for saved location…"); setCancelable(false); show()
        }
        io.execute {
            val addr = Geo.address(this, r.lat, r.lng)
            val m = Geo.mapThumbResult(r.lat, r.lng)
            if (m.real) {
                Store.mapFile(this, r.id).outputStream().use { m.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                r.mapReal = true
                editing?.mapReal = true
            }
            if (addr != null) r.mapAddress = addr   // photo stamp always uses the real map address
            runOnUiThread { updateRealViews() }
            runOnUiThread {
                progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    addr == null && !m.real -> toast("No internet. Try again when connected.")
                    addr == null -> toast("Map updated ✓  No address found. Type it manually, then tap Save.")
                    inAddress.text.isNullOrBlank() || inAddress.text.toString() == addr -> {
                        inAddress.setText(addr)
                        toast("Address & map ready ✓  Tap Save to update the photo.")
                    }
                    else -> {
                        val coords = String.format(Locale.ENGLISH, "%.6f, %.6f", r.lat, r.lng)
                        val lessDetail = Geo.detail(addr) < Geo.detail(inAddress.text.toString())
                        AlertDialog.Builder(this)
                            .setTitle("Replace address?")
                            .setMessage("Current:\n${inAddress.text}\n\nFrom saved photo location ($coords):\n$addr" +
                                if (lessDetail) "\n\nNote: this is less detailed than your current address." else "")
                            .setPositiveButton("Replace") { _, _ -> inAddress.setText(addr); toast("Tap Save to update the photo.") }
                            .setNegativeButton("Keep current", null)
                            .setNeutralButton("Open in Maps") { _, _ ->
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$coords".replace(" ", ""))))
                                } catch (e: Exception) { toast("No maps app found") }
                            }
                            .show()
                    }
                }
            }
        }
    }

    /** Fills the read-only "real address" + "real coordinates" boxes and the photo-address button. */
    private fun updateRealViews() {
        val e = if (retakePending) null else editing   // pending retake: show the new photo's GPS
        val la = e?.lat ?: lat
        val ln = e?.lng ?: lng
        val have = (e != null && !(e.lat == 0.0 && e.lng == 0.0)) || (e == null && hasLocation)
        val real = (if (e != null) e.mapAddress else autoAddress.orEmpty())
        if (have) {
            val plus = PlusCode.encode(la, ln)
            roRealAddress.setText(if (real.isNotBlank()) "${plus.substring(4)}, $real" else "$plus  (address not found yet)")
            roCoords.setText(String.format(Locale.ENGLISH, "%.6f, %.6f", la, ln))
        } else {
            roRealAddress.setText(if (importMode || e != null) "No coordinates" else "Waiting for GPS…")
            roCoords.setText("")
        }
        btnStampManual.text = if (useManualOnPhoto) "✓ Photo shows typed address (tap to use real address)"
            else "Use this address on photo"
    }

    /** Returns true if this photo replaces the photo of an already-saved shop. */
    private fun markRetake(): Boolean {
        if (editing == null) return false
        retakePending = true
        toast("New photo ready. Tap Save to add it (old photo stays in Gallery).")
        updateRealViews()
        return true
    }

    /** "Take photo" when the shop has no photo yet, otherwise "Retake photo". */
    private fun updatePhotoButton() {
        val hasPhoto = photoTaken || !(editing?.photoName.isNullOrBlank())
        (findViewById<View>(R.id.btnRetake) as MaterialButton).text = if (hasPhoto) "Retake photo" else "Take photo"
        (findViewById<View>(R.id.btnGallery) as MaterialButton).text =
            if (editing != null) "🖼 Attach from gallery" else "🖼 From gallery"
        if (!hasPhoto && !hasLocation && editing == null && !importStart) gpsStatus.text = "No photo yet. Tap Take photo when ready."
    }

    private fun hasAnyPhoto() = photoTaken || !(editing?.photoName.isNullOrBlank())

    /** Reads the business name from the signboard with Gemini (never overwrites a typed name unless forced). */
    private fun readNameFromBoard(force: Boolean) {
        if (!force && !inName.text.isNullOrBlank()) return
        if (!Gemini.hasKey(this)) {
            if (force) {
                AlertDialog.Builder(this)
                    .setMessage("Add your free Gemini API key first:\nHome → ⋮ → Settings (Gemini key).")
                    .setPositiveButton("OK", null).show()
            }
            return
        }
        // the clean photo: the new one (not yet saved) or the saved shop's clean copy
        val file = when {
            photoTaken && captureFile.exists() -> captureFile
            editing != null -> Store.rawPhoto(this, editing!!.id)
            else -> null
        }
        if (file == null || !file.exists()) { if (force) toast("Take the shop photo first"); return }
        nameNote.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        nameNote.text = "Reading name from signboard…"
        if (io.isShutdown) return
        io.execute {
            val res = Gemini.readName(this, file)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                nameNote.text = Gemini.message(res)
                when (res) {
                    is Gemini.Result.Name -> {
                        if (force || inName.text.isNullOrBlank()) inName.setText(res.name)
                        nameNote.setTextColor(ContextCompat.getColor(this, R.color.green))
                    }
                    is Gemini.Result.Offline, Gemini.Result.Limit, Gemini.Result.Busy, Gemini.Result.Slow, Gemini.Result.NoName ->
                        nameNote.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    else -> nameNote.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
            }
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
