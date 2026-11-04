package it.cime.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import it.cime.app.core.Calibration
import it.cime.app.core.Geo
import it.cime.app.core.Peak
import it.cime.app.core.Projector
import it.cime.app.core.ViewResult
import it.cime.app.core.VisiblePeak
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cima cercata dall'utente, con la geometria vista dalla posizione corrente. */
data class Target(
    val peak: Peak,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val distanceM: Double,
    /** null = non calcolato (es. oltre il raggio di calcolo). */
    val visible: Boolean?,
)

/** Disegna orizzonte ed etichette delle cime sopra la preview della fotocamera. */
class PeakOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var view: ViewResult? = null
        set(value) { field = value; invalidate() }
    var rotation9: FloatArray? = null
    var declinationDeg = 0.0
    /** Focale della preview in pixel; null finché la fotocamera non è pronta. */
    var focalPx: Double? = null
    var calibration = Calibration()
        set(value) { field = value; invalidate() }
    var showHidden = false
        set(value) { field = value; invalidate() }
    var calibrating = false
    /** Spazio occupato in alto (barra di stato + barra dell'app) e in basso (barra di navigazione), in pixel. */
    var insetTop = 56 * resources.displayMetrics.density
        set(value) { field = value; invalidate() }
    var insetBottom = 0f
        set(value) { field = value; invalidate() }
    /** Cima cercata: evidenziata se inquadrata, altrimenti indicata da una freccia al bordo. */
    var target: Target? = null
        set(value) { field = value; invalidate() }
    /** Ultimo allineamento automatico riuscito (SystemClock.elapsedRealtime). */
    var autoAlignedAt = 0L
    /** Chiamato quando l'utente modifica la calibrazione trascinando/pizzicando. */
    var onCalibrationChanged: ((Calibration) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 220, 80); strokeWidth = 2 * dp; style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.2f * dp }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * dp; isFakeBoldText = true
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 230, 230, 230); textSize = 11 * dp
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val autoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 230, 120); textSize = 13 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val targetColor = Color.rgb(255, 213, 79)
    private val targetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = targetColor; strokeWidth = 3 * dp; style = Paint.Style.STROKE
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = targetColor; setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val targetName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = targetColor; textSize = 16 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val targetInfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * dp; textAlign = Paint.Align.CENTER
        setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val tmp = FloatArray(2)
    private val path = Path()
    private val arrow = Path()

    private var projector: Projector? = null

    private class Placed(val vp: VisiblePeak, val x: Float, val y: Float, val left: Float, val right: Float, val row: Int)

    override fun onDraw(canvas: Canvas) {
        val r = rotation9 ?: return
        val f = focalPx ?: return
        val p = Projector(r, declinationDeg, width.toFloat(), height.toFloat(), f, calibration)
        projector = p
        drawHeading(canvas, p)
        val v = view ?: return
        drawSkyline(canvas, p, v)
        drawPeaks(canvas, p, v)
        drawTarget(canvas, p)
    }

    private fun drawHeading(canvas: Canvas, p: Projector) {
        val az = p.centerAzimuthDeg()
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        var label = "${dirs[((az + 22.5) / 45).toInt() % 8]} ${az.roundToInt() % 360}°"
        // Se una calibrazione manuale sta spostando la bussola, lo si vede subito.
        val off = calibration.headingOffsetDeg.roundToInt()
        if (off != 0) label += "  (corr. ${if (off > 0) "+" else ""}$off°)"
        val y = height - insetBottom - 24 * dp
        canvas.drawText(label, width / 2f, y, hudPaint)
        if (SystemClock.elapsedRealtime() - autoAlignedAt < 5_000) {
            canvas.drawText("✓ allineato all'orizzonte", width / 2f, y - 24 * dp, autoPaint)
        }
    }

    private fun drawTarget(canvas: Canvas, p: Projector) {
        val t = target ?: return
        val left = 28 * dp; val right = width - 28 * dp
        val top = insetTop + 40 * dp; val bottom = height - insetBottom - 90 * dp
        val inFront = p.project(t.azimuthDeg, t.elevationDeg, tmp)
        val km = t.distanceM / 1000
        val dist = if (km < 10) String.format(Locale.ITALY, "%.1f km", km) else "${km.roundToInt()} km"
        val info = "${t.peak.ele.roundToInt()} m · $dist" + if (t.visible == false) " · nascosta dai rilievi" else ""

        if (inFront && tmp[0] in left..right && tmp[1] in top..bottom) {
            // Inquadrata: cerchio e nome sopra.
            val x = tmp[0]; val y = tmp[1]
            canvas.drawCircle(x, y, 18 * dp, targetRing)
            canvas.drawCircle(x, y, 3 * dp, targetFill)
            val tx = x.coerceIn(left + 60 * dp, right - 60 * dp)
            canvas.drawText(t.peak.name, tx, y - 30 * dp, targetName)
            canvas.drawText(info, tx, y - 30 * dp + 16 * dp, targetInfo)
            return
        }

        // Fuori inquadratura: freccia sul bordo nella direzione in cui girarsi.
        val cx = width / 2f; val cy = (top + bottom) / 2
        val turn = Geo.angleDiff(t.azimuthDeg, p.centerAzimuthDeg())
        var dx: Float; var dy: Float
        if (inFront) { dx = tmp[0] - cx; dy = tmp[1] - cy } else { dx = if (turn >= 0) 1f else -1f; dy = 0f }
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().takeIf { it > 1e-3f } ?: 1f
        dx /= len; dy /= len
        var s = Float.MAX_VALUE
        if (dx > 1e-3f) s = minOf(s, (right - cx) / dx)
        if (dx < -1e-3f) s = minOf(s, (left - cx) / dx)
        if (dy > 1e-3f) s = minOf(s, (bottom - cy) / dy)
        if (dy < -1e-3f) s = minOf(s, (top - cy) / dy)
        val ex = cx + dx * s; val ey = cy + dy * s
        val px = -dy; val py = dx
        arrow.reset()
        arrow.moveTo(ex + dx * 16 * dp, ey + dy * 16 * dp)
        arrow.lineTo(ex - dx * 10 * dp + px * 13 * dp, ey - dy * 10 * dp + py * 13 * dp)
        arrow.lineTo(ex - dx * 10 * dp - px * 13 * dp, ey - dy * 10 * dp - py * 13 * dp)
        arrow.close()
        canvas.drawPath(arrow, targetFill)

        val hint = when {
            abs(turn) > 15 -> "gira a ${if (turn > 0) "destra" else "sinistra"} di ${abs(turn).roundToInt()}°"
            dy < -0.5f -> "alza il telefono"
            dy > 0.5f -> "abbassa il telefono"
            else -> "gira a ${if (turn > 0) "destra" else "sinistra"}"
        }
        val lx = (ex - dx * 70 * dp).coerceIn(left + 70 * dp, right - 70 * dp)
        val ly = (ey - dy * 50 * dp).coerceIn(top + 16 * dp, bottom)
        canvas.drawText(t.peak.name, lx, ly, targetName)
        canvas.drawText(hint, lx, ly + 17 * dp, targetInfo)
        canvas.drawText(info, lx, ly + 32 * dp, targetInfo)
    }

    private fun drawSkyline(canvas: Canvas, p: Projector, v: ViewResult) {
        path.reset()
        var penDown = false
        var lastX = 0f
        for (i in v.skyline.indices) {
            val az = i * v.skylineStepDeg
            if (!p.project(az, v.skyline[i].toDouble(), tmp)) { penDown = false; continue }
            // Interrompe la linea se salta da un bordo all'altro (punti ai lati estremi).
            if (penDown && abs(tmp[0] - lastX) < width) path.lineTo(tmp[0], tmp[1]) else path.moveTo(tmp[0], tmp[1])
            penDown = true; lastX = tmp[0]
        }
        canvas.drawPath(path, skyPaint)
    }

    private fun drawPeaks(canvas: Canvas, p: Projector, v: ViewResult) {
        val margin = 40 * dp
        // Priorità: cime visibili, poi più alte e più vicine.
        val targetId = target?.peak?.id
        val candidates = v.peaks
            .filter { (it.visible || showHidden) && it.peak.id != targetId }
            .mapNotNull { vp ->
                if (!p.project(vp.azimuthDeg, vp.elevationDeg, tmp)) return@mapNotNull null
                if (tmp[0] < -margin || tmp[0] > width + margin || tmp[1] < 0 || tmp[1] > height) return@mapNotNull null
                Triple(vp, tmp[0], tmp[1])
            }
            .sortedByDescending { (vp, _, _) -> (if (vp.visible) 1e6 else 0.0) + vp.peak.ele - vp.distanceM / 50.0 }

        val rowH = 34 * dp
        val top = insetTop + 8 * dp
        val maxRows = 7
        val gap = 8 * dp
        val placed = ArrayList<Placed>()
        for ((vp, x, y) in candidates) {
            val w = maxOf(namePaint.measureText(vp.peak.name), infoPaint.measureText(info(vp))) + 6 * dp
            val left = x - 3 * dp
            val right = left + w
            // Riga più bassa libera che stia sopra la cima.
            var chosen = -1
            for (row in maxRows - 1 downTo 0) {
                val bottom = top + row * rowH + rowH
                if (bottom > y - 12 * dp) continue
                val clash = placed.any { it.row == row && left < it.right + gap && right + gap > it.left }
                // Evita anche di tagliare con la propria linea etichette già piazzate nelle righe più basse.
                val crosses = placed.any { it.row > row && x >= it.left && x <= it.right } ||
                    placed.any { it.row < row && it.x >= left && it.x <= right }
                if (!clash && !crosses) { chosen = row; break }
            }
            if (chosen < 0) continue
            placed += Placed(vp, x, y, left, right, chosen)
        }

        for (pl in placed) {
            val alpha = if (pl.vp.visible) 255 else 110
            linePaint.alpha = alpha; dotPaint.alpha = alpha; namePaint.alpha = alpha; infoPaint.alpha = alpha
            val labelTop = top + pl.row * rowH
            canvas.drawLine(pl.x, labelTop + rowH - 2 * dp, pl.x, pl.y - 4 * dp, linePaint)
            canvas.drawCircle(pl.x, pl.y, 3 * dp, dotPaint)
            canvas.drawText(pl.vp.peak.name, pl.left, labelTop + 15 * dp, namePaint)
            canvas.drawText(info(pl.vp), pl.left, labelTop + 28 * dp, infoPaint)
        }
    }

    private fun info(vp: VisiblePeak): String {
        val km = vp.distanceM / 1000
        val dist = if (km < 10) String.format(Locale.ITALY, "%.1f km", km) else "${km.roundToInt()} km"
        return "${vp.peak.ele.roundToInt()} m · $dist"
    }

    // --- Calibrazione manuale: trascina per spostare, pizzica per regolare il campo visivo ---

    private var lastX = 0f
    private var lastY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val c = calibration
            calibration = c.copy(fovScale = (c.fovScale * d.scaleFactor).coerceIn(0.6, 1.6))
            onCalibrationChanged?.invoke(calibration)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!calibrating) return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                if (lastX.isNaN()) { lastX = e.x; lastY = e.y; return true }
                val dpp = projector?.degreesPerPixel() ?: return true
                val c = calibration
                calibration = c.copy(
                    // Trascinare a destra sposta le etichette a destra.
                    headingOffsetDeg = Geo.angleDiff(c.headingOffsetDeg - (e.x - lastX) * dpp, 0.0),
                    pitchOffsetDeg = (c.pitchOffsetDeg + (e.y - lastY) * dpp).coerceIn(-10.0, 10.0),
                )
                onCalibrationChanged?.invoke(calibration)
                lastX = e.x; lastY = e.y
            }
            // Dopo un pizzico il dito rimasto riparte da dove si trova, senza salti.
            MotionEvent.ACTION_POINTER_UP -> { lastX = Float.NaN; lastY = Float.NaN }
        }
        return true
    }
}
