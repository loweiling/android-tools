package com.example.bsmaintain

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class SpeedTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speedtest)

        findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btn_share).setOnClickListener {
            shareScreenshot()
        }
    }

    private fun shareScreenshot() {
        val rootView = window.decorView.rootView
        rootView.isDrawingCacheEnabled = true
        val bitmap = Bitmap.createBitmap(rootView.drawingCache)
        rootView.isDrawingCacheEnabled = false

        try {
            val file = File(cacheDir, "speedtest_screenshot.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享網速測試結果"))
        } catch (e: Exception) {
            Toast.makeText(this, "截圖失敗：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
