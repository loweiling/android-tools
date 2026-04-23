package com.wllo.bsmaintain.camera

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.wllo.bsmaintain.R
import java.util.concurrent.Executors

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private lateinit var filmstrip: RecyclerView
    private lateinit var filmstripAdapter: FilmstripAdapter
    private lateinit var photoDate: TextView
    private val photos = mutableListOf<Uri>()
    private val photoDates = mutableMapOf<Uri, Long>()
    private val dateFormat = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US)
    private var currentIndex = 0

    private val decodeExecutor = Executors.newFixedThreadPool(2)

    private var pendingAssignSiteName: String? = null
    private var pendingAssignUri: Uri? = null
    private var pendingDeleteUri: Uri? = null
    private var pendingDeleteName: String? = null

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingDeleteUri
        val name = pendingDeleteName
        pendingDeleteUri = null
        pendingDeleteName = null
        if (uri == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            name?.let { PhotoSiteNameStore.remove(this, it) }
            Toast.makeText(this, "已刪除", Toast.LENGTH_SHORT).show()
            val removedIndex = photos.indexOf(uri)
            if (removedIndex < 0) { finish(); return@registerForActivityResult }
            photos.removeAt(removedIndex)
            if (photos.isEmpty()) {
                finish()
            } else {
                pager.adapter?.notifyItemRemoved(removedIndex)
                filmstripAdapter.notifyItemRemoved(removedIndex)
                currentIndex = removedIndex.coerceAtMost(photos.size - 1)
                updateCounter(currentIndex)
            }
        } else {
            Toast.makeText(this, "刪除已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDelete() {
        val uri = photos.getOrNull(currentIndex) ?: return
        pendingDeleteUri = uri
        pendingDeleteName = queryDisplayName(uri)
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Log.e(TAG, "createDeleteRequest failed", e)
            Toast.makeText(this, "刪除請求失敗：${e.message}", Toast.LENGTH_LONG).show()
            pendingDeleteUri = null
            pendingDeleteName = null
        }
    }

    private val writeConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val siteName = pendingAssignSiteName
        val uri = pendingAssignUri
        pendingAssignSiteName = null
        pendingAssignUri = null
        if (siteName == null || uri == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val ok = PhotoMetadataWriter.writeXmpTitle(this, uri, siteName)
            Toast.makeText(this,
                if (ok) "已指派 → $siteName（XMP 已寫入）" else "已指派 → $siteName（XMP 寫入失敗，只存 app 內）",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this,
                "已指派 → $siteName（XMP 寫入取消，只存 app 內）", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        pager = findViewById(R.id.photo_pager)
        counter = findViewById(R.id.photo_counter)
        filmstrip = findViewById(R.id.filmstrip)
        photoDate = findViewById(R.id.photo_date)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_edit).setOnClickListener { openEditor() }
        findViewById<ImageButton>(R.id.btn_assign_station).setOnClickListener { showAssignStationDialog() }
        findViewById<ImageButton>(R.id.btn_delete_photo).setOnClickListener { requestDelete() }

        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        if (uris.isNullOrEmpty()) {
            finish()
            return
        }

        // Query DATE_TAKEN for all URIs in one shot, then sort photos descending by date.
        // Caller's startIndex points to a specific URI — remap it to that URI's new position.
        photoDates.putAll(queryDatesTaken(uris))
        val anchorUri = uris.getOrNull(startIndex.coerceIn(0, uris.size - 1))
        val sorted = uris.sortedByDescending { photoDates[it] ?: 0L }
        photos.addAll(sorted)
        currentIndex = anchorUri?.let { photos.indexOf(it) }?.takeIf { it >= 0 } ?: 0

        pager.adapter = FullPhotoAdapter()
        pager.setCurrentItem(currentIndex, false)
        updateCounter(currentIndex)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter(position)
                filmstripAdapter.setSelected(position)
                filmstrip.smoothScrollToPosition(position)
            }
        })

        filmstripAdapter = FilmstripAdapter(currentIndex)
        filmstrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        filmstrip.adapter = filmstripAdapter
        filmstrip.post { (filmstrip.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(currentIndex, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        decodeExecutor.shutdownNow()
    }

    private fun updateCounter(position: Int) {
        counter.text = "${position + 1} / ${photos.size}"
        val ts = photos.getOrNull(position)?.let { photoDates[it] } ?: 0L
        if (ts > 0L) {
            photoDate.text = dateFormat.format(Date(ts))
            photoDate.visibility = View.VISIBLE
        } else {
            photoDate.visibility = View.GONE
        }
    }

    /** Bulk query DATE_TAKEN (fallback DATE_ADDED) for a list of MediaStore URIs.
     *  URI last segment = _ID. Single query using `_ID IN (...)` avoids per-URI roundtrips. */
    private fun queryDatesTaken(uris: List<Uri>): Map<Uri, Long> {
        if (uris.isEmpty()) return emptyMap()
        val idToUri = mutableMapOf<Long, Uri>()
        for (u in uris) try { idToUri[ContentUris.parseId(u)] = u } catch (_: Exception) {}
        if (idToUri.isEmpty()) return emptyMap()
        val placeholders = idToUri.keys.joinToString(",") { "?" }
        val args = idToUri.keys.map { it.toString() }.toTypedArray()
        val out = mutableMapOf<Uri, Long>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED
            ),
            "${MediaStore.Images.Media._ID} IN ($placeholders)",
            args,
            null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val ts = if (!c.isNull(takenCol)) c.getLong(takenCol) else c.getLong(addedCol) * 1000
                idToUri[id]?.let { out[it] = ts }
            }
        }
        return out
    }

    private fun openEditor() {
        val uri = photos.getOrNull(currentIndex) ?: return
        val intent = Intent(this, PhotoAnnotationActivity::class.java).apply { data = uri }
        startActivity(intent)
    }

    /** Manual siteName assignment — for photos that landed in 其他 because GPS backfill
     *  missed them (GPS drift >50m, missing GPS, or mojibake XMP that failed validation).
     *  Writes only to PhotoSiteNameStore (SharedPreferences) — doesn't touch EXIF/XMP
     *  bytes, so a desktop re-process or python-tools re-run won't clash. */
    private fun showAssignStationDialog() {
        val uri = photos.getOrNull(currentIndex) ?: return
        val displayName = queryDisplayName(uri) ?: run {
            Toast.makeText(this, "讀不到檔名", Toast.LENGTH_SHORT).show()
            return
        }
        val stationNames = StationRepository.load(this)
            .map { it.siteName }
            .distinct()
            .sorted()
        if (stationNames.isEmpty()) {
            Toast.makeText(this, "站台清單還沒下載，先開一次站台相機", Toast.LENGTH_LONG).show()
            return
        }

        val input = AutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@PhotoViewerActivity,
                android.R.layout.simple_dropdown_item_1line, stationNames))
            threshold = 1
            hint = "搜尋站台名…"
            PhotoSiteNameStore.get(this@PhotoViewerActivity, displayName)?.let { setText(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("指派站台: $displayName")
            .setView(input)
            .setPositiveButton("指派") { _, _ ->
                val picked = input.text?.toString()?.trim().orEmpty()
                when {
                    picked.isBlank() -> Toast.makeText(this, "沒選站台", Toast.LENGTH_SHORT).show()
                    picked !in stationNames -> Toast.makeText(this, "「$picked」不在站台清單內", Toast.LENGTH_SHORT).show()
                    else -> {
                        PhotoSiteNameStore.save(this, displayName, picked)
                        pendingAssignSiteName = picked
                        pendingAssignUri = uri
                        try {
                            val pi = MediaStore.createWriteRequest(contentResolver, listOf(uri))
                            writeConsentLauncher.launch(
                                IntentSenderRequest.Builder(pi.intentSender).build())
                        } catch (e: Exception) {
                            Log.e(TAG, "createWriteRequest failed", e)
                            Toast.makeText(this, "已指派 → $picked（XMP 寫入請求失敗）",
                                Toast.LENGTH_LONG).show()
                            pendingAssignSiteName = null
                            pendingAssignUri = null
                        }
                    }
                }
            }
            .setNeutralButton("取消指派") { _, _ ->
                PhotoSiteNameStore.remove(this, displayName)
                Toast.makeText(this, "已移到「其他」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "queryDisplayName failed for $uri", e); null
    }

    private fun loadBitmapForViewing(uri: Uri): Bitmap? = try {
        // ImageDecoder (API 28+) respects EXIF Orientation automatically — no manual
        // rotation needed. setTargetSampleSize(2) keeps memory sane for 4000×3000 JPEGs.
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSampleSize(2)
            decoder.isMutableRequired = false
        }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed for $uri", e)
        null
    } catch (oom: OutOfMemoryError) {
        Log.w(TAG, "decode OOM for $uri", oom)
        null
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
            holder.image.setImageDrawable(null)
            holder.image.tag = uri
            decodeExecutor.execute {
                val bmp = loadBitmapForViewing(uri)
                holder.image.post {
                    if (holder.image.tag == uri && bmp != null) holder.image.setImageBitmap(bmp)
                }
            }
        }

        override fun getItemCount() = photos.size
    }

    private inner class FilmstripAdapter(private var selected: Int) :
        RecyclerView.Adapter<FilmstripAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.filmstrip_thumb)
            val border: View = view.findViewById(R.id.filmstrip_selected_border)
        }

        fun setSelected(index: Int) {
            val prev = selected
            selected = index
            if (prev in 0 until itemCount) notifyItemChanged(prev)
            if (selected in 0 until itemCount) notifyItemChanged(selected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_filmstrip, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = photos[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = uri
            decodeExecutor.execute {
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uri, Size(200, 200), null)
                    } else null
                } catch (e: Exception) { null }
                holder.image.post {
                    if (holder.image.tag == uri && bmp != null) holder.image.setImageBitmap(bmp)
                }
            }
            holder.border.visibility = if (position == selected) View.VISIBLE else View.GONE
            holder.image.alpha = if (position == selected) 1f else 0.55f
            holder.itemView.setOnClickListener {
                pager.setCurrentItem(position, true)
            }
        }

        override fun getItemCount() = photos.size
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"
        const val EXTRA_GROUP_TITLE = "group_title"
        private const val TAG = "PhotoViewerActivity"
    }
}
