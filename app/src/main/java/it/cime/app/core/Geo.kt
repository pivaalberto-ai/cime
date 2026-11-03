package it.cime.app.core

import kotlin.math.*

/** Funzioni geodetiche di base (sfera locale, sufficiente fino a ~200 km). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Coefficiente di rifrazione atmosferica standard (la luce "curva" verso il basso). */
    const val REFRACTION_K = 0.13

    /** Distanza in metri (haversine). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Azimut iniziale (gradi, 0 = nord geografico, senso orario). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    /** Punto a [distM] metri nella direzione [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val d = distM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Abbassamento apparente di un punto a distanza [d] per curvatura terrestre, al netto della rifrazione. */
    fun curvatureDrop(d: Double): Double = d * d / (2 * EARTH_RADIUS_M) * (1 - REFRACTION_K)

    /** Angolo di elevazione apparente (radianti) di un punto a quota [targetEle], distanza [d], da un occhio a [eyeEle]. */
    fun elevationAngle(eyeEle: Double, targetEle: Double, d: Double): Double =
        atan2(targetEle - eyeEle - curvatureDrop(d), d)

    fun normalizeDeg(a: Double): Double {
        val r = a % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Differenza angolare con segno in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }
}
