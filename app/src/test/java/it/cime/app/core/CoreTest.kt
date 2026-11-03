package it.cime.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

class GeoTest {
    @Test fun oneDegreeOfLatitude() {
        assertEquals(111_195.0, Geo.distance(45.0, 9.0, 46.0, 9.0), 5.0)
    }

    @Test fun bearings() {
        assertEquals(0.0, Geo.bearing(45.0, 9.0, 46.0, 9.0), 1e-9)
        assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, Geo.bearing(46.0, 9.0, 45.0, 9.0), 1e-9)
        assertEquals(270.0, Geo.bearing(0.0, 1.0, 0.0, 0.0), 1e-9)
    }

    @Test fun destinationRoundTrip() {
        val (lat, lon) = Geo.destination(45.5, 9.2, 37.0, 42_000.0)
        assertEquals(42_000.0, Geo.distance(45.5, 9.2, lat, lon), 0.5)
        assertEquals(37.0, Geo.bearing(45.5, 9.2, lat, lon), 1e-6)
    }

    @Test fun curvatureAt100km() {
        // 100 km: 785 m di curvatura, ridotti del 13% dalla rifrazione.
        assertEquals(683.0, Geo.curvatureDrop(100_000.0), 1.0)
    }

    @Test fun angleDiffWraps() {
        assertEquals(-20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
    }
}

/** Terreno sintetico: pianura a 0 m con una cresta e alcuni coni. */
object Synthetic {
    const val NORTH = 45.3
    const val WEST = 8.8
    const val CELL = 3.0 / 3600
    const val ROWS = 721      // 0.6°
    const val COLS = 721

    val peakA = Peak(1, "Nascosta", 45.0, 9.2, 1000.0)     // ~15.7 km a est, dietro la cresta
    val peakB = Peak(2, "Nord", 45.15, 9.0, 1000.0)       // ~16.7 km a nord, libera
    val peakC = Peak(3, "Alta", 45.0, 9.15, 3000.0)       // ~11.8 km a est, più alta della cresta
    val peaks = listOf(peakA, peakB, peakC)

    fun height(lat: Double, lon: Double): Double {
        var h = 0.0
        // Cresta nord-sud a lon 9.1 (~7.9 km a est dell'osservatore), alta 800 m, larga ~300 m.
        if (lat in 44.95..45.05 && kotlin.math.abs(lon - 9.1) < 0.002) h = 800.0
        for (p in peaks) {
            val d = Geo.distance(lat, lon, p.lat, p.lon)
            h = max(h, p.ele * (1 - d / 1500.0))
        }
        return h
    }

    fun write(file: File): RegionPack {
        val hs = ShortArray(ROWS * COLS)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            hs[r * COLS + c] = height(NORTH - r * CELL, WEST + c * CELL).toInt().toShort()
        }
        RegionPack.write(file, "Test àè", NORTH, WEST, CELL, ROWS, COLS, hs, peaks)
        return RegionPack.open(file)
    }
}

class RegionPackTest {
    @Test fun roundTrip() {
        val f = File.createTempFile("test", ".pkpack")
        try {
            val p = Synthetic.write(f)
            assertEquals("Test àè", p.name)
            assertEquals(3, p.peaks.size)
            assertEquals("Alta", p.peaks[2].name)
            assertEquals(3000.0, p.peaks[2].ele, 1e-3)
            assertTrue(p.contains(45.0, 9.0))
            assertFalse(p.contains(46.0, 9.0))
            assertEquals(0.0, p.elevationAt(45.2, 8.9), 1e-9)
            // In cima al cono la quota del DEM è vicina a quella della cima.
            assertEquals(3000.0, p.elevationAt(45.0, 9.15), 60.0)
            assertTrue(p.elevationAt(47.0, 9.0).isNaN())
            assertEquals(Synthetic.CELL * Math.PI / 180 * Geo.EARTH_RADIUS_M, p.cellMeters, 1e-6)
        } finally { f.delete() }
    }

    @Test fun bilinearMidpoint() {
        val f = File.createTempFile("bil", ".pkpack")
        try {
            RegionPack.write(f, "b", 1.0, 0.0, 1.0, 2, 2, shortArrayOf(0, 100, 200, 300), emptyList())
            val p = RegionPack.open(f)
            assertEquals(150.0, p.elevationAt(0.5, 0.5), 1e-9)
            assertEquals(100.0, p.elevationAt(1.0, 1.0), 1e-9)
            assertEquals(200.0, p.elevationAt(0.0, 0.0), 1e-9)
        } finally { f.delete() }
    }
}

class ViewshedTest {
    @Test fun visibilityAndSkyline() {
        val f = File.createTempFile("vshed", ".pkpack")
        try {
            val pack = Synthetic.write(f)
            val r = Viewshed(pack, maxDistanceM = 30_000.0).compute(45.0, 9.0, pack.peaks)!!
            val byName = r.peaks.associateBy { it.peak.name }
            assertFalse("dietro la cresta", byName.getValue("Nascosta").visible)
            assertTrue(byName.getValue("Nord").visible)
            assertTrue(byName.getValue("Alta").visible)

            val north = byName.getValue("Nord")
            assertEquals(0.0, north.azimuthDeg, 0.01)
            val expected = Math.toDegrees(Geo.elevationAngle(1.7, 1000.0, north.distanceM))
            assertEquals(expected, north.elevationDeg, 1e-6)

            // Orizzonte a est dominato dalla cima alta (~14°), a sud piatto (leggermente negativo).
            val eastIdx = (90.0 / r.skylineStepDeg).toInt()
            val southIdx = (180.0 / r.skylineStepDeg).toInt()
            val northIdx = 0
            assertEquals(byName.getValue("Alta").elevationDeg, r.skyline[eastIdx].toDouble(), 0.5)
            assertTrue(r.skyline[southIdx] < 0.05f)
            assertEquals(north.elevationDeg, r.skyline[northIdx].toDouble(), 0.4)
            // A 30° di azimut c'è solo pianura.
            assertTrue(r.skyline[(30 / r.skylineStepDeg).toInt()] < 0.05f)
            // Un azimut che incrocia la cresta ma non le cime: ~atan(800/8000/cos(az)).
            val az = 80.0
            val ridgeDist = Geo.distance(45.0, 9.0, 45.0, 9.1) / kotlin.math.sin(Math.toRadians(az))
            val ridgeAngle = Math.toDegrees(atan((800.0 - 1.7) / ridgeDist))
            assertEquals(ridgeAngle, r.skyline[(az / r.skylineStepDeg).toInt()].toDouble(), 0.4)
        } finally { f.delete() }
    }

    @Test fun observerOutsideDemReturnsNull() {
        val f = File.createTempFile("vshed2", ".pkpack")
        try {
            val pack = Synthetic.write(f)
            assertEquals(null, Viewshed(pack).compute(40.0, 9.0, pack.peaks))
        } finally { f.delete() }
    }
}

class ProjectorTest {
    // Telefono in verticale, fotocamera posteriore verso il nord magnetico:
    // X telefono = est, Y telefono = alto, Z telefono = sud.
    private val facingNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    @Test fun centerAndOffsets() {
        val p = Projector(facingNorth, 0.0, 1000f, 2000f, 1500.0, Calibration())
        val out = FloatArray(2)
        assertTrue(p.project(0.0, 0.0, out))
        assertEquals(500f, out[0], 1e-3f); assertEquals(1000f, out[1], 1e-3f)

        assertTrue(p.project(10.0, 0.0, out))
        assertEquals(500 + 1500 * tan(Math.toRadians(10.0)), out[0].toDouble(), 1e-2)
        assertEquals(1000f, out[1], 1e-3f)

        assertTrue(p.project(0.0, 5.0, out))
        assertEquals(1000 - 1500 * tan(Math.toRadians(5.0)), out[1].toDouble(), 1e-2)

        assertFalse("alle spalle", p.project(180.0, 0.0, out))
        assertEquals(0.0, p.centerAzimuthDeg(), 1e-6)
    }

    @Test fun declinationAndCalibration() {
        val out = FloatArray(2)
        // Declinazione +3°: il nord magnetico è a 3° geografici, quindi una cima a 3° sta al centro.
        val p = Projector(facingNorth, 3.0, 1000f, 2000f, 1500.0, Calibration())
        assertTrue(p.project(3.0, 0.0, out)); assertEquals(500f, out[0], 1e-3f)
        assertEquals(3.0, p.centerAzimuthDeg(), 1e-6)

        val c = Projector(facingNorth, 0.0, 1000f, 2000f, 1500.0, Calibration(headingOffsetDeg = 2.0, pitchOffsetDeg = 1.0, fovScale = 1.2))
        assertTrue(c.project(2.0, 1.0, out))
        assertEquals(500f, out[0], 1e-3f); assertEquals(1000f, out[1], 1e-3f)
        assertEquals(Math.toDegrees(1 / 1800.0), c.degreesPerPixel(), 1e-12)
    }

    @Test fun rolledPhoneStillProjects() {
        // Telefono ruotato di 90° in senso antiorario (orizzontale): X telefono = alto, Y telefono = ovest.
        val landscape = floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f)
        val p = Projector(landscape, 0.0, 1000f, 2000f, 1500.0, Calibration())
        val out = FloatArray(2)
        // Una cima alta compare verso destra del telefono (che ora è "in alto").
        assertTrue(p.project(0.0, 10.0, out))
        assertTrue(out[0] > 500f)
        assertEquals(1000f, out[1], 1e-3f)
        assertEquals(0.0, p.centerAzimuthDeg(), 1e-6)
    }

    @Test fun focalFromSensor() {
        // Sensore 4:3 6.4×4.8 mm, focale 4.8 mm, schermo 1080×2340: l'altezza comanda il ritaglio.
        val f = Projector.focalPxPortrait(4.8, 6.4, 4.8, 1080f, 2340f)
        assertEquals(1755.0, f, 1e-6)
        // Il campo verticale visibile coincide con quello del lato lungo del sensore.
        val vfov = 2 * Math.toDegrees(atan(1170 / f))
        assertEquals(2 * Math.toDegrees(atan(3.2 / 4.8)), vfov, 1e-6)
        // Sensore 16:9: lo stream 4:3 usa tutto il lato corto.
        assertEquals(1755.0, Projector.focalPxPortrait(4.8, 8.533, 4.8, 1080f, 2340f), 1e-6)
    }
}
