package com.wllo.bsmaintain.camera

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.wllo.bsmaintain.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoAnnotationActivity : AppCompatActivity() {

    private lateinit var photoView: ImageView
    private lateinit var drawingView: DrawingView
    private lateinit var inlineTextInput: EditText
    private lateinit var btnEdit: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnSave: TextView
    private lateinit var editToolbar: LinearLayout
    private lateinit var toolBrush: TextView
    private lateinit var toolText: TextView
    private lateinit var brushSizeRow: LinearLayout
    private lateinit var brushSizeSeekbar: SeekBar
    private lateinit var brushSizeLabel: TextView
    private lateinit var textSizeRow: LinearLayout
    private lateinit var textSizeSeekbar: SeekBar
    private lateinit var textSizeLabel: TextView

    private var sourceUri: Uri? = null
    private var sourceBitmap: Bitmap? = null
    private var isEditMode = false
    private var isBrushTool = true
    private var currentColor = Color.RED
    private var currentTextSize = 80f

    // Inline text editing state
    private var isTyping = false
    private var editingLabel: DrawingView.TextLabel? = null
    private var inlineX = 0f
    private var inlineY = 0f

    companion object {
        private const val TAG = "PhotoAnnotation"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_annotation)

        photoView = findViewById(R.id.photo_view)
        drawingView = findViewById(R.id.drawing_view)
        inlineTextInput = findViewById(R.id.inline_text_input)
        btnEdit = findViewById(R.id.btn_edit)
        btnUndo = findViewById(R.id.btn_undo)
        btnClear = findViewById(R.id.btn_clear)
        btnSave = findViewById(R.id.btn_save)
        editToolbar = findViewById(R.id.edit_toolbar)
        toolBrush = findViewById(R.id.tool_brush)
        toolText = findViewById(R.id.tool_text)
        brushSizeRow = findViewById(R.id.brush_size_row)
        brushSizeSeekbar = findViewById(R.id.brush_size_seekbar)
        brushSizeLabel = findViewById(R.id.brush_size_label)
        textSizeRow = findViewById(R.id.text_size_row)
        textSizeSeekbar = findViewById(R.id.text_size_seekbar)
        textSizeLabel = findViewById(R.id.text_size_label)

        sourceUri = intent.data
        if (sourceUri == null) {
            Toast.makeText(this, "無法載入照片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadPhoto()

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { onBackAction() }
        btnEdit.setOnClickListener { enterEditMode() }
        btnUndo.setOnClickListener { drawingView.undo() }
        btnClear.setOnClickListener { drawingView.clearAll() }
        btnSave.setOnClickListener { saveAnnotatedPhoto() }
        toolBrush.setOnClickListener { switchTool(true) }
        toolText.setOnClickListener { switchTool(false) }

        // 畫筆粗細
        brushSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                drawingView.strokeWidth = progress.toFloat()
                brushSizeLabel.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // 文字大小
        textSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentTextSize = progress.toFloat()
                textSizeLabel.text = progress.toString()
                // 正在打字的話，即時更新 EditText 大小
                if (isTyping) {
                    updateInlineTextStyle()
                }
                // 有選中的文字標籤，即時更新大小
                drawingView.selectedLabel?.let {
                    it.textSize = currentTextSize
                    drawingView.invalidate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        setupColorPicker()

        // 文字模式：點空白處 → 開始 inline 輸入
        drawingView.onTextTapListener = { x, y ->
            commitInlineText()
            startInlineInput(x, y, null)
        }

        // 文字模式：點已有文字 → inline 編輯
        drawingView.onTextEditListener = { label ->
            commitInlineText()
            startInlineInput(label.x, label.y, label)
        }

        // 文字模式：點空白處取消選取 → 結束輸入
        drawingView.onDeselectListener = {
            commitInlineText()
        }

        // 按鍵盤 Done 完成輸入
        inlineTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInlineText()
                true
            } else false
        }
    }

    // ---- Inline text input ----

    private fun startInlineInput(x: Float, y: Float, existing: DrawingView.TextLabel?) {
        isTyping = true
        editingLabel = existing
        inlineX = x
        inlineY = y

        // 如果是編輯已有文字，先從畫面上隱藏它（在 EditText 裡編輯）
        if (existing != null) {
            inlineTextInput.setText(existing.text)
            inlineTextInput.setSelection(existing.text.length)
            // 暫時隱藏該 label 的文字（由 EditText 顯示）
            drawingView.selectLabel(existing)
        } else {
            inlineTextInput.setText("")
        }

        updateInlineTextStyle()

        // 定位 EditText
        val lp = inlineTextInput.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = x.toInt()
        lp.topMargin = (y - currentTextSize).toInt().coerceAtLeast(0)
        inlineTextInput.layoutParams = lp
        inlineTextInput.visibility = View.VISIBLE
        inlineTextInput.requestFocus()

        // 顯示鍵盤
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inlineTextInput.post {
            imm.showSoftInput(inlineTextInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateInlineTextStyle() {
        inlineTextInput.setTextColor(currentColor)
        inlineTextInput.textSize = currentTextSize / resources.displayMetrics.density
        inlineTextInput.setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private fun commitInlineText() {
        if (!isTyping) return
        isTyping = false

        val text = inlineTextInput.text.toString().trim()

        // 隱藏鍵盤
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inlineTextInput.windowToken, 0)
        inlineTextInput.visibility = View.GONE
        inlineTextInput.setText("")

        if (text.isNotEmpty()) {
            if (editingLabel != null) {
                // 更新已有文字
                editingLabel!!.text = text
                editingLabel!!.color = currentColor
                editingLabel!!.textSize = currentTextSize
                drawingView.invalidate()
            } else {
                // 新增文字標籤
                drawingView.addTextLabel(text, inlineX, inlineY, currentColor, currentTextSize)
            }
        } else if (editingLabel != null) {
            // 清空文字 → 刪除標籤
            drawingView.removeLabel(editingLabel!!)
        }

        editingLabel = null
        drawingView.deselectAll()
    }

    // ---- Mode management ----

    private fun loadPhoto() {
        try {
            val rotation = contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                val raw = BitmapFactory.decodeStream(stream) ?: return
                sourceBitmap = if (rotation != 0f) {
                    val matrix = Matrix().apply { postRotate(rotation) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                } else raw
                photoView.setImageBitmap(sourceBitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load photo failed", e)
            Toast.makeText(this, "照片載入失敗", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun enterEditMode() {
        isEditMode = true
        btnEdit.visibility = View.GONE
        btnUndo.visibility = View.VISIBLE
        btnClear.visibility = View.VISIBLE
        btnSave.visibility = View.VISIBLE
        editToolbar.visibility = View.VISIBLE
        drawingView.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        commitInlineText()
        isEditMode = false
        btnEdit.visibility = View.VISIBLE
        btnUndo.visibility = View.GONE
        btnClear.visibility = View.GONE
        btnSave.visibility = View.GONE
        editToolbar.visibility = View.GONE
        drawingView.visibility = View.GONE
    }

    private fun onBackAction() {
        if (isTyping) {
            commitInlineText()
            return
        }
        if (isEditMode) {
            if (drawingView.hasElements()) {
                AlertDialog.Builder(this)
                    .setMessage("放棄編輯內容？")
                    .setPositiveButton("放棄") { _, _ -> exitEditMode() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                exitEditMode()
            }
        } else {
            finish()
        }
    }

    private fun switchTool(toBrush: Boolean) {
        commitInlineText()
        isBrushTool = toBrush
        drawingView.isTextMode = !toBrush
        if (toBrush) drawingView.deselectAll()

        toolBrush.setTextColor(if (toBrush) Color.WHITE else 0x80FFFFFF.toInt())
        toolBrush.setTypeface(null, if (toBrush) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        toolText.setTextColor(if (!toBrush) Color.WHITE else 0x80FFFFFF.toInt())
        toolText.setTypeface(null, if (!toBrush) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        brushSizeRow.visibility = if (toBrush) View.VISIBLE else View.GONE
        textSizeRow.visibility = if (!toBrush) View.VISIBLE else View.GONE
    }

    private fun setupColorPicker() {
        val colorMap = mapOf(
            R.id.color_red to Color.RED,
            R.id.color_yellow to Color.YELLOW,
            R.id.color_green to Color.parseColor("#4CAF50"),
            R.id.color_blue to Color.parseColor("#2196F3"),
            R.id.color_white to Color.WHITE,
            R.id.color_black to Color.BLACK
        )
        for ((id, color) in colorMap) {
            findViewById<View>(id).setOnClickListener {
                currentColor = color
                drawingView.strokeColor = color
                if (isTyping) updateInlineTextStyle()
                drawingView.selectedLabel?.let {
                    it.color = color
                    drawingView.invalidate()
                }
            }
        }
    }

    // ---- Save ----

    private fun saveAnnotatedPhoto() {
        commitInlineText()
        val original = sourceBitmap ?: return

        if (!drawingView.hasElements()) {
            Toast.makeText(this, "沒有任何標記", Toast.LENGTH_SHORT).show()
            return
        }

        drawingView.deselectAll()

        val imageViewWidth = photoView.width.toFloat()
        val imageViewHeight = photoView.height.toFloat()
        val imgRatio = original.width.toFloat() / original.height.toFloat()
        val viewRatio = imageViewWidth / imageViewHeight

        val displayedWidth: Float
        val displayedHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (imgRatio > viewRatio) {
            displayedWidth = imageViewWidth
            displayedHeight = imageViewWidth / imgRatio
            offsetX = 0f
            offsetY = (imageViewHeight - displayedHeight) / 2f
        } else {
            displayedHeight = imageViewHeight
            displayedWidth = imageViewHeight * imgRatio
            offsetX = (imageViewWidth - displayedWidth) / 2f
            offsetY = 0f
        }

        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val drawingBitmap = Bitmap.createBitmap(drawingView.width, drawingView.height, Bitmap.Config.ARGB_8888)
        val drawingCanvas = Canvas(drawingBitmap)
        drawingView.draw(drawingCanvas)

        val srcLeft = offsetX.toInt().coerceAtLeast(0)
        val srcTop = offsetY.toInt().coerceAtLeast(0)
        val srcRight = (offsetX + displayedWidth).toInt().coerceAtMost(drawingBitmap.width)
        val srcBottom = (offsetY + displayedHeight).toInt().coerceAtMost(drawingBitmap.height)

        if (srcRight > srcLeft && srcBottom > srcTop) {
            val croppedDrawing = Bitmap.createBitmap(
                drawingBitmap, srcLeft, srcTop,
                srcRight - srcLeft, srcBottom - srcTop
            )
            val scaledDrawing = Bitmap.createScaledBitmap(croppedDrawing, original.width, original.height, true)
            canvas.drawBitmap(scaledDrawing, 0f, 0f, null)
            croppedDrawing.recycle()
            scaledDrawing.recycle()
        }

        val sourceDescription = try {
            contentResolver.openInputStream(sourceUri!!)?.use { stream ->
                ExifInterface(stream).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "read source ImageDescription failed", e); null
        }

        val filename = "ANNOTATED_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (!sourceDescription.isNullOrBlank()) {
                put(MediaStore.Images.Media.DESCRIPTION, sourceDescription)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {
            val uri = contentResolver.insert(collection, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    result.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Toast.makeText(this, "已儲存標記照片", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Toast.makeText(this, "儲存失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            drawingBitmap.recycle()
            result.recycle()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isTyping || isEditMode) {
            onBackAction()
        } else {
            super.onBackPressed()
        }
    }
}
