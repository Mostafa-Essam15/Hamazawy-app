package com.hamzawy.radio

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.appcompat.app.AlertDialog

/** Small helpers that draw the focus rings and cards in the current theme's accent colour. */
object Ui {

    fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun rounded(fill: Int, radius: Float, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radius
        d.setColor(fill)
        d.setStroke(strokeWidth, strokeColor)
        return d
    }

    private fun oval(fill: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        d.setStroke(strokeWidth, strokeColor)
        return d
    }

    /** Dark translucent card: accent ring when focused, accent tint when selected. */
    fun cardBackground(ctx: Context, accent: Int, radiusDp: Float = 16f): StateListDrawable {
        val r = dp(ctx, radiusDp).toFloat()
        val list = StateListDrawable()
        list.addState(
            intArrayOf(android.R.attr.state_focused),
            rounded(0xB3000000.toInt(), r, dp(ctx, 3f), accent)
        )
        list.addState(
            intArrayOf(android.R.attr.state_selected),
            rounded(withAlpha(accent, 0x40), r, dp(ctx, 2f), withAlpha(accent, 0xCC))
        )
        list.addState(intArrayOf(), rounded(0x66000000, r, dp(ctx, 1f), 0x22FFFFFF))
        return list
    }

    /** Round player-control button. */
    fun circleBackground(ctx: Context, accent: Int): StateListDrawable {
        val list = StateListDrawable()
        list.addState(
            intArrayOf(android.R.attr.state_focused),
            oval(0xB3000000.toInt(), dp(ctx, 3f), accent)
        )
        list.addState(intArrayOf(), oval(0x55000000, dp(ctx, 1f), 0x22FFFFFF))
        return list
    }

    /** Plain translucent panel (the now-playing bar). */
    fun panel(ctx: Context, radiusDp: Float = 20f): GradientDrawable =
        rounded(0x99000000.toInt(), dp(ctx, radiusDp).toFloat(), dp(ctx, 1f), 0x22FFFFFF)

    fun circle(color: Int): GradientDrawable = oval(color, 0, 0)

    /** A stable, pleasant colour per station for the round badge. */
    fun monogramColor(id: String): Int {
        val hue = ((id.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.70f))
    }

    /** Shows a dialog right-to-left, so the Arabic text lines up even when the TV's language is English. */
    fun showRtl(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    /** Gently enlarges a view while it has focus, like the TV launcher tiles. */
    fun focusScale(view: View, scale: Float = 1.05f) {
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}
