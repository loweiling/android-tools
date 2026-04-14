package com.example.bsmaintain.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var isMockDetected = false

    private var selectedStation: JsonObject? = null
    private var selectedStationName: String = ""
    private var selectedStationLat: Double = 0.0
    private var selectedStationLng: Double = 0.0
    private var generalMode: Boolean = false

    companion object {
        private const val STATION_RADIUS_M = 80f
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_station_camera)

        previewView = findViewById(R.id.preview_view)
        stationNameText = findViewById(R.id.station_name_text)
        stationCoordText = findViewById(R.id.station_coord_text)
        mockWarningText = findViewById(R.id.mock_warning_text)
        shutterButton = findViewById(R.id.shutter_button)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // 強制關閉 app 自帶的模擬定位服務
        stopService(Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        })

        shutterButton.setOnClickListener { onShutterClicked() }

        val needed = mutableListOf(Manifest.permission.CAMERA)
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
            startLocationUpdates()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("StationCamera", "bind failed", e)
                Toast.makeText(this, "相機初始化失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

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
            Log.e("StationCamera", "location permission denied", e)
        }
    }

    private fun isLocationMock(loc: Location): Boolean = loc.isMock

    private fun onLocationUpdate(loc: Location) {
        currentLocation = loc
        isMockDetected = isLocationMock(loc)
        mockWarningText.visibility = if (isMockDetected) android.view.View.VISIBLE else android.view.View.GONE
        shutterButton.isEnabled = !isMockDetected
        shutterButton.alpha = if (isMockDetected) 0.4f else 1f

        if (isMockDetected) {
            // 再次嘗試關閉 app 自己的模擬定位服務
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
                        Log.e("StationCamera", "sync failed", e)
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
            // 距離最近站台都超過 80m → 一般相機模式
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
            Log.e("StationCamera", "read cache failed", e); null
        }
    }

    private fun onShutterClicked() {
        if (isMockDetected) {
            Toast.makeText(this, "請先關閉模擬定位", Toast.LENGTH_SHORT).show(); return
        }
        if (!generalMode && selectedStation == null) {
            Toast.makeText(this, "定位中,請稍候", Toast.LENGTH_SHORT).show(); return
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
                        Log.e("StationCamera", "exif write failed", e)
                    }
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val patched = XmpWriter.injectXmp(bytes, selectedStationName)
                            contentResolver.openOutputStream(uri, "wt")?.use { it.write(patched) }
                        }
                    } catch (e: Exception) {
                        Log.e("StationCamera", "xmp write failed", e)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, update, null, null)
                }
                val msg = if (generalMode) "已儲存" else "已儲存到 $selectedStationName"
                runOnUiThread {
                    Toast.makeText(this@StationCameraActivity, msg, Toast.LENGTH_SHORT).show()
                    shutterButton.isEnabled = true
                    // 若是被其他 app 以 IMAGE_CAPTURE 呼叫,把 URI 回傳給對方
                    if (intent?.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE ||
                        intent?.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE_SECURE) {
                        val resultIntent = Intent().apply { data = uri }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("StationCamera", "capture failed", exception)
                runOnUiThread {
                    Toast.makeText(this@StationCameraActivity, "拍照失敗: ${exception.message}", Toast.LENGTH_LONG).show()
                    shutterButton.isEnabled = true
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        executor.shutdown()
    }
}
