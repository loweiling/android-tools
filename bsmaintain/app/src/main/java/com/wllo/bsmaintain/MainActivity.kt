package com.wllo.bsmaintain
import com.wllo.bsmaintain.R

import android.content.Intent
import android.os.Bundle

import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wllo.bsmaintain.map.MapsActivity
import com.wllo.bsmaintain.sms.SmsPermissions

import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.rememberCameraPositionState
import retrofit2.http.GET
import retrofit2.http.Query

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 設定畫面
        setContentView(R.layout.activity_main)

        // 2. 找到按鈕
        val btnOpenMap = findViewById<Button>(R.id.btnOpenMap)
        val editFileId = findViewById<EditText>(R.id.editFileId)

        // 先幫使用者填好最常用的那個 ID
        editFileId.setText("1ilulgTwD-_SGthF-o4RgKfYsaoUSe_4l")
        // 3. 設定點擊監聽器
        btnOpenMap.setOnClickListener {
            val intent = Intent(this, MapsActivity::class.java)
            intent.putExtra("DRIVE_FILE_ID", "1ilulgTwD-_SGthF-o4RgKfYsaoUSe_4l")
            startActivity(intent)
        }

        val btnOpenSpeedTest = findViewById<Button>(R.id.btnOpenSpeedTest)
        btnOpenSpeedTest.setOnClickListener {
            startActivity(Intent(this, SpeedTestActivity::class.java))
        }

        // SMS auto-login: 啟動時請求簡訊權限（Phase 1：只 Log/Toast 驗證接收）
        SmsPermissions.requestIfNeeded(this)
    }
}
