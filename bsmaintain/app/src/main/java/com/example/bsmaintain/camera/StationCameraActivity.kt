package com.example.bsmaintain.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.bsmaintain.Constants
import com.example.bsmaintain.R
import com.example.bsmaintain.map.MockLocationService
import com.example.bsmaintain.network.RetrofitClient
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class StationCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var stationNameText: TextView
    private lateinit var stationCoordText: TextView
    private lateinit var mockWarningText: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var btnFlipCamera: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var thumbnailPreview: ImageView
    private lateinit var btnEditPhoto: ImageButton
    private lateinit var recordingTimer: TextView
    private lateinit var zoomRatioText: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var isMockDetected = false

    private var selectedStation: JsonObject? = null
    private var selectedStationName: String = ""
    private var selectedStationLat: Double = 0.0
    private var selectedStationLng: Double = 0.0
    private var generalMode: Boolean = false

    // Camera state
    private var isBackCamera = true
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isVideoMode = false
    private var isRecording = false
    private var lastCapturedUri: Uri? = null

    // Recording timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val min = recordingSeconds / 60
            val sec = recordingSeconds % 60
            recordingTimer.text = String.format("%02d:%02d", min, sec)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // Zoom
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val zoomHideHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val STATION_RADIUS_M = 80f
        private const val TAG = "StationCamera"
    }

    private val locationListener = LocationListener { loc ->
        onLocationUpdate(loc)
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val camOk = result[Manifest.permission.CAMERA] == true
        val locOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (camOk && locOk) {
            startCamera()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "需要相機與定位權限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_station_camera)

        previewView = findViewById(R.id.preview_view)
        stationNameText = findViewById(R.id.station_name_text)
        stationCoordText = findViewById(R.id.station_coord_text)
        mockWarningText = findViewById(R.id.mock_warning_text)
        shutterButton = findViewById(R.id.shutter_button)
        btnFlipCamera = findViewById(R.id.btn_flip_camera)
        btnFlash = findViewById(R.id.btn_flash)
        modePhoto = findViewById(R.id.mode_photo)
        modeVideo = findViewById(R.id.mode_video)
        thumbnailPreview = findViewById(R.id.thumbnail_preview)
        btnEditPhoto = findViewById(R.id.btn_edit_photo)
        recordingTimer = findViewById(R.id.recording_timer)
        zoomRatioText = findViewById(R.id.zoom_ratio_text)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        stopService(Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        })

        shutterButton.setOnClickListener { onShutterClicked() }
        btnFlipCamera.setOnClickListener { flipCamera() }
        btnFlash.setOnClickListener { toggleFlash() }
        modePhoto.setOnClickListener { switchMode(false) }
        modeVideo.setOnClickListener { switchMode(true) }
        thumbnailPreview.setOnClickListener { openLastCaptured() }
        btnEditPhoto.setOnClickListener { editLastCaptured() }

        // Pinch-to-zoom
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false
                val newZoom = zoomState.zoomRatio * detector.scaleFactor
                cam.cameraControl.setZoomRatio(newZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio))

                zoomRatioText.text = String.format("%.1fx", newZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio))
                zoomRatioText.visibility = View.VISIBLE
                zoomHideHandler.removeCallbacksAndMessages(null)
                zoomHideHandler.postDelayed({ zoomRatioText.visibility = View.GONE }, 1500)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        updateFlashIcon()

        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
            startLocationUpdates()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }

        loadLastPhoto()
    }

    private fun loadLastPhoto() {
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                arrayOf("Pictures/BsMaintain%")
            else null
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    lastCapturedUri = uri
                    updateThumbnail(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLastPhoto failed", e)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                Toast.makeText(this, "相機初始化失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        isBackCamera = !isBackCamera
        startCamera()
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> {
                btnFlash.setImageResource(R.drawable.ic_flash_on)
                btnFlash.contentDescription = "閃光燈 開"
            }
            ImageCapture.FLASH_MODE_AUTO -> {
                btnFlash.setImageResource(R.drawable.ic_flash_auto)
                btnFlash.contentDescription = "閃光燈 自動"
            }
            else -> {
                btnFlash.setImageResource(R.drawable.ic_flash_off)
                btnFlash.contentDescription = "閃光燈 關"
            }
        }
        btnFlash.alpha = 1.0f
    }

    private fun switchMode(toVideo: Boolean) {
        if (isVideoMode == toVideo) return
        isVideoMode = toVideo

        modePhoto.textColor(if (!isVideoMode) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
        modePhoto.setTypeface(null, if (!isVideoMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        modeVideo.textColor(if (isVideoMode) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
        modeVideo.setTypeface(null, if (isVideoMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        if (isVideoMode) {
            shutterButton.setBackgroundResource(R.drawable.shutter_record_bg)
            shutterButton.setImageDrawable(null)
        } else {
            shutterButton.setBackgroundResource(R.drawable.shutter_bg)
            shutterButton.setImageResource(android.R.drawable.ic_menu_camera)
        }
    }

    private fun TextView.textColor(color: Int) {
        setTextColor(color)
    }

    private fun onShutterClicked() {
        if (isMockDetected) {
            Toast.makeText(this, "請先關閉模擬定位", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVideoMode) {
            if (isRecording) stopRecording() else startRecording()
        } else {
            capturePhoto()
        }
    }

    private fun capturePhoto() {
        if (!generalMode && selectedStation == null) {
            Toast.makeText(this, "定位中,請稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val capture = imageCapture ?: return

        val prefix = if (generalMode) "IMG" else "STATION"
        val filename = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BsMaintain")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val output = ImageCapture.OutputFileOptions.Builder(contentResolver, collection, values).build()

        shutterButton.isEnabled = false
        capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val uri = results.savedUri ?: return
                if (!generalMode) {
                    try {
                        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, selectedStationName)
                            val loc = Location("station").apply {
                                latitude = selectedStationLat
                                longitude = selectedStationLng
                            }
                            exif.setGpsInfo(loc)
                            exif.saveAttributes()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "exif write failed", e)
                    }
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val patched = XmpWriter.injectXmp(bytes, selectedStationName)
                            contentResolver.openOutputStream(uri, "wt")?.use { it.write(patched) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "xmp write failed", e)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, update, null, null)
                }
                lastCapturedUri = uri
                val msg = if (generalMode) "已儲存" else "已儲存到 $selectedStationName"
                runOnUiThread {
                    Toast.makeText(this@StationCameraActivity, msg, Toast.LENGTH_SHORT).show()
                    shutterButton.isEnabled = true
                    updateThumbnail(uri)

                    if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE ||
                        intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE) {
                        val resultIntent = Intent().apply { data = uri }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture failed", exception)
                runOnUiThread {
                    Toast.makeText(this@StationCameraActivity, "拍照失敗: ${exception.message}", Toast.LENGTH_LONG).show()
                    shutterButton.isEnabled = true
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return

        val filename = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BsMaintain")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        activeRecording = vc.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            lastCapturedUri = event.outputResults.outputUri
                            Toast.makeText(this, "錄影已儲存", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "video recording error: ${event.error}")
                            Toast.makeText(this, "錄影失敗", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

        isRecording = true
        recordingSeconds = 0
        recordingTimer.text = "00:00"
        recordingTimer.visibility = View.VISIBLE
        timerHandler.postDelayed(timerRunnable, 1000)

        // Change button appearance to indicate recording
        shutterButton.setBackgroundResource(R.drawable.shutter_bg)
        shutterButton.setImageDrawable(null)

        // Show a red square inside
        shutterButton.setBackgroundResource(R.drawable.shutter_record_bg)
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false

        timerHandler.removeCallbacks(timerRunnable)
        recordingTimer.visibility = View.GONE

        shutterButton.setBackgroundResource(R.drawable.shutter_record_bg)
        shutterButton.setImageDrawable(null)
    }

    private fun updateThumbnail(uri: Uri) {
        try {
            // Read EXIF orientation
            val rotation = contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                val raw = BitmapFactory.decodeStream(stream, null, options) ?: return
                val bitmap = if (rotation != 0f) {
                    val matrix = Matrix().apply { postRotate(rotation) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                } else raw

                thumbnailPreview.setImageBitmap(bitmap)
                thumbnailPreview.visibility = View.VISIBLE
                btnEditPhoto.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "thumbnail failed", e)
        }
    }

    private fun openLastCaptured() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    private fun editLastCaptured() {
        val uri = lastCapturedUri ?: return
        val intent = Intent(this, PhotoAnnotationActivity::class.java).apply {
            data = uri
        }
        startActivity(intent)
    }

    // ---- Location / Station logic (unchanged) ----

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationUpdate)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission denied", e)
        }
    }

    private fun isLocationMock(loc: Location): Boolean = loc.isMock

    private fun onLocationUpdate(loc: Location) {
        currentLocation = loc
        isMockDetected = isLocationMock(loc)
        mockWarningText.visibility = if (isMockDetected) View.VISIBLE else View.GONE
        shutterButton.isEnabled = !isMockDetected
        shutterButton.alpha = if (isMockDetected) 0.4f else 1f

        if (isMockDetected) {
            stopService(Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_STOP
            })
        }

        findNearestStation(loc)
    }

    private var syncing = false

    private fun findNearestStation(userLoc: Location) {
        val json = readCachedStations() ?: run {
            if (!syncing) {
                syncing = true
                stationNameText.text = "站台資料同步中…"
                stationCoordText.text = ""
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        RetrofitClient.init(applicationContext)
                        val j = RetrofitClient.driveService.getUniversalData(Constants.DRIVE_FILE_ID)
                        File(cacheDir, "drive_cache_${Constants.DRIVE_FILE_ID}.json").writeText(j.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "sync failed", e)
                    }
                    withContext(Dispatchers.Main) {
                        syncing = false
                        currentLocation?.let { findNearestStation(it) }
                    }
                }
            }
            return
        }
        if (!json.isJsonArray) return
        var best: JsonObject? = null
        var bestDist = Float.MAX_VALUE
        var bestLat = 0.0
        var bestLng = 0.0
        for (el in json.asJsonArray) {
            val obj = el.asJsonObject
            val latKey = obj.keySet().find { it.contains("緯") || it.contains("lat", true) || it.contains("latitude", true) } ?: continue
            val lngKey = obj.keySet().find { it.contains("經") || it.contains("lng", true) || it.contains("longitude", true) } ?: continue
            val lat = obj.get(latKey)?.asDouble ?: continue
            val lng = obj.get(lngKey)?.asDouble ?: continue
            val out = FloatArray(1)
            Location.distanceBetween(userLoc.latitude, userLoc.longitude, lat, lng, out)
            if (out[0] < bestDist) {
                bestDist = out[0]; best = obj; bestLat = lat; bestLng = lng
            }
        }
        best ?: return
        if (bestDist > STATION_RADIUS_M) {
            generalMode = true
            selectedStation = null
            selectedStationName = ""
            stationNameText.text = "一般相機模式"
            stationCoordText.text = String.format(Locale.US, "附近無站台 (最近 %.0f m)", bestDist)
            return
        }
        generalMode = false
        selectedStation = best
        selectedStationLat = bestLat
        selectedStationLng = bestLng
        val nameKey = best.keySet().find { it.contains("名") || it.contains("name", true) || it.contains("站") }
        selectedStationName = nameKey?.let { best.get(it).asString } ?: "未命名站台"
        stationNameText.text = selectedStationName
        stationCoordText.text = String.format(Locale.US, "%.6f, %.6f  (距離 %.0f m)", bestLat, bestLng, bestDist)
    }

    private fun readCachedStations(): JsonElement? {
        val f = File(cacheDir, "drive_cache_${Constants.DRIVE_FILE_ID}.json")
        if (!f.exists()) return null
        return try {
            Gson().fromJson(f.readText(), JsonElement::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "read cache failed", e); null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        timerHandler.removeCallbacksAndMessages(null)
        zoomHideHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}
