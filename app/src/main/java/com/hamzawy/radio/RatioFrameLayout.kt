package com.hamzawy.radio

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout that is always 16:9 (as wide as its parent allows). Used for the theme thumbnails. */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w * 9 / 16
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
