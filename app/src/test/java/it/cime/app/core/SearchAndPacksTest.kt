package it.cime.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakSearchTest {
    private val peaks = listOf(
        Peak(1, "Monte Rosa", 45.95, 9.30, 2036.0),           // vicino
        Peak(2, "Monte Rosa", 45.94, 7.87, 4634.0),           // lontano
        Peak(3, "Punta Dufour", 45.937, 7.867, 4634.0),
        Peak(4, "Pizzo d'Arera", 45.93, 9.82, 2512.0),
        Peak(5, "Cima Rosà", 46.0, 9.4, 1800.0),
        Peak(6, "Grignone", 45.953, 9.388, 2410.0),
    )
    private val s = PeakSearch(peaks)

    @Test fun normalizesAccentsCaseAndApostrophes() {
        assertEquals("pizzo d arera", PeakSearch.normalize("Pizzo d'Arèra"))
        assertEquals(listOf(4L), s.search("arera", 45.9, 9.3).map { it.peak.id })
        assertEquals(listOf(5L), s.search("ROSÀ", 45.9, 9.3).filter { it.peak.id == 5L }.map { it.peak.id })
    }

    @Test fun ranksPrefixThenWordThenContainsAndByDistance() {
        val hits = s.search("rosa", 45.95, 9.30).map { it.peak.id }
        // "Monte Rosa" contiene la parola, "Cima Rosà" pure: a parità di tipo, il più vicino prima.
        assertEquals(listOf(1L, 5L, 2L), hits)
        assertEquals(listOf(1L, 2L), s.search("monte r", 45.95, 9.30).map { it.peak.id })
        assertTrue(s.search("xyz", 45.9, 9.3).isEmpty())
    }

    @Test fun emptyQueryListsHighestNearby() {
        val hits = s.search("", 45.95, 9.35, nearKm = 60.0).map { it.peak.id }
        assertEquals(listOf(4L, 6L, 1L, 5L), hits)
    }
}

class PackInfoTest {
    @Test fun parsesTheBboxFromTheReleaseBody() {
        val body = "Pacchetto regione per Cime: Lombardia\nRiquadro: 45.4,8.5,46.7,11.0\n\n```\nTerreno…"
        assertArrayEquals(doubleArrayOf(45.4, 8.5, 46.7, 11.0), PackInfo.parseBbox(body), 1e-9)
        assertArrayEquals(doubleArrayOf(-12.5, -77.0, -11.0, -75.5), PackInfo.parseBbox("Riquadro: -12.5, -77, -11, -75.5"), 1e-9)
        assertNull(PackInfo.parseBbox("niente"))
        assertNull(PackInfo.parseBbox("Riquadro: 46,8,45,9"))   // sud > nord
        assertNull(PackInfo.parseBbox(null))
    }

    @Test fun coversAndNames() {
        val p = RemotePack("Lombardia", "lombardia.pkpack", "u", 28_500_452, "2026-11-03T17:09:00Z",
            doubleArrayOf(45.4, 8.5, 46.7, 11.0))
        assertTrue(p.covers(45.95, 9.39))
        assertFalse(p.covers(45.0, 7.0))
        assertEquals("Lombardia", PackInfo.titleFrom("Pacchetto: Lombardia", "lombardia.pkpack"))
        assertEquals("valle-d-aosta", PackInfo.titleFrom(null, "valle-d-aosta.pkpack"))
        assertEquals("28,5 MB", PackInfo.formatSize(28_500_452))
        // Le date ISO si confrontano come stringhe: serve a riconoscere gli aggiornamenti.
        assertTrue("2026-11-05T08:00:00Z" > "2026-11-03T17:09:00Z")
    }
}
