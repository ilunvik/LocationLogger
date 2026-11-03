package no.grokbot.stedslogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.AbsListView
import android.widget.ListView

/**
 * Slim drag handle for a ListView, used instead of the built-in fast scroller (1.40).
 *
 * Android's FastScroller widens its touch zone to the 48 dp minimum touch target
 * (FastScroller.isPointInsideX) and, when it has a track, accepts a touch at any height and scrolls on a
 * plain tap, so taps on row buttons near the right edge made the list jump. This view is only
 * [touchWidthDp] wide (laid over the list's right edge), draws a thin thumb, and only reacts to a touch
 * that starts on (or within 16 dp above/below) the thumb; every other touch passes through to the list.
 */
class SlimScrollHandle @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val thumbW = 5 * d
    private val thumbMinH = 44 * d
    private val edge = 3 * d
    private val grabSlop = 16 * d
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.m3_outline) }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.m3_primary) }
    private val rect = RectF()
    private var list: ListView? = null
    private var dragging = false
    private var grabOffset = 0f
    private val hide = Runnable { if (!dragging) animate().alpha(0f).setDuration(300).start() }

    fun attach(lv: ListView) {
        list = lv
        lv.isFastScrollEnabled = false
        lv.isVerticalScrollBarEnabled = false
        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) { refresh() }
        })
        alpha = 0f
    }

    private fun scrollable(): Boolean {
        val lv = list ?: return false
        val n = lv.adapter?.count ?: 0
        return n > 0 && lv.childCount > 0 && lv.childCount < n
    }

    /** Fraction 0..1 of how far the list is scrolled (by item position, smooth within the first item). */
    private fun fraction(): Float {
        val lv = list ?: return 0f
        val n = lv.adapter?.count ?: return 0f
        val visible = lv.childCount
        val maxFirst = (n - visible).coerceAtLeast(1)
        val top = lv.getChildAt(0)
        val partial = if (top != null && top.height > 0) -top.top.toFloat() / top.height else 0f
        return ((lv.firstVisiblePosition + partial) / maxFirst).coerceIn(0f, 1f)
    }

    private fun thumbH(): Float {
        val lv = list ?: return thumbMinH
        val n = (lv.adapter?.count ?: 1).coerceAtLeast(1)
        return (height * lv.childCount.toFloat() / n).coerceIn(thumbMinH, height.toFloat())
    }

    private fun thumbTop() = fraction() * (height - thumbH())

    private fun refresh() {
        if (!scrollable()) { alpha = 0f; return }
        invalidate()
        if (alpha < 1f) { animate().cancel(); alpha = 1f }
        removeCallbacks(hide); postDelayed(hide, 1500)
    }

    override fun onDraw(c: Canvas) {
        if (!scrollable()) return
        val top = thumbTop()
        rect.set(width - edge - thumbW, top, width - edge, top + thumbH())
        c.drawRoundRect(rect, thumbW / 2, thumbW / 2, if (dragging) dragPaint else thumbPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lv = list ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scrollable() || alpha < 0.05f) return false
                val top = thumbTop()
                if (e.y < top - grabSlop || e.y > top + thumbH() + grabSlop) return false
                dragging = true
                grabOffset = (e.y - top).coerceIn(0f, thumbH())
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hide); animate().cancel(); alpha = 1f
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val n = lv.adapter?.count ?: 0
                val span = (height - thumbH()).coerceAtLeast(1f)
                val f = ((e.y - grabOffset) / span).coerceIn(0f, 1f)
                val maxFirst = (n - lv.childCount).coerceAtLeast(0)
                lv.setSelectionFromTop(Math.round(f * maxFirst), 0)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                refresh()
                return true
            }
        }
        return dragging
    }

    companion object { const val touchWidthDp = 20 }
}
