package com.nicobrailo.batidoorlink

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Lays its child out as large as fits while keeping the picture's shape, centred. */
class VideoFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var aspect = 4f / 3f

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (a == aspect) return
        aspect = a
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var childW = w
        var childH = (w / aspect).toInt()
        if (childH > h) {
            childH = h
            childW = (h * aspect).toInt()
        }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            val x = (right - left - c.measuredWidth) / 2
            val y = (bottom - top - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
