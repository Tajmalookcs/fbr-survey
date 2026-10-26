package com.tajmalook.fbrsurvey

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Simple crop screen: drag the corners (or the whole box), rotate 90°, reset.
 * Returns a spec "rot;left,top,right,bottom" (fractions of the rotated photo); "" = no crop.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_SPEC = "spec"
    }

    private lateinit var view: CropView
    private var original: Bitmap? = null
    private var rot = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Crop photo"
        val d = resources.displayMetrics.density
        view = CropView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        fun btn(text: String, outlined: Boolean, onClick: () -> Unit) = MaterialButton(
            this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
            else com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt()
            }
        }
        bar.addView(btn("Rotate", true) { rotateBy90() })
        bar.addView(btn("Reset", true) { rot = 0; showRotated(); view.resetRect() })
        bar.addView(btn("Cancel", true) { setResult(RESULT_CANCELED); finish() })
        bar.addView(btn("Done", false) { done() })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        keepAboveSystemBars()

        val file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val spec = intent.getStringExtra(EXTRA_SPEC).orEmpty()
        Executors.newSingleThreadExecutor().execute {
            val b = try { Photo.loadUpright(file, 1600) } catch (e: Exception) { null }
            runOnUiThread {
                if (b == null) { Toast.makeText(this, "Could not open photo", Toast.LENGTH_LONG).show(); finish(); return@runOnUiThread }
                original = b
                // restore an existing crop
                var rect: RectF? = null
                if (spec.isNotBlank()) try {
                    val (r, f) = spec.split(";")
                    rot = r.toFloat().toInt()
                    val v = f.split(",").map { it.toFloat() }
                    rect = RectF(v[0], v[1], v[2], v[3])
                } catch (e: Exception) { rot = 0 }
                showRotated()
                if (rect != null) view.setRect(rect) else view.resetRect()
            }
        }
    }

    private fun rotateBy90() {
        rot = (rot + 90) % 360
        showRotated()
        view.resetRect()
    }

    private fun showRotated() {
        val b = original ?: return
        val shown = if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        view.setBitmap(shown)
    }

    private fun done() {
        val r = view.rect
        val full = r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f
        val spec = if (rot == 0 && full) "" else
            String.format(Locale.ENGLISH, "%d;%.4f,%.4f,%.4f,%.4f", rot, r.left, r.top, r.right, r.bottom)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SPEC, spec))
        finish()
    }
}

/** Shows the photo with a movable crop box. [rect] is in fractions (0..1) of the shown photo. */
class CropView(ctx: Context) : View(ctx) {

    var rect = RectF(0f, 0f, 1f, 1f)
        private set
    private var bmp: Bitmap? = null
    private val dst = RectF()
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * ctx.resources.displayMetrics.density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val grid = Paint().apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1f }
    private val touchR = 36 * ctx.resources.displayMetrics.density
    private var drag = -1          // 0..3 corners, 4 = move box
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(b: Bitmap) { bmp = b; requestLayout(); invalidate() }
    fun resetRect() { rect = RectF(0f, 0f, 1f, 1f); invalidate() }
    fun setRect(r: RectF) { rect = RectF(r); invalidate() }

    private fun layoutDst() {
        val b = bmp ?: return
        val pad = 16 * resources.displayMetrics.density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = min(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        layoutDst()
        c.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val l = dst.left + rect.left * dst.width()
        val t = dst.top + rect.top * dst.height()
        val r = dst.left + rect.right * dst.width()
        val bt = dst.top + rect.bottom * dst.height()
        c.drawRect(dst.left, dst.top, dst.right, t, dim)
        c.drawRect(dst.left, bt, dst.right, dst.bottom, dim)
        c.drawRect(dst.left, t, l, bt, dim)
        c.drawRect(r, t, dst.right, bt, dim)
        for (i in 1..2) {
            c.drawLine(l + (r - l) * i / 3, t, l + (r - l) * i / 3, bt, grid)
            c.drawLine(l, t + (bt - t) * i / 3, r, t + (bt - t) * i / 3, grid)
        }
        c.drawRect(l, t, r, bt, border)
        val hr = 9 * resources.displayMetrics.density
        for ((x, y) in listOf(l to t, r to t, l to bt, r to bt)) c.drawCircle(x, y, hr, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || dst.width() <= 0) return false
        val l = dst.left + rect.left * dst.width()
        val t = dst.top + rect.top * dst.height()
        val r = dst.left + rect.right * dst.width()
        val bt = dst.top + rect.bottom * dst.height()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corners = listOf(l to t, r to t, l to bt, r to bt)
                drag = corners.indexOfFirst { (x, y) -> abs(e.x - x) < touchR && abs(e.y - y) < touchR }
                if (drag == -1 && e.x in l..r && e.y in t..bt) drag = 4
                lastX = e.x; lastY = e.y
                return drag != -1
            }
            MotionEvent.ACTION_MOVE -> {
                val fx = (e.x - dst.left) / dst.width()
                val fy = (e.y - dst.top) / dst.height()
                val minS = 0.1f
                val n = RectF(rect)
                when (drag) {
                    0 -> { n.left = fx.coerceIn(0f, n.right - minS); n.top = fy.coerceIn(0f, n.bottom - minS) }
                    1 -> { n.right = fx.coerceIn(n.left + minS, 1f); n.top = fy.coerceIn(0f, n.bottom - minS) }
                    2 -> { n.left = fx.coerceIn(0f, n.right - minS); n.bottom = fy.coerceIn(n.top + minS, 1f) }
                    3 -> { n.right = fx.coerceIn(n.left + minS, 1f); n.bottom = fy.coerceIn(n.top + minS, 1f) }
                    4 -> {
                        val dx = (e.x - lastX) / dst.width()
                        val dy = (e.y - lastY) / dst.height()
                        val mx = dx.coerceIn(-n.left, 1f - n.right)
                        val my = dy.coerceIn(-n.top, 1f - n.bottom)
                        n.offset(mx, my)
                    }
                }
                rect = n
                lastX = e.x; lastY = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; return true }
        }
        return super.onTouchEvent(e)
    }
}
