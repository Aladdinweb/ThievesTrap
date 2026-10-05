package com.thievestrap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * v2.9.3 preview only: a stylised, static "map" drawn with Canvas.
 * No tiles, no network, no location access. Purely decorative until the
 * real live-tracking service ships in v3.0.0.
 */
class MockMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    private val bg = Paint().apply { color = 0xFF0B1018.toInt() }
    private val grid = Paint().apply {
        color = 0xFF151E2B.toInt(); strokeWidth = 1f * d; style = Paint.Style.STROKE
    }
    private val block = Paint().apply { color = 0xFF101826.toInt(); style = Paint.Style.FILL }
    private val road = Paint().apply {
        color = 0xFF1F2C3F.toInt(); strokeWidth = 9f * d; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val roadLine = Paint().apply {
        color = 0xFF2B3B52.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val ring = Paint().apply {
        color = 0x33CC0000; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val pin = Paint().apply {
        color = 0xFFCC0000.toInt(); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val pinCore = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bg)

        // city blocks
        canvas.drawRect(w * 0.06f, h * 0.10f, w * 0.30f, h * 0.38f, block)
        canvas.drawRect(w * 0.62f, h * 0.08f, w * 0.92f, h * 0.34f, block)
        canvas.drawRect(w * 0.10f, h * 0.58f, w * 0.34f, h * 0.90f, block)
        canvas.drawRect(w * 0.66f, h * 0.62f, w * 0.90f, h * 0.88f, block)

        // grid
        val step = 36f * d
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, grid); x += step }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); y += step }

        // roads
        path.reset()
        path.moveTo(0f, h * 0.48f)
        path.cubicTo(w * 0.25f, h * 0.40f, w * 0.55f, h * 0.60f, w, h * 0.46f)
        canvas.drawPath(path, road); canvas.drawPath(path, roadLine)

        path.reset()
        path.moveTo(w * 0.46f, 0f)
        path.cubicTo(w * 0.40f, h * 0.30f, w * 0.58f, h * 0.70f, w * 0.50f, h)
        canvas.drawPath(path, road); canvas.drawPath(path, roadLine)

        path.reset()
        path.moveTo(0f, h * 0.88f)
        path.lineTo(w * 0.45f, h * 0.70f)
        canvas.drawPath(path, road); canvas.drawPath(path, roadLine)

        // marker
        val cx = w * 0.50f
        val cy = h * 0.50f
        canvas.drawCircle(cx, cy, 30f * d, ring)
        canvas.drawCircle(cx, cy, 12f * d, pin)
        canvas.drawCircle(cx, cy, 4.5f * d, pinCore)
    }
}
