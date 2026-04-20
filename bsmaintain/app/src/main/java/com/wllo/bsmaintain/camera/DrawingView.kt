package com.wllo.bsmaintain.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private sealed class DrawElement {
        data class StrokePath(val path: Path, val paint: Paint) : DrawElement()
    }

    data class TextLabel(
        var text: String,
        var x: Float,
        var y: Float,
        var color: Int,
        var textSize: Float
    ) {
        fun createPaint(): Paint = Paint().apply {
            color = this@TextLabel.color
            style = Paint.Style.FILL
            textSize = this@TextLabel.textSize
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        fun getBounds(): RectF {
            val paint = createPaint()
            val rect = Rect()
            paint.getTextBounds(text, 0, text.length, rect)
            val padding = 12f
            return RectF(
                x - padding,
                y + rect.top - padding,
                x + rect.width() + padding,
                y + rect.bottom + padding
            )
        }
    }

    private val strokeElements = mutableListOf<DrawElement.StrokePath>()
    private val undoneStrokes = mutableListOf<DrawElement.StrokePath>()
    val textLabels = mutableListOf<TextLabel>()

    private var currentPath = Path()

    var strokeColor: Int = Color.RED
        set(value) {
            field = value
            currentPaint = createStrokePaint()
        }

    var strokeWidth: Float = 8f
        set(value) {
            field = value
            currentPaint = createStrokePaint()
        }

    var isTextMode = false

    var selectedLabel: TextLabel? = null
        private set
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f

    /** Tap on empty area in text mode */
    var onTextTapListener: ((Float, Float) -> Unit)? = null

    /** Tap on existing text label (not drag) */
    var onTextEditListener: ((TextLabel) -> Unit)? = null

    /** Tap on empty area while a label is selected — means "finish editing" */
    var onDeselectListener: (() -> Unit)? = null

    private var currentPaint = createStrokePaint()

    private val selectedBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    private fun createStrokePaint() = Paint().apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = this@DrawingView.strokeWidth
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (el in strokeElements) {
            canvas.drawPath(el.path, el.paint)
        }
        if (!isTextMode) {
            canvas.drawPath(currentPath, currentPaint)
        }
        for (label in textLabels) {
            val paint = label.createPaint()
            canvas.drawText(label.text, label.x, label.y, paint)
            if (label == selectedLabel) {
                val bounds = label.getBounds()
                canvas.drawRoundRect(bounds, 6f, 6f, selectedBorderPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTextMode) return handleTextTouch(event)

        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (selectedLabel != null) {
                    selectedLabel = null
                    invalidate()
                }
                currentPath = Path()
                currentPath.moveTo(x, y)
                undoneStrokes.clear()
            }
            MotionEvent.ACTION_MOVE -> currentPath.lineTo(x, y)
            MotionEvent.ACTION_UP -> {
                strokeElements.add(DrawElement.StrokePath(currentPath, currentPaint))
                currentPath = Path()
                currentPaint = createStrokePaint()
            }
        }
        invalidate()
        return true
    }

    private fun handleTextTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                val hitLabel = findLabelAt(x, y)
                if (hitLabel != null) {
                    selectedLabel = hitLabel
                    isDragging = true
                    dragOffsetX = x - hitLabel.x
                    dragOffsetY = y - hitLabel.y
                    invalidate()
                } else {
                    isDragging = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && selectedLabel != null) {
                    selectedLabel!!.x = x - dragOffsetX
                    selectedLabel!!.y = y - dragOffsetY
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val dist = Math.hypot((x - touchDownX).toDouble(), (y - touchDownY).toDouble())
                val wasTap = dist < 15

                if (isDragging && selectedLabel != null) {
                    if (wasTap) {
                        // Tap on existing label → edit inline
                        onTextEditListener?.invoke(selectedLabel!!)
                    }
                    // else: was a drag, do nothing extra (label already moved)
                    isDragging = false
                } else if (wasTap) {
                    // Tap on empty area
                    if (selectedLabel != null) {
                        // Deselect current label
                        selectedLabel = null
                        onDeselectListener?.invoke()
                        invalidate()
                    } else {
                        // Create new label
                        onTextTapListener?.invoke(x, y)
                    }
                }
            }
        }
        return true
    }

    private fun findLabelAt(x: Float, y: Float): TextLabel? {
        for (i in textLabels.indices.reversed()) {
            if (textLabels[i].getBounds().contains(x, y)) {
                return textLabels[i]
            }
        }
        return null
    }

    fun addTextLabel(text: String, x: Float, y: Float, color: Int, textSize: Float): TextLabel {
        val label = TextLabel(text, x, y, color, textSize)
        textLabels.add(label)
        selectedLabel = label
        invalidate()
        return label
    }

    fun removeLabel(label: TextLabel) {
        textLabels.remove(label)
        if (selectedLabel == label) selectedLabel = null
        invalidate()
    }

    fun deselectAll() {
        selectedLabel = null
        invalidate()
    }

    fun selectLabel(label: TextLabel) {
        selectedLabel = label
        invalidate()
    }

    fun undo() {
        if (isTextMode) {
            if (textLabels.isNotEmpty()) {
                textLabels.removeAt(textLabels.lastIndex)
                selectedLabel = null
                invalidate()
            }
        } else {
            if (strokeElements.isNotEmpty()) {
                undoneStrokes.add(strokeElements.removeAt(strokeElements.lastIndex))
                invalidate()
            }
        }
    }

    fun clearAll() {
        strokeElements.clear()
        undoneStrokes.clear()
        textLabels.clear()
        selectedLabel = null
        currentPath = Path()
        invalidate()
    }

    fun hasElements(): Boolean = strokeElements.isNotEmpty() || textLabels.isNotEmpty()
}
