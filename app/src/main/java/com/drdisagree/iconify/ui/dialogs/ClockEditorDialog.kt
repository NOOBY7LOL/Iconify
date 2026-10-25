package com.drdisagree.iconify.ui.dialogs

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
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
import com.drdisagree.iconify.data.common.Preferences.LSCLOCK_TOPMARGIN
import com.drdisagree.iconify.data.config.RPrefs
import com.drdisagree.iconify.ui.preferences.SliderPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * iPhone style editor for the lock screen clock.
 *
 * Shows a small copy of the lock screen. Drag the clock to move it and pinch to resize it.
 * On save, the values are written to the same slider preferences the settings page uses
 * (horizontal position, top margin and text scaling), so the lock screen picks them up
 * like any other change.
 */
class ClockEditorDialog(
    private val context: Context,
    @LayoutRes private val clockLayout: Int,
    private val wallpaper: Bitmap?
) {

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

        val clockView = LayoutInflater.from(context).inflate(clockLayout, null).apply {
            pivotX = screenW / 2f
            pivotY = 0f
        }
        val clockParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(clockView, clockParams)

        fun applyToClock() {
            clockParams.topMargin = (topMargin * density).roundToInt()
            clockView.layoutParams = clockParams
            clockView.translationX = offsetX * density
            clockView.scaleX = scale10 / 10f
            clockView.scaleY = scale10 / 10f
        }
        applyToClock()

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

        var lastX = 0f
        var lastY = 0f

        frame.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep following the finger that is still down
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        // Preview pixels to dp on the real screen
                        val toDp = 1f / (previewScale * density)
                        offsetX = (offsetX + (event.x - lastX) * toDp)
                            .coerceIn(-MAX_OFFSET_X, MAX_OFFSET_X)
                        topMargin = (topMargin + (event.y - lastY) * toDp)
                            .coerceIn(0f, MAX_TOP_MARGIN)
                        applyToClock()
                    }
                    lastX = event.x
                    lastY = event.y
                }
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
                    topMargin = (12 * density).roundToInt()
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
            applyToClock()
        }
    }

    private fun saveValue(key: String, value: Int) {
        SliderPreference.setValues(RPrefs.getPrefs, key, listOf(value.toFloat()))
    }

    companion object {
        private const val MIN_SCALE = 5f
        private const val MAX_SCALE = 25f
        private const val MAX_OFFSET_X = 300f
        private const val MAX_TOP_MARGIN = 600f
    }
}
