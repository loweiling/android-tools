package com.wllo.bsmaintain.camera

import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wllo.bsmaintain.R

class GalleryActivity : AppCompatActivity() {

    private val photos = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        loadPhotos()

        val grid = findViewById<RecyclerView>(R.id.photo_grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = PhotoAdapter()
    }

    private fun loadPhotos() {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("STATION_%", "OTHERIMG_%", "ANNOTATED_%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                photos.add(ContentUris.withAppendedId(collection, id))
            }
        }
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            // 讓每個格子是正方形
            val size = parent.measuredWidth / 3
            view.layoutParams = ViewGroup.LayoutParams(size, size)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = photos[position]
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    holder.image.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}

            holder.image.setOnClickListener {
                val intent = Intent(this@GalleryActivity, PhotoViewerActivity::class.java).apply {
                    putExtra("index", position)
                    putParcelableArrayListExtra("uris", ArrayList(photos))
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = photos.size
    }
}
