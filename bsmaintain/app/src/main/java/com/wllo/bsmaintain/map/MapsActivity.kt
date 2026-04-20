package com.wllo.bsmaintain.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.wllo.bsmaintain.R
import com.wllo.bsmaintain.network.RetrofitClient
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color as GColor
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import androidx.core.graphics.createBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DRIVE_API_KEY = "AIzaSyC4sDtSCYJl7ZhquCKMMnsoCR9EHdRJ_zk"
        private const val PHOTO_MATCH_METERS = 50f
    }

    private lateinit var mMap: GoogleMap
    private var fileId: String = ""
    private lateinit var composeView: ComposeView

    // UI 狀態控制
    private var selectedData by mutableStateOf<JsonObject?>(null)
    private var isSheetVisible by mutableStateOf(false)
    private var currentBounds by mutableStateOf<LatLngBounds?>(null)

    // 模擬定位座標狀態 (null 代表目前是真實 GPS)
    private var mockedMarkerPosition by mutableStateOf<LatLng?>(null)
    // 追蹤標記實體
    private var lastSelectedMarker: Marker? = null
    private val allIconMarkers = mutableListOf<Marker>()

    // 真實位置標記
    private var myLocationMarker: Marker? = null

    // 照片比對結果：key = "lat_lng"，value = 對應的照片 URI 列表
    private var sitePhotos by mutableStateOf<Map<String, List<Uri>>>(emptyMap())

    // 全域載入狀態（同步雲端資料、掃描照片等）
    private var isLoading by mutableStateOf(false)
    private var loadingMessage by mutableStateOf("")

    // 相機拍照
    private var cameraPhotoUri: Uri? = null
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            Toast.makeText(this, "照片已儲存，重新掃描中…", Toast.LENGTH_SHORT).show()
            scanPhotosForSites(forceRescan = true)
        }
    }

    // 動態權限請求器（位置）
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) setupRealLocationListener()
    }

    // 動態權限請求器（照片讀取 + 位置 metadata）
    private val requestPhotoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[readMediaPermission()] == true ||
            ActivityCompat.checkSelfPermission(this, readMediaPermission()) == PackageManager.PERMISSION_GRANTED
        if (readGranted) scanPhotosForSites()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        RetrofitClient.init(applicationContext)
        fileId = intent.getStringExtra("DRIVE_FILE_ID") ?: ""
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        composeView = findViewById(R.id.compose_view)
        composeView.setContent { MaterialTheme { MainUI() } }

        // 請求位置權限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        // 請求照片讀取 + GPS metadata 存取權限
        val permissionsToRequest = mutableListOf<String>()
        val photoPermission = readMediaPermission()
        if (ActivityCompat.checkSelfPermission(this, photoPermission) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(photoPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (permissionsToRequest.isNotEmpty())
            requestPhotoPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        else
            Log.d("PhotoScan", "照片與位置權限已就緒")
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setInfoWindowAdapter(null)
        mMap.isMyLocationEnabled = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) setupRealLocationListener()

        mMap.setOnMarkerClickListener { clicked ->
            // 若點到「我的位置」三角形,轉派給其下方最近的站台 marker(螢幕距離)
            val marker = if (clicked == myLocationMarker) {
                val proj = mMap.projection
                val myPt = proj.toScreenLocation(clicked.position)
                allIconMarkers.minByOrNull {
                    val p = proj.toScreenLocation(it.position)
                    val dx = (p.x - myPt.x).toDouble(); val dy = (p.y - myPt.y).toDouble()
                    dx * dx + dy * dy
                } ?: return@setOnMarkerClickListener true
            } else clicked
            if (marker.tag == null) return@setOnMarkerClickListener true

            val prevSelected = lastSelectedMarker
            if (prevSelected != null && prevSelected != marker) {
                val wasOldMocked = (prevSelected.position == mockedMarkerPosition)
                refreshMarkerVisual(prevSelected, isSelected = false, isMocking = wasOldMocked)
            }

            lastSelectedMarker = marker
            selectedData = marker.tag as? JsonObject
            isSheetVisible = true

            val isNewMocked = (marker.position == mockedMarkerPosition)
            refreshMarkerVisual(marker, isSelected = true, isMocking = isNewMocked)

            focusOnMarkerAtTopThird(marker.position)
            true
        }

        mMap.setOnMapClickListener {
            isSheetVisible = false
            lastSelectedMarker?.let { marker ->
                refreshMarkerVisual(marker, isSelected = false, isMocking = (marker.position == mockedMarkerPosition))
            }
            lastSelectedMarker = null
        }

        mMap.setMinZoomPreference(5f)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(24.075, 120.544), 14f))
        fetchCloudData()
    }

    private fun refreshMarkerVisual(marker: Marker, isSelected: Boolean, isMocking: Boolean) {
        if (marker == myLocationMarker) return
        marker.tag as? JsonObject ?: return
        marker.setIcon(createCircleIcon(isSelected, isMocking))
    }

    @SuppressLint("MissingPermission")
    private fun setupRealLocationListener() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val currentPos = LatLng(location.latitude, location.longitude)
                runOnUiThread {
                    if (myLocationMarker == null) {
                        myLocationMarker = mMap.addMarker(MarkerOptions().position(currentPos).title("我的位置").anchor(0.5f, 0.5f).icon(createTriangleMarker()).zIndex(100f).flat(true))
                    } else {
                        myLocationMarker?.position = currentPos
                    }
                }
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener)
        } catch (e: Exception) {}
    }

    private fun createTriangleMarker(): BitmapDescriptor {
        val size = 80
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), intArrayOf("#FFEB3B".toColorInt(), "#F57C00".toColorInt()), null, Shader.TileMode.CLAMP)
        }
        val path = Path().apply { moveTo(size / 2f, 0f); lineTo(0f, size.toFloat()); lineTo(size.toFloat(), size.toFloat()); close() }
        canvas.drawPath(path, paint)
        val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = GColor.WHITE; strokeWidth = 4f }
        canvas.drawPath(path, strokePaint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun startMockLocation(marker: Marker) {
        val latLng = marker.position
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LAT, latLng.latitude)
            putExtra(MockLocationService.EXTRA_LNG, latLng.longitude)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        mockedMarkerPosition = latLng
        allIconMarkers.forEach { m ->
            refreshMarkerVisual(m, isSelected = (m == lastSelectedMarker), isMocking = (m.position == latLng))
        }
        Toast.makeText(this, "定位模擬啟動", Toast.LENGTH_SHORT).show()
    }

    private fun stopMockLocation() {
        startService(Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        })
        mockedMarkerPosition = null
        allIconMarkers.forEach { m -> refreshMarkerVisual(m, isSelected = (m == lastSelectedMarker), isMocking = false) }
        Toast.makeText(this, "恢復真實 GPS", Toast.LENGTH_SHORT).show()
    }

    private fun getLabelFromData(obj: JsonObject): String {
        val allKeys = obj.keySet()
        val nameKey = allKeys.find { it.contains("名") || it.contains("name", true) || it.contains("站") }
        val snKey = allKeys.find { it.contains("編號") || it.contains("number", true) || it.contains("SN", true) }
        val siteName = nameKey?.let { obj.get(it).asString } ?: "未命名"
        val rawValue = snKey?.let { obj.get(it).toString() }?.replace("\"", "") ?: ""
        val siteNumber = if (rawValue.contains(".")) rawValue.toDoubleOrNull()?.toLong()?.toString() ?: rawValue else rawValue
        return if (siteNumber.isNotEmpty()) "$siteName $siteNumber" else siteName
    }

    private fun focusOnMarkerAtTopThird(latLng: LatLng) {
        val projection = mMap.projection
        val markerPoint = projection.toScreenLocation(latLng)
        val mapView = findViewById<android.view.View>(R.id.map)
        val mapHeight = mapView.height
        if (mapHeight <= 0) return
        val targetPoint = Point(markerPoint.x, markerPoint.y + (mapHeight / 6))
        mMap.animateCamera(CameraUpdateFactory.newLatLng(projection.fromScreenLocation(targetPoint)))
    }

    // ── 照片相關 ──────────────────────────────────────────────

    private fun readMediaPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    private fun latLngKey(pos: LatLng) = "${pos.latitude}_${pos.longitude}"

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0]
    }

    // ── 照片快取 ──────────────────────────────────────────────

    /** 用照片數量 + 最新修改時間作為指紋，快速判斷是否需要重新掃描 */
    private fun getMediaFingerprint(): String {
        val count = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, null
        )?.use { it.count } ?: 0

        val latest = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATE_MODIFIED),
            null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

        return "${count}_${latest}"
    }

    private fun loadPhotoCache(): Map<String, List<Uri>>? {
        val prefs = getSharedPreferences("photo_cache", MODE_PRIVATE)
        val storedFingerprint = prefs.getString("fingerprint_$fileId", null)
        val currentFingerprint = getMediaFingerprint()

        if (storedFingerprint == currentFingerprint) {
            val cacheFile = java.io.File(cacheDir, "photo_cache_$fileId.json")
            if (cacheFile.exists()) {
                return try {
                    val type = object : TypeToken<Map<String, List<String>>>() {}.type
                    val map: Map<String, List<String>> = Gson().fromJson(cacheFile.readText(), type)
                    Log.d("PhotoScan", "使用照片快取（${map.values.sumOf { it.size }} 張）")
                    map.mapValues { entry -> entry.value.map { Uri.parse(it) } }
                } catch (e: Exception) {
                    Log.w("PhotoScan", "快取讀取失敗：${e.message}")
                    null
                }
            }
        }
        return null
    }

    private fun savePhotoCache(matched: Map<String, List<Uri>>) {
        try {
            val cacheFile = java.io.File(cacheDir, "photo_cache_$fileId.json")
            val serializable = matched.mapValues { entry -> entry.value.map { it.toString() } }
            cacheFile.writeText(Gson().toJson(serializable))
            val prefs = getSharedPreferences("photo_cache", MODE_PRIVATE)
            prefs.edit().putString("fingerprint_$fileId", getMediaFingerprint()).apply()
            Log.d("PhotoScan", "照片快取已儲存（${matched.values.sumOf { it.size }} 張）")
        } catch (e: Exception) {
            Log.w("PhotoScan", "快取儲存失敗：${e.message}")
        }
    }

    // ── 方案 C：EXIF Title 優先，GPS 距離為輔 ─────────────────

    /** 建立 siteName → latLngKey 的對照表 */
    private fun buildSiteNameMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (marker in allIconMarkers) {
            val obj = marker.tag as? JsonObject ?: continue
            val nameKey = obj.keySet().find { it.contains("名") || it.contains("name", true) || it.contains("站") }
            val siteName = nameKey?.let { obj.get(it).asString } ?: continue
            map[siteName] = latLngKey(marker.position)
        }
        return map
    }

    private fun scanPhotosForSites(forceRescan: Boolean = false) {
        val sitePositions = allIconMarkers.map { it.position }
        if (sitePositions.isEmpty()) {
            Log.w("PhotoScan", "尚無 marker，跳過掃描")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 嘗試讀��快取（除非強制重新掃描）
            if (!forceRescan) {
                withContext(Dispatchers.Main) { isLoading = true; loadingMessage = "正在讀取照片快取…" }
                val cached = loadPhotoCache()
                if (cached != null) {
                    withContext(Dispatchers.Main) { sitePhotos = cached; isLoading = false; loadingMessage = "" }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) { isLoading = true; loadingMessage = "正在掃描手機照片…" }
            Log.d("PhotoScan", "開始掃描照片…")

            val matched = mutableMapOf<String, MutableList<Uri>>()
            val siteNameMap = withContext(Dispatchers.Main) { buildSiteNameMap() }  // siteName → latLngKey（Marker API 必須在 main thread）

            val projection = arrayOf(MediaStore.Images.Media._ID)
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    try {
                        val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            MediaStore.setRequireOriginal(uri) else uri

                        var matchedByTitle = false
                        val latLong = FloatArray(2)
                        var hasGps = false

                        // 讀取 EXIF（一次讀取 Title + GPS）
                        val exif = try {
                            contentResolver.openInputStream(originalUri)?.use { ExifInterface(it) }
                        } catch (_: UnsupportedOperationException) {
                            contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                        }

                        if (exif != null) {
                            // 優先：EXIF Title 比對 siteName
                            val title = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                                ?: exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                            if (!title.isNullOrBlank()) {
                                for ((siteName, key) in siteNameMap) {
                                    if (title.contains(siteName, ignoreCase = true)) {
                                        matched.getOrPut(key) { mutableListOf() }.add(uri)
                                        matchedByTitle = true
                                        break
                                    }
                                }
                            }

                            // 輔助：GPS 距離比對（僅在 Title 未命中時）
                            if (!matchedByTitle) {
                                hasGps = exif.getLatLong(latLong)
                            }
                        }

                        if (!matchedByTitle && hasGps) {
                            for (pos in sitePositions) {
                                if (distanceBetween(latLong[0].toDouble(), latLong[1].toDouble(), pos.latitude, pos.longitude) <= PHOTO_MATCH_METERS) {
                                    matched.getOrPut(latLngKey(pos)) { mutableListOf() }.add(uri)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 儲存快取
            val result = matched.mapValues { it.value.toList() }
            savePhotoCache(result)

            withContext(Dispatchers.Main) {
                sitePhotos = result
                isLoading = false
                loadingMessage = ""
                if (forceRescan) Toast.makeText(this@MapsActivity, "掃描完成（${result.values.sumOf { it.size }} 張照片）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 相機：關閉模擬定位後拍照 ──────────────────────────────

    private fun launchCamera() {
        if (mockedMarkerPosition != null) {
            stopMockLocation()
            Toast.makeText(this, "已關閉模擬定位，使用真實 GPS 拍照", Toast.LENGTH_SHORT).show()
        }
        startActivity(Intent(this, com.wllo.bsmaintain.camera.StationCameraActivity::class.java).apply {
            putExtra(com.wllo.bsmaintain.camera.StationCameraActivity.EXTRA_FROM_MAP, true)
        })
    }

    private fun openPhoto(uri: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }

    // ── Compose UI ────────────────────────────────────────────

    @Composable
    fun MainUI() {
        Box(modifier = Modifier.fillMaxSize()) {
            // 頂部載入狀態列
            AnimatedVisibility(
                visible = isLoading,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.ui.graphics.Color(0xFF1A73E8),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = loadingMessage,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 右上角 FAB
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(onClick = { if (!isLoading) focusOnCentroid() }, containerColor = if (isLoading) androidx.compose.ui.graphics.Color.LightGray else androidx.compose.ui.graphics.Color.White, contentColor = androidx.compose.ui.graphics.Color(0xFF1A73E8), shape = CircleShape) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Focus")
                }
                FloatingActionButton(onClick = { if (!isLoading) startActivity(android.content.Intent(this@MapsActivity, com.wllo.bsmaintain.SpeedTestActivity::class.java)) }, containerColor = if (isLoading) androidx.compose.ui.graphics.Color.LightGray else androidx.compose.ui.graphics.Color.White, contentColor = androidx.compose.ui.graphics.Color(0xFF1A73E8), shape = CircleShape) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = "SpeedTest")
                }
            }
            FixedBottomInfoPanel(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    private fun focusOnCentroid() {
        currentBounds?.let { bounds ->
            val center = LatLng(
                (bounds.northeast.latitude + bounds.southwest.latitude) / 2,
                (bounds.northeast.longitude + bounds.southwest.longitude) / 2
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 13f))
        }
    }

    @Composable
    fun FixedBottomInfoPanel(modifier: Modifier = Modifier) {
        AnimatedVisibility(
            visible = isSheetVisible,
            modifier = modifier,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = androidx.compose.ui.graphics.Color.White,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    // 標題列
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val title = selectedData?.get("站名")?.asString?.replace("\"", "") ?: "詳細資料"
                        Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF1A73E8))
                        IconButton(onClick = { isSheetVisible = false }) {
                            Icon(Icons.Default.Close, tint = androidx.compose.ui.graphics.Color.Gray, contentDescription = "Close")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                    // 照片縮圖區塊
                    val photoKey = lastSelectedMarker?.position?.let { latLngKey(it) }
                    val photos = photoKey?.let { sitePhotos[it] } ?: emptyList()
                    if (photos.isNotEmpty()) {
                        Text(
                            text = "相關照片（${photos.size} 張）",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color(0xFF1A73E8)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(photos) { uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { openPhoto(uri) }
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    }

                    // 站點資料列表
                    Box(modifier = Modifier.heightIn(max = 200.dp)) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            selectedData?.let { obj ->
                                items(obj.keySet().toList()) { key ->
                                    val formattedValue = obj.get(key).toString().replace("\"", "").replace("\\n", "\n").replace("\\r", "")
                                    DataRow(key, formattedValue)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // 操作按鈕
                    val buttonsEnabled = !isLoading
                    val disabledGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFBDBDBD), androidx.compose.ui.graphics.Color(0xFF9E9E9E)))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        val isMocking = (mockedMarkerPosition != null && lastSelectedMarker?.position == mockedMarkerPosition)
                        val blueGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF42A5F5), androidx.compose.ui.graphics.Color(0xFF1976D2)))
                        val redGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFF5252), androidx.compose.ui.graphics.Color(0xFFD32F2F)))
                        val greenGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF66BB6A), androidx.compose.ui.graphics.Color(0xFF2E7D32)))
                        val orangeGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFFB74D), androidx.compose.ui.graphics.Color(0xFFF57C00)))
                        val tealGrad = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF4DD0E1), androidx.compose.ui.graphics.Color(0xFF00838F)))

                        // 模擬定位
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (!buttonsEnabled) disabledGrad else if (isMocking) redGrad else blueGrad).clickable(enabled = buttonsEnabled) {
                            lastSelectedMarker?.let { marker -> if (isMocking) stopMockLocation() else startMockLocation(marker) }
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AirplanemodeActive, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White, contentDescription = "Mock")
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        // 導航
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (!buttonsEnabled) disabledGrad else greenGrad).clickable(enabled = buttonsEnabled) {
                            lastSelectedMarker?.let { marker ->
                                val intentUri = "https://www.google.com/maps/dir/?api=1&destination=${marker.position.latitude},${marker.position.longitude}".toUri()
                                startActivity(Intent(Intent.ACTION_VIEW, intentUri).setPackage("com.google.android.apps.maps"))
                            }
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Explore, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White, contentDescription = "Route")
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        // 拍照（自動關閉模擬定位）
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (!buttonsEnabled) disabledGrad else orangeGrad).clickable(enabled = buttonsEnabled) {
                            launchCamera()
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CameraAlt, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White, contentDescription = "Camera")
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        // 重新掃描照片
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (!buttonsEnabled) disabledGrad else tealGrad).clickable(enabled = buttonsEnabled) {
                            scanPhotosForSites(forceRescan = true)
                        }, contentAlignment = Alignment.Center) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White, contentDescription = "Rescan")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DataRow(key: String, value: String) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(text = key, color = androidx.compose.ui.graphics.Color.Gray, fontSize = 13.sp)
            SelectionContainer {
                Text(text = value, color = androidx.compose.ui.graphics.Color.Black, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // ── 雲端資料 ──────────────────────────────────────────────

    private fun fetchCloudData() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; loadingMessage = "正在同步雲端站台資料…" }
            try {
                val cacheFile = java.io.File(cacheDir, "drive_cache_$fileId.json")
                val prefs = getSharedPreferences("drive_cache", android.content.Context.MODE_PRIVATE)
                val storedModifiedTime = prefs.getString("modified_$fileId", null)

                val modifiedTime = try {
                    RetrofitClient.driveMetaService.getFileMetadata(fileId, apiKey = DRIVE_API_KEY).modifiedTime
                } catch (e: Exception) {
                    Log.w("MapCache", "無法取得 metadata：${e.message}")
                    null
                }

                val jsonElement = if (modifiedTime != null && modifiedTime == storedModifiedTime && cacheFile.exists()) {
                    Log.d("MapCache", "檔案未變動，使用快取")
                    com.google.gson.Gson().fromJson(cacheFile.readText(), com.google.gson.JsonElement::class.java)
                } else {
                    withContext(Dispatchers.Main) { loadingMessage = "正在下載站台資料…" }
                    Log.d("MapCache", "下載新資料 modifiedTime=$modifiedTime")
                    val json = RetrofitClient.driveService.getUniversalData(fileId)
                    cacheFile.writeText(json.toString())
                    if (modifiedTime != null) prefs.edit().putString("modified_$fileId", modifiedTime).apply()
                    json
                }

                withContext(Dispatchers.Main) {
                    loadingMessage = "正在繪製站台標記…"
                    drawDynamicMarkersSequentially(jsonElement)
                }
            } catch (e: Exception) {
                Log.e("MapError", "抓取失敗：${e.message}")
                withContext(Dispatchers.Main) { isLoading = false; loadingMessage = "" }
            }
        }
    }

    private fun drawDynamicMarkersSequentially(jsonElement: JsonElement) {
        if (!jsonElement.isJsonArray) { isLoading = false; loadingMessage = ""; return }
        val jsonArray = jsonElement.asJsonArray
        val boundsBuilder = LatLngBounds.Builder()
        var sumLat = 0.0; var sumLng = 0.0; var count = 0
        allIconMarkers.clear()
        for (element in jsonArray) {
            val obj = element.asJsonObject
            val latKey = obj.keySet().find { it.contains("緯") || it.contains("lat", true) || it.contains("latitude", true) }
            val lngKey = obj.keySet().find { it.contains("經") || it.contains("lng", true) || it.contains("longitude", true) }
            if (latKey != null && lngKey != null) {
                val pos = LatLng(obj.get(latKey).asDouble, obj.get(lngKey).asDouble)
                sumLat += pos.latitude; sumLng += pos.longitude; count++
                boundsBuilder.include(pos)
                val circleMarker = mMap.addMarker(MarkerOptions().position(pos).anchor(0.5f, 0.5f).icon(createCircleIcon(false, false)).zIndex(10f))
                circleMarker?.tag = obj
                if (circleMarker != null) allIconMarkers.add(circleMarker)
                mMap.addMarker(MarkerOptions().position(pos).icon(createLabelIcon(getLabelFromData(obj))).anchor(-0.2f, 0.5f).zIndex(5f))
            }
        }
        if (count > 0) {
            currentBounds = boundsBuilder.build()
            focusOnCentroid()
        }
        // 還原先前的 mock 狀態（app 收到背景後 Activity 被重建時）
        MockLocationService.getPersistedMock(this)?.let { (mLat, mLng) ->
            val pos = LatLng(mLat, mLng)
            mockedMarkerPosition = pos
            allIconMarkers.forEach { m ->
                refreshMarkerVisual(m, isSelected = (m == lastSelectedMarker), isMocking = (m.position == pos))
            }
        }
        // Marker 載入完成後，掃描手機照片
        if (ActivityCompat.checkSelfPermission(this, readMediaPermission()) == PackageManager.PERMISSION_GRANTED) {
            scanPhotosForSites()
        } else {
            isLoading = false; loadingMessage = ""
        }
    }

    private fun createCircleIcon(isSelected: Boolean, isMocking: Boolean): BitmapDescriptor {
        val radius = 36f; val glowSize = if (isSelected || isMocking) 27f else 0f
        val size = ((radius + glowSize + 10) * 2).toInt()
        val bitmap = createBitmap(size, size); val canvas = Canvas(bitmap)
        val center = size / 2f
        if (isSelected || isMocking) {
            val color = if (isMocking) GColor.RED else GColor.GREEN
            val glowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; this.color = color; strokeWidth = 18f; setShadowLayer(22.5f, 0f, 0f, color) }
            canvas.drawCircle(center, center, radius + 3f, glowPaint)
        }
        val gradientPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL
            shader = RadialGradient(center - (radius * 0.3f), center - (radius * 0.3f), radius * 1.5f, intArrayOf(
                "#64B5F6".toColorInt(), "#1976D2".toColorInt(), "#0D47A1".toColorInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(center, center, radius, gradientPaint)
        val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = GColor.WHITE; strokeWidth = 6f }
        canvas.drawCircle(center, center, radius, strokePaint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createLabelIcon(label: String): BitmapDescriptor {
        val paint = Paint().apply { color = GColor.BLACK; textSize = 42f; isAntiAlias = true; setShadowLayer(6f, 3f, 3f, GColor.WHITE) }
        val textBounds = Rect(); paint.getTextBounds(label, 0, label.length, textBounds)
        val width = textBounds.width() + 20; val height = textBounds.height() + 40
        val bitmap = createBitmap(width, height); val canvas = Canvas(bitmap)
        canvas.drawText(label, 10f, height / 2f - textBounds.centerY(), paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
