package com.drdisagree.iconify.xposed.modules.lockscreen.clock

import android.view.View
import android.view.ViewGroup
import android.widget.TextClock

/**
 * Stretches a lock screen clock but keeps the date at its normal shape.
 *
 * The whole clock view is scaled from its top center. Every date TextClock inside it is then
 * scaled the opposite way, so on screen the date keeps its normal size and shape while it still
 * moves along with the rest of the clock. A TextClock counts as the time when its format shows
 * hours, minutes or seconds, and as the date otherwise.
 */
object ClockStretchHelper {

    /** Stretch values are in percent, 100 means normal. */
    fun apply(clockView: View, stretchX: Int, stretchY: Int) {
        val sx = stretchX / 100f
        val sy = stretchY / 100f

        clockView.pivotY = 0f
        clockView.pivotX = if (clockView.width > 0) {
            clockView.width / 2f
        } else {
            clockView.resources.displayMetrics.widthPixels / 2f
        }

        if (clockView.width == 0) {
            clockView.post {
                if (clockView.width > 0) clockView.pivotX = clockView.width / 2f
            }
        }

        clockView.scaleX = sx
        clockView.scaleY = sy

        keepDatesUnstretched(clockView, sx, sy)
    }

    /** Cancels the stretch on every date TextClock under [root]. Scales are 1f for normal. */
    fun keepDatesUnstretched(root: View, stretchX: Float, stretchY: Float) {
        if (stretchX <= 0f || stretchY <= 0f) return

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                counterScale(root.getChildAt(i), stretchX, stretchY)
            }
        }
    }

    private fun counterScale(view: View, stretchX: Float, stretchY: Float) {
        if (view is TextClock) {
            val isTime = isTimeFormat(view.format12Hour) || isTimeFormat(view.format24Hour)

            if (!isTime) {
                view.scaleX = 1f / stretchX
                view.scaleY = 1f / stretchY
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                counterScale(view.getChildAt(i), stretchX, stretchY)
            }
        }
    }

    /** True when the format shows hours, minutes or seconds. Quoted text is ignored. */
    private fun isTimeFormat(format: CharSequence?): Boolean {
        if (format == null) return true

        var quoted = false
        for (c in format) {
            if (c == '\'') {
                quoted = !quoted
            } else if (!quoted && c in "hHkKms") {
                return true
            }
        }
        return false
    }
}
