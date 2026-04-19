package com.example.bsmaintain.camera

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.bsmaintain.R

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private val photos = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        pager = findViewById(R.id.photo_pager)
        counter = findViewById(R.id.photo_counter)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>("uris")
        val startIndex = intent.getIntExtra("index", 0)

        if (uris.isNullOrEmpty()) {
            finish()
            return
        }
        photos.addAll(uris)

        pager.adapter = FullPhotoAdapter()
        pager.setCurrentItem(startIndex, false)
        updateCounter(startIndex)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })
    }

    private fun updateCounter(position: Int) {
        counter.text = "${position + 1} / ${photos.size}"
    }

    private inner class FullPhotoAdapter : RecyclerView.Adapter<FullPhotoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_full)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_full, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = photos[position]
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
                    val raw = BitmapFactory.decodeStream(stream) ?: return
                    val bmp = if (rotation != 0f) {
                        val matrix = Matrix().apply { postRotate(rotation) }
                        android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    } else raw
                    holder.image.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}
        }

        override fun getItemCount() = photos.size
    }
}
