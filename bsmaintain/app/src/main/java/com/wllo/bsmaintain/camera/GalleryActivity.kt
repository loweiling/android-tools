package com.wllo.bsmaintain.camera

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wllo.bsmaintain.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private sealed class Item {
        data class Header(val title: String) : Item()
        data class Photo(val uri: Uri, val dateTakenMs: Long) : Item()
    }

    private val items = mutableListOf<Item>()
    private val photoFlatPositions = mutableMapOf<Uri, Int>()
    private val groupOfPhoto = mutableMapOf<Uri, String>()
    private val groupPhotos = linkedMapOf<String, MutableList<Uri>>()
    private lateinit var grid: RecyclerView
    private lateinit var currentGroupLabel: TextView
    private lateinit var stationSearch: AutoCompleteTextView
    private lateinit var btnClearSearch: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectionCountText: TextView
    private lateinit var backfillOverlay: View
    private lateinit var backfillLabel: TextView
    private lateinit var backfillProgress: ProgressBar

    // Long-press on a photo enters selection mode; tap toggles while in mode.
    private val selectedUris = linkedSetOf<Uri>()
    private val inSelectionMode: Boolean get() = selectedUris.isNotEmpty()

    // XMP write flow state — retained across the MediaStore.createWriteRequest roundtrip
    // so the launcher callback can finish what the assign dialog started.
    private var pendingAssignSiteName: String? = null
    private var pendingAssignUris: List<Uri> = emptyList()

    // SAF CreateDocument — user picks OneDrive / Downloads / anywhere; JSON gets written
    // there for the desktop sync_phone_to_stations.py to consume.
    private val exportSitemapLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = PhotoSiteNameStore.exportToJson(this)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            val count = PhotoSiteNameStore.getAll(this).size
            Toast.makeText(this, "已匯出 $count 筆對照表", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            Toast.makeText(this, "匯出失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Delete flow — names captured BEFORE launching because URIs are dead post-delete.
    private var pendingDeleteNames: List<String> = emptyList()
    private var pendingDeleteCount: Int = 0

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val names = pendingDeleteNames
        val count = pendingDeleteCount
        pendingDeleteNames = emptyList()
        pendingDeleteCount = 0
        if (result.resultCode == Activity.RESULT_OK) {
            for (n in names) PhotoSiteNameStore.remove(this, n)
            Toast.makeText(this, "已刪除 $count 張", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            val targetUri: Uri? = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET_URI)
            loadPhotos(targetUri)
            grid.adapter?.notifyDataSetChanged()
            setupStationSearch()
        } else {
            Toast.makeText(this, "刪除已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private val writeConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val siteName = pendingAssignSiteName
        val uris = pendingAssignUris
        pendingAssignSiteName = null
        pendingAssignUris = emptyList()
        if (siteName == null || uris.isEmpty()) return@registerForActivityResult
        val granted = result.resultCode == Activity.RESULT_OK
        if (granted) {
            val ok = uris.count { PhotoMetadataWriter.writeXmpTitle(this, it, siteName) }
            Toast.makeText(this,
                "指派 ${uris.size} 張，XMP 寫入 $ok 張",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this,
                "XMP 寫入取消（app 內對照表已更新 ${uris.size} 張）",
                Toast.LENGTH_LONG).show()
        }
        exitSelectionMode()
        val targetUri: Uri? = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET_URI)
        loadPhotos(targetUri)
        grid.adapter?.notifyDataSetChanged()
        setupStationSearch()
    }

    private val decodeExecutor = Executors.newFixedThreadPool(4)
    private val dateFormat = SimpleDateFormat("yy/MM/dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        currentGroupLabel = findViewById(R.id.current_group_label)
        stationSearch = findViewById(R.id.station_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        selectionToolbar = findViewById(R.id.selection_toolbar)
        selectionCountText = findViewById(R.id.selection_count)
        backfillOverlay = findViewById(R.id.backfill_overlay)
        backfillLabel = findViewById(R.id.backfill_label)
        backfillProgress = findViewById(R.id.backfill_progress)
        findViewById<ImageButton>(R.id.btn_selection_cancel).setOnClickListener { exitSelectionMode() }
        findViewById<ImageButton>(R.id.btn_bulk_assign).setOnClickListener { showBulkAssignDialog() }
        findViewById<ImageButton>(R.id.btn_bulk_delete).setOnClickListener { runDelete(selectedUris.toList()) }
        findViewById<ImageButton>(R.id.btn_export_sitemap).setOnClickListener {
            val count = PhotoSiteNameStore.getAll(this).size
            if (count == 0) {
                Toast.makeText(this, "對照表是空的，先讓 backfill 跑完", Toast.LENGTH_SHORT).show()
            } else {
                exportSitemapLauncher.launch("bsmaintain_sitenames.json")
            }
        }

        @Suppress("DEPRECATION")
        val targetUri: Uri? = intent.getParcelableExtra(EXTRA_TARGET_URI)

        loadPhotos(targetUri)

        grid = findViewById(R.id.photo_grid)
        val spanCount = 3
        val lm = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (items.getOrNull(position) is Item.Header) spanCount else 1
            }
        }
        grid.layoutManager = lm
        grid.adapter = GalleryAdapter()

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateGroupLabel(lm.findFirstVisibleItemPosition())
            }
        })

        setupStationSearch()

        if (targetUri != null) {
            val pos = photoFlatPositions[targetUri]
            if (pos != null) grid.post {
                lm.scrollToPositionWithOffset(pos, 0)
                updateGroupLabel(pos)
            }
        } else {
            grid.post { updateGroupLabel(0) }
        }

        // Backfill runs on background thread — 1000+ photos × ~10-30ms EXIF read would ANR
        // on main. When it finishes, reload gallery + refresh autocomplete so newly-tagged
        // photos regroup without requiring the user to reopen.
        if (!PhotoSiteNameStore.isBackfillDone(this)) {
            backfillOverlay.visibility = View.VISIBLE
            backfillLabel.text = "回填中…"
            backfillProgress.progress = 0
            Thread({
                val stats = PhotoSiteNameStore.backfillFromGps(this) { scanned, total ->
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        backfillLabel.text = "回填中 $scanned/$total"
                        backfillProgress.progress =
                            if (total > 0) ((scanned * 100) / total) else 0
                    }
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    backfillOverlay.visibility = View.GONE
                    if (stats != null) {
                        PhotoSiteNameStore.markBackfillDone(this)
                        Toast.makeText(this, stats.toHumanString(), Toast.LENGTH_LONG).show()
                        loadPhotos(targetUri)
                        grid.adapter?.notifyDataSetChanged()
                        setupStationSearch()
                    } else {
                        Toast.makeText(this, "站台清單還沒下載，先開一次站台相機", Toast.LENGTH_LONG).show()
                    }
                }
            }, "photo-backfill").start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload on every return — PhotoViewer's "指派站台" writes to PhotoSiteNameStore
        // and we want the photo to regroup without forcing the user to reopen the gallery.
        // Skip the very first resume (onCreate already loaded).
        if (::grid.isInitialized) {
            val targetUri: Uri? = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET_URI)
            loadPhotos(targetUri)
            grid.adapter?.notifyDataSetChanged()
            setupStationSearch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        decodeExecutor.shutdownNow()
    }

    /** Find the nearest preceding Header for [flatPosition] and show its title. */
    private fun updateGroupLabel(flatPosition: Int) {
        if (flatPosition == RecyclerView.NO_POSITION || flatPosition < 0) return
        var i = flatPosition.coerceAtMost(items.size - 1)
        while (i >= 0 && items[i] !is Item.Header) i--
        if (i < 0) return
        val title = (items[i] as Item.Header).title
        currentGroupLabel.text = title
        currentGroupLabel.visibility = View.VISIBLE
    }

    private fun setupStationSearch() {
        // Autocomplete source = both station names from Drive cache AND any group currently
        // visible (so 其他 / unknown-but-active groups can be searched too).
        val fromCache = StationRepository.load(this).map { it.siteName }
        val fromGroups = items.mapNotNull { (it as? Item.Header)?.title }
        val names = (fromCache + fromGroups).distinct().sorted()
        stationSearch.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        )
        stationSearch.setOnItemClickListener { _, _, pos, _ ->
            val picked = (stationSearch.adapter.getItem(pos) as? String) ?: return@setOnItemClickListener
            scrollToGroup(picked)
            hideKeyboard()
            stationSearch.clearFocus()
        }
        stationSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        btnClearSearch.setOnClickListener {
            stationSearch.setText("")
            stationSearch.clearFocus()
            hideKeyboard()
        }
    }

    private fun scrollToGroup(title: String) {
        val pos = items.indexOfFirst { it is Item.Header && it.title == title }
        if (pos < 0) {
            Toast.makeText(this, "$title 沒有照片", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = grid.layoutManager as GridLayoutManager
        lm.scrollToPositionWithOffset(pos, 0)
        updateGroupLabel(pos)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(stationSearch.windowToken, 0)
    }

    private fun toggleSelection(uri: Uri) {
        if (!selectedUris.add(uri)) selectedUris.remove(uri)
        refreshSelectionUI()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectedUris.clear()
        refreshSelectionUI()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun refreshSelectionUI() {
        selectionToolbar.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
        selectionCountText.text = "已選 ${selectedUris.size} 張"
    }

    override fun onBackPressed() {
        if (inSelectionMode) exitSelectionMode() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun showBulkAssignDialog() {
        if (selectedUris.isEmpty()) return
        val stationNames = StationRepository.load(this)
            .map { it.siteName }.distinct().sorted()
        if (stationNames.isEmpty()) {
            Toast.makeText(this, "站台清單還沒下載，先開一次站台相機", Toast.LENGTH_LONG).show()
            return
        }

        val input = AutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@GalleryActivity,
                android.R.layout.simple_dropdown_item_1line, stationNames))
            threshold = 1
            hint = "搜尋站台名…"
        }
        AlertDialog.Builder(this)
            .setTitle("批次指派 ${selectedUris.size} 張到站台")
            .setView(input)
            .setPositiveButton("指派") { _, _ ->
                val picked = input.text?.toString()?.trim().orEmpty()
                when {
                    picked.isBlank() -> Toast.makeText(this, "沒選站台", Toast.LENGTH_SHORT).show()
                    picked !in stationNames -> Toast.makeText(this, "「$picked」不在站台清單內", Toast.LENGTH_SHORT).show()
                    else -> runAssign(selectedUris.toList(), picked)
                }
            }
            .setNeutralButton("取消指派（移到其他）") { _, _ ->
                var ok = 0
                for (uri in selectedUris) {
                    val name = queryDisplayName(uri) ?: continue
                    PhotoSiteNameStore.remove(this, name); ok++
                }
                Toast.makeText(this, "已把 $ok 張移到其他", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                val targetUri: Uri? = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET_URI)
                loadPhotos(targetUri)
                grid.adapter?.notifyDataSetChanged()
                setupStationSearch()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    /** Two-step assign: save PhotoSiteNameStore immediately (guaranteed even if user
     *  denies XMP write), then request MediaStore write consent. Actual XMP writes
     *  happen in writeConsentLauncher's callback. */
    private fun runAssign(uris: List<Uri>, siteName: String) {
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            PhotoSiteNameStore.save(this, name, siteName)
        }
        pendingAssignSiteName = siteName
        pendingAssignUris = uris
        try {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            writeConsentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (e: Exception) {
            Log.e(TAG, "createWriteRequest failed", e)
            Toast.makeText(this, "XMP 寫入請求失敗：${e.message}", Toast.LENGTH_LONG).show()
            pendingAssignSiteName = null
            pendingAssignUris = emptyList()
            exitSelectionMode()
            val targetUri: Uri? = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_TARGET_URI)
            loadPhotos(targetUri)
            grid.adapter?.notifyDataSetChanged()
            setupStationSearch()
        }
    }

    /** System createDeleteRequest dialog shows one consent for all URIs. On OK we also
     *  wipe the PhotoSiteNameStore entries (DISPLAY_NAME → siteName) so they don't become
     *  zombie map entries pointing at deleted files. Display names MUST be captured before
     *  launching — the URIs are invalid after deletion. */
    private fun runDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val names = uris.mapNotNull { queryDisplayName(it) }
        pendingDeleteNames = names
        pendingDeleteCount = uris.size
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            Log.e(TAG, "createDeleteRequest failed", e)
            Toast.makeText(this, "刪除請求失敗：${e.message}", Toast.LENGTH_LONG).show()
            pendingDeleteNames = emptyList()
            pendingDeleteCount = 0
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "queryDisplayName failed for $uri", e); null
    }

    private fun loadPhotos(targetUri: Uri?) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf("DCIM/Camera/")
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            selectionArgs = arrayOf("Camera")
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        val siteNameMap = PhotoSiteNameStore.getAll(this)

        val photoDates = mutableMapOf<Uri, Long>()
        groupPhotos.clear()
        groupOfPhoto.clear()
        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                val dateTaken = if (!cursor.isNull(dateTakenCol)) cursor.getLong(dateTakenCol)
                    else cursor.getLong(dateAddedCol) * 1000
                photoDates[uri] = dateTaken
                val group = siteNameMap[name]?.takeIf { it.isNotBlank() } ?: OTHER_GROUP
                groupPhotos.getOrPut(group) { mutableListOf() }.add(uri)
                groupOfPhoto[uri] = group
            }
        }

        val targetGroup = targetUri?.let { groupOfPhoto[it] }
        val orderedKeys = buildList<String> {
            if (targetGroup != null && groupPhotos.containsKey(targetGroup)) add(targetGroup)
            for (k in groupPhotos.keys) {
                if (k != targetGroup && k != OTHER_GROUP) add(k)
            }
            if (groupPhotos.containsKey(OTHER_GROUP) && OTHER_GROUP != targetGroup) add(OTHER_GROUP)
        }

        items.clear()
        photoFlatPositions.clear()
        for (title in orderedKeys) {
            val uris = groupPhotos[title] ?: continue
            items.add(Item.Header(title))
            for (u in uris) {
                photoFlatPositions[u] = items.size
                items.add(Item.Photo(u, photoDates[u] ?: 0L))
            }
        }
    }

    private inner class GalleryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.header_title)
        }

        inner class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_item)
            val date: TextView = view.findViewById(R.id.photo_date)
            val selectionIndicator: ImageView = view.findViewById(R.id.photo_selection_indicator)
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is Item.Header) TYPE_HEADER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_photo_header, parent, false))
            } else {
                val view = inflater.inflate(R.layout.item_photo, parent, false)
                val size = parent.measuredWidth / 3
                view.layoutParams = ViewGroup.LayoutParams(size, size)
                PhotoVH(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderVH).title.text = item.title
                is Item.Photo -> {
                    val ph = holder as PhotoVH
                    ph.image.setImageDrawable(null)
                    ph.image.tag = item.uri
                    val uri = item.uri
                    decodeExecutor.execute {
                        val bmp = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(uri, Size(300, 300), null)
                            } else null
                        } catch (e: Exception) {
                            Log.w(TAG, "loadThumbnail failed for $uri", e)
                            null
                        }
                        ph.image.post {
                            if (ph.image.tag == uri && bmp != null) ph.image.setImageBitmap(bmp)
                        }
                    }

                    ph.date.text = if (item.dateTakenMs > 0)
                        dateFormat.format(Date(item.dateTakenMs)) else ""
                    ph.date.visibility = if (ph.date.text.isEmpty()) View.GONE else View.VISIBLE

                    val isSelected = item.uri in selectedUris
                    if (inSelectionMode) {
                        ph.selectionIndicator.visibility = View.VISIBLE
                        ph.selectionIndicator.setImageResource(
                            if (isSelected) R.drawable.selection_circle_checked
                            else R.drawable.selection_circle_empty
                        )
                    } else {
                        ph.selectionIndicator.visibility = View.GONE
                    }

                    ph.image.setOnClickListener {
                        if (inSelectionMode) {
                            toggleSelection(item.uri)
                        } else {
                            val group = groupOfPhoto[item.uri] ?: OTHER_GROUP
                            val list = groupPhotos[group] ?: return@setOnClickListener
                            val index = list.indexOf(item.uri).coerceAtLeast(0)
                            val intent = Intent(this@GalleryActivity, PhotoViewerActivity::class.java).apply {
                                putExtra(PhotoViewerActivity.EXTRA_INDEX, index)
                                putExtra(PhotoViewerActivity.EXTRA_GROUP_TITLE, group)
                                putParcelableArrayListExtra(PhotoViewerActivity.EXTRA_URIS, ArrayList(list))
                            }
                            startActivity(intent)
                        }
                    }
                    ph.image.setOnLongClickListener {
                        toggleSelection(item.uri)
                        true
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val EXTRA_TARGET_URI = "target_uri"
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
        private const val OTHER_GROUP = "其他"
        private const val TAG = "GalleryActivity"
    }
}
