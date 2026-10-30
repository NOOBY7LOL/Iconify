package com.drdisagree.iconify.ui.dialogs

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.drdisagree.iconify.R
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_FONT_TEXT_SCALING
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_OFFSETX
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_STRETCH_X
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_STRETCH_Y
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_TOPMARGIN
import com.drdisagree.iconify.data.config.RPrefs
import com.drdisagree.iconify.ui.preferences.SliderPreference
import com.drdisagree.iconify.xposed.modules.lockscreen.clock.ClockStretchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iPhone / One UI style editor for the lock screen clock.
 *
 * Shows a small copy of the lock screen with a box around the clock.
 * - Drag inside the box to move the clock.
 * - Drag a handle on an edge of the box to stretch the clock (width or height on its own).
 *   The opposite edge stays where it is.
 * - Pinch to resize the clock evenly.
 * The date is not stretched, only the time.
 *
 * On save, the values are written to the same slider preferences the lock screen reads
 * (horizontal position, top margin, text scaling and horizontal / vertical stretch),
 * so the lock screen picks them up like any other change.
 */
class ClockEditorDialog(
    private val context: Context,
    @LayoutRes private val clockLayout: Int,
    private val wallpaper: Bitmap?
) {

    private enum class Drag { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM }

    /** Draws the box around the clock and a handle in the middle of each edge. */
    private class BoxOverlay(context: Context, private val density: Float) : View(context) {

        val box = RectF()

        private val handleRect = RectF()

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2f * density
        }

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            if (box.isEmpty) return

            canvas.drawRect(box, borderPaint)

            val thick = 7f * density
            val lenX = min(40f * density, box.width() * 0.5f)
            val lenY = min(40f * density, box.height() * 0.5f)

            drawHandle(canvas, box.left, box.centerY(), thick, lenY)
            drawHandle(canvas, box.right, box.centerY(), thick, lenY)
            drawHandle(canvas, box.centerX(), box.top, lenX, thick)
            drawHandle(canvas, box.centerX(), box.bottom, lenX, thick)
        }

        private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
            handleRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            val radius = min(w, h) / 2f
            canvas.drawRoundRect(handleRect, radius, radius, handlePaint)
        }
    }

    fun show() {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // The clock is laid out at the real screen size and scaled down,
        // so margins in the preview mean the same thing as on the lock screen
        val previewScale = 0.58f
        val previewW = (screenW * previewScale).roundToInt()
        val previewH = (screenH * previewScale).roundToInt()

        var offsetX = RPrefs.getSliderInt(LSCLOCK_OFFSETX, 0).toFloat()
        var topMargin = RPrefs.getSliderInt(LSCLOCK_TOPMARGIN, 100).toFloat()
        var scale10 = RPrefs.getSliderInt(LSCLOCK_FONT_TEXT_SCALING, 10).toFloat()

        // Stretch is in percent, 100 means not stretched
        var stretchX = RPrefs.getSliderInt(LSCLOCK_STRETCH_X, 100).toFloat()
        var stretchY = RPrefs.getSliderInt(LSCLOCK_STRETCH_Y, 100).toFloat()

        val content = FrameLayout(context).apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = previewScale
            scaleY = previewScale
        }

        val background = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (wallpaper != null) {
                setImageBitmap(wallpaper)
            } else {
                setBackgroundColor(Color.parseColor("#1B1D2B"))
            }
        }
        content.addView(
            background,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dim = View(context).apply { setBackgroundColor(0x33000000) }
        content.addView(
            dim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Scales from the top center, so the top edge stays put when stretching downwards
        val clockView = LayoutInflater.from(context).inflate(clockLayout, null).apply {
            pivotX = screenW / 2f
            pivotY = 0f
        }

        // Wrap the clock so the box fits the clock and not the whole screen width
        val clockParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(clockView, clockParams)

        val overlay = BoxOverlay(context, density)
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun clockScaleX() = scale10 / 10f * stretchX / 100f
        fun clockScaleY() = scale10 / 10f * stretchY / 100f

        // Place the box on the clock as it looks right now (all in real screen pixels)
        fun updateOverlay() {
            if (clockView.width == 0 || clockView.height == 0) return

            val w = clockView.width * clockScaleX()
            val h = clockView.height * clockScaleY()
            val centerX = screenW / 2f + offsetX * density
            val top = topMargin * density

            overlay.box.set(centerX - w / 2f, top, centerX + w / 2f, top + h)
            overlay.invalidate()
        }

        fun applyToClock() {
            clockParams.topMargin = (topMargin * density).roundToInt()
            clockView.layoutParams = clockParams
            clockView.translationX = offsetX * density
            clockView.scaleX = clockScaleX()
            clockView.scaleY = clockScaleY()
            // The date keeps its normal shape, like on the lock screen
            ClockStretchHelper.keepDatesUnstretched(clockView, stretchX / 100f, stretchY / 100f)
            updateOverlay()
        }
        applyToClock()

        clockView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width / 2f
            updateOverlay()
        }

        val frame = FrameLayout(context).apply {
            this.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 28f * density
            }
            clipToOutline = true
            addView(content, FrameLayout.LayoutParams(screenW, screenH))
        }

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scale10 = (scale10 * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    applyToClock()
                    return true
                }
            }
        )

        // Which part of the box the current touch grabbed (touch is in real screen pixels)
        val edgeSlop = 48f * density

        fun hitTest(x: Float, y: Float): Drag {
            val b = overlay.box
            if (b.isEmpty) return Drag.MOVE

            // Keep the grab zones from swallowing the whole box when the clock is small
            val slopX = min(edgeSlop, b.width() / 3f)
            val slopY = min(edgeSlop, b.height() / 3f)

            val nearVertically = y >= b.top - edgeSlop && y <= b.bottom + edgeSlop
            val nearHorizontally = x >= b.left - edgeSlop && x <= b.right + edgeSlop

            var best = Drag.MOVE
            var bestDistance = Float.MAX_VALUE

            fun consider(drag: Drag, distance: Float, limit: Float) {
                if (distance <= limit && distance < bestDistance) {
                    best = drag
                    bestDistance = distance
                }
            }

            if (nearVertically) {
                consider(Drag.LEFT, abs(x - b.left), slopX)
                consider(Drag.RIGHT, abs(x - b.right), slopX)
            }
            if (nearHorizontally) {
                consider(Drag.TOP, abs(y - b.top), slopY)
                consider(Drag.BOTTOM, abs(y - b.bottom), slopY)
            }

            return best
        }

        // side is +1 for the right edge and -1 for the left edge. The other edge stays fixed.
        fun stretchWidth(deltaPx: Float, side: Int) {
            val w = clockView.width * clockScaleX()
            if (w <= 0f) return

            val newW = (w + side * deltaPx).coerceAtLeast(1f)
            val newStretch = (stretchX * newW / w).coerceIn(MIN_STRETCH, MAX_STRETCH)
            val grown = w * (newStretch / stretchX) - w

            stretchX = newStretch
            // Scaling is from the center, so move the center to keep the other edge fixed
            offsetX = (offsetX + side * grown / 2f / density)
                .coerceIn(-MAX_OFFSET_X, MAX_OFFSET_X)
        }

        // side is +1 for the bottom edge and -1 for the top edge. The other edge stays fixed.
        fun stretchHeight(deltaPx: Float, side: Int) {
            val h = clockView.height * clockScaleY()
            if (h <= 0f) return

            val newH = (h + side * deltaPx).coerceAtLeast(1f)
            val newStretch = (stretchY * newH / h).coerceIn(MIN_STRETCH, MAX_STRETCH)
            val grown = h * (newStretch / stretchY) - h

            stretchY = newStretch
            // Scaling is from the top, so only the top edge needs the margin to follow
            if (side < 0) {
                topMargin = (topMargin - grown / density).coerceIn(0f, MAX_TOP_MARGIN)
            }
        }

        var drag = Drag.NONE
        var lastX = 0f
        var lastY = 0f

        frame.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    drag = hitTest(event.x / previewScale, event.y / previewScale)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep following the finger that is still down
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    drag = Drag.MOVE
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        // Preview pixels to real screen pixels
                        val dxPx = (event.x - lastX) / previewScale
                        val dyPx = (event.y - lastY) / previewScale

                        when (drag) {
                            Drag.MOVE -> {
                                offsetX = (offsetX + dxPx / density)
                                    .coerceIn(-MAX_OFFSET_X, MAX_OFFSET_X)
                                topMargin = (topMargin + dyPx / density)
                                    .coerceIn(0f, MAX_TOP_MARGIN)
                            }

                            Drag.LEFT -> stretchWidth(dxPx, -1)
                            Drag.RIGHT -> stretchWidth(dxPx, 1)
                            Drag.TOP -> stretchHeight(dyPx, -1)
                            Drag.BOTTOM -> stretchHeight(dyPx, 1)
                            Drag.NONE -> Unit
                        }

                        applyToClock()
                    }
                    lastX = event.x
                    lastY = event.y
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
            }
            true
        }

        val hint = TextView(context).apply {
            text = context.getString(R.string.lockscreen_clock_editor_hint)
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (16 * density).roundToInt()
            setPadding(pad, pad, pad, 0)
            addView(
                hint,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                frame,
                LinearLayout.LayoutParams(previewW, previewH).apply {
                    // "this." because a local variable named topMargin shadows the field
                    this.topMargin = (12 * density).roundToInt()
                }
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lockscreen_clock_editor_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                saveValue(LSCLOCK_OFFSETX, offsetX.roundToInt())
                saveValue(LSCLOCK_TOPMARGIN, topMargin.roundToInt())
                saveValue(LSCLOCK_FONT_TEXT_SCALING, scale10.roundToInt())
                saveValue(LSCLOCK_STRETCH_X, stretchX.roundToInt())
                saveValue(LSCLOCK_STRETCH_Y, stretchY.roundToInt())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.btn_reset, null)
            .create()

        dialog.show()

        // Reset must not close the dialog
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            offsetX = 0f
            topMargin = 100f
            scale10 = 10f
            stretchX = 100f
            stretchY = 100f
            applyToClock()
        }
    }

    private fun saveValue(key: String, value: Int) {
        SliderPreference.setValues(RPrefs.getPrefs, key, listOf(value.toFloat()))
    }

    companion object {
        private const val MIN_SCALE = 5f
        private const val MAX_SCALE = 25f
        private const val MIN_STRETCH = 30f
        private const val MAX_STRETCH = 300f
        private const val MAX_OFFSET_X = 300f
        private const val MAX_TOP_MARGIN = 600f
    }
}
