package it.cime.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import it.cime.app.core.Alignment
import it.cime.app.core.Calibration
import it.cime.app.core.HorizonMatcher
import it.cime.app.core.LumaGrid
import it.cime.app.core.Projector
import it.cime.app.core.SkylineDetector
import it.cime.app.core.ViewResult
import kotlin.math.roundToInt

/**
 * Analizza i fotogrammi della fotocamera e, quando riconosce la linea dell'orizzonte,
 * propone la correzione di bussola e inclinazione. Gira sul thread dell'analisi, mai su quello principale.
 */
class HorizonAligner(
    /** Copia l'orientamento corrente nella matrice passata. */
    private val rotation: (FloatArray) -> Unit,
    private val view: () -> ViewResult?,
    private val declination: () -> Double,
    private val calibration: () -> Calibration,
    /** Focale (mm) e dimensioni del sensore (lato lungo, lato corto, mm); null se sconosciute. */
    private val optics: () -> Triple<Double, Double, Double>?,
    private val enabled: () -> Boolean,
    private val onResult: (Alignment) -> Unit,
) : ImageAnalysis.Analyzer {

    private val main = Handler(Looper.getMainLooper())
    private val rot = FloatArray(9)
    private val prevRot = FloatArray(9)
    private var hasPrev = false
    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < 350 || !enabled()) return
            val v = view() ?: return
            val opt = optics() ?: return
            rotation(rot)
            // Solo a telefono fermo: con il movimento l'immagine e i sensori non sono allineati nel tempo.
            val moved = hasPrev && cameraAngleDeg(rot, prevRot) > 1.0
            rot.copyInto(prevRot); hasPrev = true
            if (moved) return
            // La fotocamera deve guardare verso l'orizzonte (non il cielo o i piedi).
            if (kotlin.math.abs(rot[8]) > 0.6f) return
            lastRun = now

            val grid = toGrid(image)
            val cal = calibration()
            val f = Projector.focalPxPortrait(opt.first, opt.second, opt.third,
                grid.w.toFloat(), grid.h.toFloat(), grid.h.toDouble() / grid.w) * cal.fovScale
            val rows = SkylineDetector.detect(grid)
            val a = HorizonMatcher.match(rows, grid.w, grid.h, f, rot, declination(), v, cal) ?: return
            main.post { onResult(a) }
        } catch (_: Exception) {
            // Un fotogramma non analizzabile non deve mai bloccare la fotocamera.
        } finally {
            image.close()
        }
    }

    /** Angolo tra le direzioni della fotocamera di due orientamenti, in gradi. */
    private fun cameraAngleDeg(a: FloatArray, b: FloatArray): Double {
        val dot = (a[2] * b[2] + a[5] * b[5] + a[8] * b[8]).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(dot))
    }

    /** Luminanza raddrizzata in verticale e ridotta a 120 colonne (media di 2×2 campioni per cella). */
    private fun toGrid(image: ImageProxy): LumaGrid {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = image.width
        val h = image.height
        val rotDeg = image.imageInfo.rotationDegrees
        val upW = if (rotDeg == 90 || rotDeg == 270) h else w
        val upH = if (rotDeg == 90 || rotDeg == 270) w else h
        val gw = GRID_W
        val gh = (gw.toDouble() * upH / upW).roundToInt()
        val cell = upW.toDouble() / gw
        val data = FloatArray(gw * gh)
        for (r in 0 until gh) for (c in 0 until gw) {
            var s = 0
            for (sy in 0..1) for (sx in 0..1) {
                val u = ((c + 0.25 + 0.5 * sx) * cell).toInt().coerceIn(0, upW - 1)
                val v = ((r + 0.25 + 0.5 * sy) * cell).toInt().coerceIn(0, upH - 1)
                val (x, y) = when (rotDeg) {
                    90 -> v to (h - 1 - u)
                    180 -> (w - 1 - u) to (h - 1 - v)
                    270 -> (w - 1 - v) to u
                    else -> u to v
                }
                s += buf.get(y * rs + x * ps).toInt() and 0xFF
            }
            data[r * gw + c] = s / (4 * 255f)
        }
        return LumaGrid(gw, gh, data)
    }

    companion object {
        const val GRID_W = 120
    }
}
