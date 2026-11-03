package it.cime.app.core

import kotlin.math.max
import kotlin.math.min

data class VisiblePeak(
    val peak: Peak,
    val distanceM: Double,
    /** Azimut geografico, gradi. */
    val azimuthDeg: Double,
    /** Elevazione apparente, gradi (già corretta per curvatura e rifrazione). */
    val elevationDeg: Double,
    /** false = la cima è nascosta dal terreno davanti. */
    val visible: Boolean,
)

class ViewResult(
    val observerLat: Double,
    val observerLon: Double,
    val eyeEle: Double,
    val peaks: List<VisiblePeak>,
    /** Elevazione dell'orizzonte (gradi) per ogni azimut, passo [skylineStepDeg]. */
    val skyline: FloatArray,
    val skylineStepDeg: Double,
)

/**
 * Calcola quali cime sono visibili da un punto e il profilo dell'orizzonte.
 * Puro calcolo, nessuna dipendenza Android: va eseguito su un thread in background.
 */
class Viewshed(
    private val dem: Dem,
    private val maxDistanceM: Double = 100_000.0,
    private val eyeHeightM: Double = 1.7,
    /** Tolleranza: il DEM a ~90 m smussa creste e cime, quindi concediamo un margine. */
    private val toleranceDeg: Double = 0.08,
    private val skylineStepDeg: Double = 0.5,
) {
    fun compute(lat: Double, lon: Double, peaks: List<Peak>, altitudeOverride: Double? = null): ViewResult? {
        val ground = dem.elevationAt(lat, lon)
        val eye = when {
            altitudeOverride != null -> altitudeOverride
            !ground.isNaN() -> ground + eyeHeightM
            else -> return null
        }
        val result = ArrayList<VisiblePeak>()
        for (p in peaks) {
            val d = Geo.distance(lat, lon, p.lat, p.lon)
            if (d < 200 || d > maxDistanceM) continue
            val az = Geo.bearing(lat, lon, p.lat, p.lon)
            val peakAngle = Geo.elevationAngle(eye, p.ele, d)
            val terrainMax = maxTerrainAngle(lat, lon, p.lat, p.lon, d, eye)
            val visible = peakAngle >= terrainMax - Math.toRadians(toleranceDeg)
            result += VisiblePeak(p, d, az, Math.toDegrees(peakAngle), visible)
        }
        val n = (360.0 / skylineStepDeg).toInt()
        val sky = FloatArray(n) { i -> Math.toDegrees(horizonAngle(lat, lon, i * skylineStepDeg, eye)).toFloat() }
        return ViewResult(lat, lon, eye, result, sky, skylineStepDeg)
    }

    /**
     * Massimo angolo di elevazione del terreno lungo il segmento osservatore → cima,
     * escludendo gli ultimi metri vicino alla cima stessa.
     */
    private fun maxTerrainAngle(lat0: Double, lon0: Double, lat1: Double, lon1: Double, d: Double, eye: Double): Double {
        val path = GreatCircle(lat0, lon0, lat1, lon1)
        val step = max(dem.cellMeters * 0.75, 25.0)
        val end = d - max(250.0, dem.cellMeters * 2)
        var best = Double.NEGATIVE_INFINITY
        var s = 50.0
        while (s < end) {
            path.at(s / d)
            val h = dem.elevationAt(path.lat, path.lon)
            if (!h.isNaN()) {
                val a = Geo.elevationAngle(eye, h, s)
                if (a > best) best = a
            }
            s += step
        }
        return best
    }

    /** Elevazione dell'orizzonte in una direzione: il passo cresce con la distanza per contenere i calcoli. */
    private fun horizonAngle(lat: Double, lon: Double, az: Double, eye: Double): Double {
        val (lat1, lon1) = Geo.destination(lat, lon, az, maxDistanceM)
        val path = GreatCircle(lat, lon, lat1, lon1)
        val base = max(dem.cellMeters * 0.75, 25.0)
        var best = -Math.PI / 2
        var s = 50.0
        while (s < maxDistanceM) {
            path.at(s / maxDistanceM)
            val h = dem.elevationAt(path.lat, path.lon)
            if (!h.isNaN()) {
                val a = Geo.elevationAngle(eye, h, s)
                if (a > best) best = a
            }
            s += max(base, min(s * 0.004, 400.0))
        }
        return best
    }
}

/**
 * Punti lungo il cerchio massimo tra due coordinate, interpolando i vettori unitari 3D.
 * Per archi brevi (< 2°) la frazione [at] coincide con la frazione di distanza a meno di 1e-4.
 */
internal class GreatCircle(lat0: Double, lon0: Double, lat1: Double, lon1: Double) {
    private val x0: Double; private val y0: Double; private val z0: Double
    private val dx: Double; private val dy: Double; private val dz: Double
    var lat = 0.0; private set
    var lon = 0.0; private set

    init {
        val a0 = Math.toRadians(lat0); val o0 = Math.toRadians(lon0)
        val a1 = Math.toRadians(lat1); val o1 = Math.toRadians(lon1)
        x0 = Math.cos(a0) * Math.cos(o0); y0 = Math.cos(a0) * Math.sin(o0); z0 = Math.sin(a0)
        dx = Math.cos(a1) * Math.cos(o1) - x0
        dy = Math.cos(a1) * Math.sin(o1) - y0
        dz = Math.sin(a1) - z0
    }

    fun at(t: Double) {
        val x = x0 + dx * t; val y = y0 + dy * t; val z = z0 + dz * t
        val n = Math.sqrt(x * x + y * y + z * z)
        lat = Math.toDegrees(Math.asin(z / n))
        lon = Math.toDegrees(Math.atan2(y, x))
    }
}
