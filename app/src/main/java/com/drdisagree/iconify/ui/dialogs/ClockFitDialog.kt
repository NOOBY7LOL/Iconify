package com.drdisagree.iconify.ui.dialogs

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits the lock screen clock to the subject of the wallpaper, like One UI's Adaptive Clock.
 *
 * Paint over the person (or object) in the wallpaper, then tap Fit. The clock moves up and
 * stretches so it fills the free space above the painted area, without covering it.
 * On save, only the top margin and the vertical stretch are written, to the same slider
 * preferences the lock screen already reads.
 */
class ClockFitDialog(
    private val context: Context,
    @LayoutRes private val clockLayout: Int,
    private val wallpaper: Bitmap?,
    private val onImportImage: () -> Unit
) {

    private var backgroundView: ImageView? = null
    private var highlightView: HighlightView? = null

    /**
     * Shows a picture the user picked as the background to paint on.
     * It is only an example to paint on and is not saved or set as the wallpaper.
     */
    fun setReferenceImage(bitmap: Bitmap) {
        backgroundView?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageBitmap(bitmap)
        }
        highlightView?.clear()
    }

    /** Lets the user paint over the subject with a finger and remembers where it ends. */
    private class HighlightView(context: Context, density: Float) : View(context) {

        private val radius = 26f * density
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = radius * 2f
            color = Color.argb(120, 190, 255, 70)
        }

        private var minY = Float.MAX_VALUE

        var hasPaint = false
            private set

        fun start(x: Float, y: Float) {
            path.moveTo(x, y)
            path.lineTo(x + 0.1f, y)
            include(y)
        }

        fun move(x: Float, y: Float) {
            path.lineTo(x, y)
            include(y)
        }

        fun clear() {
            path.reset()
            minY = Float.MAX_VALUE
            hasPaint = false
            invalidate()
        }

        /** Top edge of the painted area, including the width of the brush. */
        fun subjectTop(): Float = max(0f, minY - radius)

        private fun include(y: Float) {
            hasPaint = true
            minY = min(minY, y)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
    }

    fun show() {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Same preview as the move and resize editor: real screen size, scaled down
        val previewScale = 0.58f
        val previewW = (screenW * previewScale).roundToInt()
        val previewH = (screenH * previewScale).roundToInt()

        val offsetX = RPrefs.getSliderInt(LSCLOCK_OFFSETX, 0).toFloat()
        val scale10 = RPrefs.getSliderInt(LSCLOCK_FONT_TEXT_SCALING, 10).toFloat()
        val stretchX = RPrefs.getSliderInt(LSCLOCK_STRETCH_X, 100).toFloat()
        var topMargin = RPrefs.getSliderInt(LSCLOCK_TOPMARGIN, 100).toFloat()
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
        backgroundView = background
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

        val clockView = LayoutInflater.from(context).inflate(clockLayout, null).apply {
            pivotX = screenW / 2f
            pivotY = 0f
        }
        val clockParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(clockView, clockParams)

        val highlight = HighlightView(context, density)
        highlightView = highlight
        content.addView(
            highlight,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun applyToClock() {
            clockParams.topMargin = (topMargin * density).roundToInt()
            clockView.layoutParams = clockParams
            clockView.translationX = offsetX * density
            clockView.scaleX = scale10 / 10f * stretchX / 100f
            clockView.scaleY = scale10 / 10f * stretchY / 100f
            // The date keeps its normal shape, like on the lock screen
            ClockStretchHelper.keepDatesUnstretched(clockView, stretchX / 100f, stretchY / 100f)
        }
        applyToClock()

        clockView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width / 2f
        }

        // Moves the clock up and stretches it to the free space above the painted subject
        fun fit() {
            if (!highlight.hasPaint) {
                Toast.makeText(
                    context,
                    R.string.lockscreen_clock_fit_paint_first,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            // Height of the clock before any scaling
            val naturalHeight = clockView.height.toFloat()
            if (naturalHeight <= 0f) return

            val gap = 12f * density
            val minHeight = 48f * density
            val subjectTop = highlight.subjectTop()

            val top = min(topMargin * density, subjectTop - gap - minHeight)
            if (top < 0f) {
                Toast.makeText(
                    context,
                    R.string.lockscreen_clock_fit_no_room,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val targetHeight = subjectTop - gap - top
            topMargin = top / density
            stretchY = (targetHeight / (naturalHeight * (scale10 / 10f)) * 100f)
                .coerceIn(MIN_STRETCH, MAX_STRETCH)
            applyToClock()
        }

        val frame = FrameLayout(context).apply {
            this.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 28f * density
            }
            clipToOutline = true
            addView(content, FrameLayout.LayoutParams(screenW, screenH))
        }

        frame.setOnTouchListener { v, event ->
            // Preview pixels to real screen pixels
            val x = event.x / previewScale
            val y = event.y / previewScale

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    highlight.start(x, y)
                }

                MotionEvent.ACTION_MOVE -> highlight.move(x, y)
            }
            true
        }

        val hint = TextView(context).apply {
            text = context.getString(R.string.lockscreen_clock_fit_hint)
            gravity = Gravity.CENTER
        }

        val importButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = context.getString(R.string.lockscreen_clock_fit_import)
            setOnClickListener { onImportImage() }
        }

        val clearButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = context.getString(R.string.lockscreen_clock_fit_clear)
            setOnClickListener { highlight.clear() }
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(importButton)
            addView(clearButton)
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
                buttonRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(frame, LinearLayout.LayoutParams(previewW, previewH))
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lockscreen_clock_fit_title)
            .setView(container)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                saveValue(LSCLOCK_TOPMARGIN, topMargin.roundToInt())
                saveValue(LSCLOCK_STRETCH_Y, stretchY.roundToInt())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.lockscreen_clock_fit_apply, null)
            .create()

        dialog.show()

        // Fit must not close the dialog
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { fit() }
    }

    private fun saveValue(key: String, value: Int) {
        SliderPreference.setValues(RPrefs.getPrefs, key, listOf(value.toFloat()))
    }

    companion object {
        private const val MIN_STRETCH = 30f
        private const val MAX_STRETCH = 300f
    }
}
