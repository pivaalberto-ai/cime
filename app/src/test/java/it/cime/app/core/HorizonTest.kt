package it.cime.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Genera "foto" sintetiche: cielo chiaro sopra la linea calcolata, terreno scuro e ruvido sotto. */
object Render {
    const val W = 120
    const val H = 160
    /** ~50° di campo orizzontale sulla griglia. */
    val F = W / 2.0 / Math.tan(Math.toRadians(25.0))

    /** Telefono in piedi con la fotocamera verso [azDeg] (magnetico), inclinata di [pitchDeg]. */
    fun upright(azDeg: Double, pitchDeg: Double): FloatArray {
        val a = Math.toRadians(azDeg); val p = Math.toRadians(pitchDeg)
        val cam = doubleArrayOf(sin(a) * cos(p), cos(a) * cos(p), sin(p))
        val z = DoubleArray(3) { -cam[it] }
        val x = doubleArrayOf(cos(a), -sin(a), 0.0)
        val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        return floatArrayOf(
            x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
        )
    }

    /** Elevazione e azimut del punto (x, y) della griglia, in coordinate continue. */
    fun direction(r: FloatArray, xPx: Double, yPx: Double): Pair<Double, Double> {
        val x = (xPx - W / 2.0) / F
        val y = -(yPx - H / 2.0) / F
        val e = r[0] * x + r[1] * y - r[2]
        val n = r[3] * x + r[4] * y - r[5]
        val u = r[6] * x + r[7] * y - r[8]
        return Math.toDegrees(atan2(e, n)) to Math.toDegrees(atan2(u, hypot(e, n)))
    }

    /** Ogni pixel è la media di 4×4 campioni, come il sensore di una fotocamera vera (bordi sfumati). */
    fun image(view: ViewResult, trueRotation: FloatArray, seed: Int = 1, clouds: Boolean = false): LumaGrid {
        val rnd = Random(seed)
        val d = FloatArray(W * H)
        val ss = 4
        for (row in 0 until H) for (c in 0 until W) {
            var acc = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val (az, el) = direction(trueRotation, c + (sx + 0.5) / ss, row + (sy + 0.5) / ss)
                val sky = HorizonMatcher.sky(view, az)
                acc += if (el > sky) {
                    var v = 0.78f + 0.1f * (row.toFloat() / H)
                    // Nuvole: bande leggere sopra la linea.
                    if (clouds && el > sky + 4 && sin(az * 0.3 + el) > 0.6) v -= 0.08f
                    v
                } else {
                    // Terreno più scuro, con un crinale vicino ancora più scuro (secondo bordo da ignorare).
                    if (el < sky - 3.0) 0.22f else 0.42f
                }
            }
            val sky = acc / (ss * ss) > 0.6f
            d[row * W + c] = acc / (ss * ss) + (rnd.nextFloat() - 0.5f) * (if (sky) 0.03f else 0.12f)
        }
        return LumaGrid(W, H, d)
    }

    /** Posizione esatta della linea nella colonna [c] (centro colonna), per i confronti. */
    fun trueRow(view: ViewResult, r: FloatArray, c: Int): Double {
        var y = 0.0
        while (y < H) {
            val (az, el) = direction(r, c + 0.5, y)
            if (el < HorizonMatcher.sky(view, az)) return y
            y += 0.02
        }
        return Double.NaN
    }
}

class HorizonTest {
    private fun world(): Pair<RegionPack, ViewResult> {
        val f = File.createTempFile("horizon", ".pkpack")
        f.deleteOnExit()
        val pack = Synthetic.write(f)
        // Osservatore a ovest della cresta e delle cime: guardando a est si vede un profilo movimentato.
        val view = Viewshed(pack, maxDistanceM = 30_000.0).compute(45.0, 9.0, pack.peaks)!!
        return pack to view
    }

    @Test fun detectsTheSkyline() {
        val (_, view) = world()
        val rot = Render.upright(85.0, 6.0)
        val img = Render.image(view, rot)
        val rows = SkylineDetector.detect(img)
        var ok = 0; var valid = 0
        for (c in 0 until Render.W) {
            if (rows[c].isNaN()) continue
            valid++
            if (kotlin.math.abs(rows[c] - Render.trueRow(view, rot, c)) < 0.35) ok++
        }
        assertTrue("colonne valide: $valid", valid > Render.W * 0.8)
        assertTrue("colonne entro 1/3 di pixel: $ok su $valid", ok > valid * 0.9)
    }

    @Test fun recoversCompassAndPitchError() {
        val (_, view) = world()
        val trueRot = Render.upright(85.0, 6.0)
        // Il telefono crede di guardare 6° più a destra e 1,5° più in alto di quanto fa davvero.
        val sensorRot = Render.upright(91.0, 7.5)
        val img = Render.image(view, trueRot)
        val rows = SkylineDetector.detect(img)
        val a = HorizonMatcher.match(rows, Render.W, Render.H, Render.F, sensorRot, 0.0, view, Calibration())
        assertNotNull(a)
        assertEquals(-6.0, a!!.headingOffsetDeg, 0.3)
        assertEquals(-1.5, a.pitchOffsetDeg, 0.3)

        // Con la correzione trovata, il centro dello schermo indica di nuovo la direzione vera.
        val p = Projector(sensorRot, 0.0, 1000f, 1500f, 1000.0, Calibration(a.headingOffsetDeg, a.pitchOffsetDeg))
        assertEquals(85.0, p.centerAzimuthDeg(), 0.4)
    }

    @Test fun worksFromAnExistingCalibrationAndWithClouds() {
        val (_, view) = world()
        val trueRot = Render.upright(80.0, 5.0)
        val sensorRot = Render.upright(68.0, 5.0)   // bussola indietro di 12°
        val img = Render.image(view, trueRot, seed = 7, clouds = true)
        val rows = SkylineDetector.detect(img)
        val a = HorizonMatcher.match(rows, Render.W, Render.H, Render.F, sensorRot, 0.0, view,
            Calibration(headingOffsetDeg = 9.0))
        assertNotNull(a)
        assertEquals(12.0, a!!.headingOffsetDeg, 0.4)
    }

    @Test fun ignoresAForegroundObstacle() {
        val (_, view) = world()
        val trueRot = Render.upright(85.0, 6.0)
        val sensorRot = Render.upright(80.0, 6.0)   // bussola indietro di 5°
        val img = Render.image(view, trueRot)
        // Una persona (o la croce di vetta) scura copre il 15% delle colonne fino in cima all'immagine.
        for (r in 20 until Render.H) for (c in 15 until 33) img.data[r * Render.W + c] = 0.15f
        val a = HorizonMatcher.match(SkylineDetector.detect(img), Render.W, Render.H, Render.F, sensorRot, 0.0, view, Calibration())
        assertNotNull(HorizonMatcher.lastDiagnostics, a)
        assertEquals(5.0, a!!.headingOffsetDeg, 0.3)
    }

    @Test fun rejectsFlatHorizonAndBlankImages() {
        val (_, view) = world()
        // Verso sud c'è solo pianura: nessun riferimento per la direzione.
        val south = Render.upright(200.0, 2.0)
        val flat = SkylineDetector.detect(Render.image(view, south))
        assertNull(HorizonMatcher.match(flat, Render.W, Render.H, Render.F, south, 0.0, view, Calibration()))

        // Immagine uniforme con solo rumore (es. nebbia): niente correzione.
        val rnd = Random(3)
        val fog = LumaGrid(Render.W, Render.H, FloatArray(Render.W * Render.H) { 0.7f + rnd.nextFloat() * 0.02f })
        val east = Render.upright(85.0, 6.0)
        assertNull(HorizonMatcher.match(SkylineDetector.detect(fog), Render.W, Render.H, Render.F, east, 0.0, view, Calibration()))
    }
}
