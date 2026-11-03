package it.cime.app.core

import kotlin.math.cos
import kotlin.math.sin

/** Correzioni manuali impostate dall'utente trascinando/pizzicando lo schermo. */
data class Calibration(
    val headingOffsetDeg: Double = 0.0,
    val pitchOffsetDeg: Double = 0.0,
    val fovScale: Double = 1.0,
)

/**
 * Proietta direzioni (azimut geografico, elevazione) sullo schermo.
 *
 * [rotation] è la matrice 3x3 riga-per-riga di SensorManager.getRotationMatrixFromVector:
 * porta un vettore dal sistema del telefono (X destra, Y alto, Z uscente dallo schermo)
 * al sistema mondo (X est, Y nord MAGNETICO, Z alto). La fotocamera posteriore guarda lungo -Z.
 */
class Projector(
    private val rotation: FloatArray,
    private val declinationDeg: Double,
    private val widthPx: Float,
    private val heightPx: Float,
    /** Focale in pixel dello schermo, già scalata per come la preview è ritagliata. */
    focalPx: Double,
    private val cal: Calibration,
) {
    private val f = focalPx * cal.fovScale
    private val cx = widthPx / 2.0
    private val cy = heightPx / 2.0

    /** Coordinate schermo in [out] (x, y); false se il punto è dietro la fotocamera. */
    fun project(azimuthTrueDeg: Double, elevationDeg: Double, out: FloatArray): Boolean {
        // Azimut geografico → magnetico (sistema della matrice), più la correzione utente.
        val az = Math.toRadians(azimuthTrueDeg - declinationDeg - cal.headingOffsetDeg)
        val el = Math.toRadians(elevationDeg - cal.pitchOffsetDeg)
        val e = cos(el) * sin(az)
        val n = cos(el) * cos(az)
        val u = sin(el)
        val r = rotation
        // v_dev = Rᵀ · v_mondo
        val vx = r[0] * e + r[3] * n + r[6] * u
        val vy = r[1] * e + r[4] * n + r[7] * u
        val vz = r[2] * e + r[5] * n + r[8] * u
        val forward = -vz
        if (forward < 0.05) return false
        out[0] = (cx + f * vx / forward).toFloat()
        out[1] = (cy - f * vy / forward).toFloat()
        return true
    }

    /** Azimut geografico verso cui punta il centro dell'inquadratura (per la bussola a schermo). */
    fun centerAzimuthDeg(): Double {
        // Direzione -Z del telefono nel mondo = -(colonna 3 di R)
        val e = -rotation[2].toDouble()
        val n = -rotation[5].toDouble()
        val magnetic = Math.toDegrees(Math.atan2(e, n))
        return Geo.normalizeDeg(magnetic + declinationDeg + cal.headingOffsetDeg)
    }

    /** Gradi per pixel al centro: serve a convertire i trascinamenti in correzioni angolari. */
    fun degreesPerPixel(): Double = Math.toDegrees(1.0 / f)

    companion object {
        /**
         * Focale in pixel della preview in verticale con PreviewView FILL_CENTER.
         * [sensorLongMm]/[sensorShortMm]: dimensioni fisiche del sensore (orizzontale nativo);
         * in verticale il lato corto del sensore diventa la larghezza dell'immagine.
         */
        fun focalPxPortrait(
            focalMm: Double, sensorLongMm: Double, sensorShortMm: Double,
            viewW: Float, viewH: Float, streamAspect: Double = 4.0 / 3.0,
        ): Double {
            // Lo stream (di solito 4:3) è il campo inquadrato; se il sensore ha un rapporto diverso, viene ritagliato.
            val sensorAspect = sensorLongMm / sensorShortMm
            val usedShortMm = if (sensorAspect > streamAspect) sensorShortMm else sensorLongMm / streamAspect
            // Immagine in verticale: larghezza ∝ lato corto, altezza ∝ lato lungo (rapporto streamAspect).
            val imgW = maxOf(viewW.toDouble(), viewH / streamAspect)
            return focalMm * imgW / usedShortMm
        }
    }
}
