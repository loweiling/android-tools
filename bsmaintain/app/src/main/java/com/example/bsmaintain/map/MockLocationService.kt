package com.example.bsmaintain.map

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.bsmaintain.R
import kotlinx.coroutines.*

class MockLocationService : Service() {

    private val providerName = LocationManager.GPS_PROVIDER
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_LAT = "EXTRA_LAT"
        const val EXTRA_LNG = "EXTRA_LNG"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mock_location_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startForeground(NOTIFICATION_ID, buildNotification(lat, lng))
                startMocking(lat, lng)
            }
            ACTION_STOP -> {
                stopMocking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "WrongConstant")
    private fun startMocking(lat: Double, lng: Double) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            try { locationManager.removeTestProvider(providerName) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = ProviderProperties.Builder()
                    .setPowerUsage(1).setAccuracy(1)
                    .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                    .build()
                locationManager.addTestProvider(providerName, properties)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(providerName, false, false, false, false, true, true, true, 1, 1)
            }
            locationManager.setTestProviderEnabled(providerName, true)
        } catch (_: Exception) {}

        mockJob = serviceScope.launch {
            while (isActive) {
                try {
                    val mockLocation = Location(providerName).apply {
                        latitude = lat
                        longitude = lng
                        altitude = 0.0
                        time = System.currentTimeMillis()
                        accuracy = 1.0f
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    locationManager.setTestProviderLocation(providerName, mockLocation)
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stopMocking() {
        mockJob?.cancel()
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try { locationManager.removeTestProvider(providerName) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMocking()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "模擬定位", NotificationManager.IMPORTANCE_LOW).apply {
                description = "模擬 GPS 位置持續運作中"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(lat: Double, lng: Double): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("模擬定位中")
            .setContentText("緯度: ${"%.5f".format(lat)}, 經度: ${"%.5f".format(lng)}")
            .setSmallIcon(R.drawable.ic_point_24)
            .setOngoing(true)
            .build()
}