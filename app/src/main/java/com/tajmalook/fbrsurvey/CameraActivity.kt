package com.tajmalook.fbrsurvey

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app camera with a live stamp preview (map, Plus Code + address, coordinates, time),
 * so the surveyor can see the address has loaded before taking the photo.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OUT = "out"
        const val RES_LAT = "lat"
        const val RES_LNG = "lng"
        const val RES_ACC = "acc"
        const val RES_ADDR = "addr"
        const val RES_USE_SYSTEM = "useSystem"
    }

    private val io = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var previewView: PreviewView
    private lateinit var camMap: ImageView
    private lateinit var camStatus: TextView
    private lateinit var camAddress: TextView
    private lateinit var camCoords: TextView
    private lateinit var camTime: TextView
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnFlash: MaterialButton

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var flashOn = false

    private lateinit var fused: FusedLocationProviderClient
    private var loc: Location? = null
    private var lookedUpAt: Location? = null
    private var lookupToken = 0
    private var address: String? = null
    private var updatesOn = false

    private val tick = object : Runnable {
        override fun run() {
            camTime.text = Photo.stampDate(System.currentTimeMillis())
            handler.postDelayed(this, 1000)
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLoc(it) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        keepAboveSystemBars()

        previewView = findViewById(R.id.previewView)
        camMap = findViewById(R.id.camMap)
        camStatus = findViewById(R.id.camStatus)
        camAddress = findViewById(R.id.camAddress)
        camCoords = findViewById(R.id.camCoords)
        camTime = findViewById(R.id.camTime)
        btnShutter = findViewById(R.id.btnShutter)
        btnFlash = findViewById(R.id.btnFlash)

        // keep the preview area 4:3 (portrait), exactly like the saved photo
        val frame = findViewById<android.view.View>(R.id.frame)
        frame.post {
            frame.layoutParams = frame.layoutParams.apply { height = frame.width * 4 / 3 }
        }

        camMap.setImageBitmap(Geo.mapThumb(0.0, 0.0, 256, online = false))
        fused = LocationServices.getFusedLocationProviderClient(this)

        btnShutter.setOnClickListener { takePhoto() }
        btnFlash.setOnClickListener {
            flashOn = !flashOn
            btnFlash.text = if (flashOn) "Flash: On" else "Flash: Off"
            imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        }
        findViewById<MaterialButton>(R.id.btnSystemCam).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(RES_USE_SYSTEM, true))
            finish()
        }
        camStatus.setOnClickListener {
            if (!locationEnabled()) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        val zoom = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { _, ev -> zoom.onTouchEvent(ev); true }

        startCamera()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        if (updatesOn) { fused.removeLocationUpdates(callback); updatesOn = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------- camera ----------

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error, opening phone camera", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK, Intent().putExtra(RES_USE_SYSTEM, true))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val out = File(intent.getStringExtra(EXTRA_OUT) ?: return)
        btnShutter.isEnabled = false
        ic.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val data = Intent()
                    loc?.let {
                        data.putExtra(RES_LAT, it.latitude)
                        data.putExtra(RES_LNG, it.longitude)
                        data.putExtra(RES_ACC, it.accuracy)
                    }
                    address?.let { data.putExtra(RES_ADDR, it) }
                    setResult(RESULT_OK, data)
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    btnShutter.isEnabled = true
                    Toast.makeText(this@CameraActivity, "Could not take photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ---------- location + live stamp ----------

    private fun locationEnabled() =
        LocationManagerCompat.isLocationEnabled(getSystemService(LOCATION_SERVICE) as LocationManager)

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!locationEnabled()) {
            setStatus("⚠ Location is OFF. Tap here to turn on", Color.rgb(255, 120, 80))
            return
        }
        if (loc == null) setStatus("Getting GPS…", Color.rgb(255, 214, 0))
        try {
            fused.lastLocation.addOnSuccessListener { if (it != null && loc == null) onLoc(it) }
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L).build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updatesOn = true
        } catch (e: SecurityException) {
            setStatus("⚠ Location permission missing", Color.rgb(255, 120, 80))
        }
    }

    private fun onLoc(l: Location) {
        if (isFinishing || isDestroyed) return
        loc = l
        val plus = PlusCode.encode(l.latitude, l.longitude)
        camCoords.text = String.format(Locale.ENGLISH, "Lat %.6f°  Long %.6f°  (±%.0f m)", l.latitude, l.longitude, l.accuracy)
        if (address == null) camAddress.text = plus

        // look up map + address again only after moving ~25 m
        val last = lookedUpAt
        if (last != null && last.distanceTo(l) < 25f) {
            address?.let { camAddress.text = "${plus.substring(4)}, $it" }
            return
        }
        lookedUpAt = l
        val token = ++lookupToken
        setStatus("Finding address…", Color.rgb(255, 214, 0))
        val la = l.latitude
        val ln = l.longitude
        if (io.isShutdown) return
        io.execute {
            val map = Geo.mapThumb(la, ln, 256)
            runOnUiThread { if (token == lookupToken && !isDestroyed) camMap.setImageBitmap(map) }
        }
        io.execute {
            val addr = Geo.address(this, la, ln)
            runOnUiThread {
                if (token != lookupToken || isDestroyed) return@runOnUiThread
                address = addr
                if (addr != null) {
                    camAddress.text = "${PlusCode.encode(la, ln).substring(4)}, $addr"
                    setStatus("✓ Address ready", Color.rgb(90, 220, 120))
                } else {
                    camAddress.text = PlusCode.encode(la, ln)
                    setStatus("No internet: coordinates only", Color.rgb(255, 160, 60))
                }
            }
        }
    }

    private fun setStatus(text: String, color: Int) {
        camStatus.text = text
        camStatus.setTextColor(color)
    }
}
