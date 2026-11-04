package it.cime.app.core

import java.text.Normalizer

data class SearchHit(val peak: Peak, val distanceM: Double)

/** Ricerca delle cime per nome, senza badare ad accenti, maiuscole e apostrofi. */
class PeakSearch(private val peaks: List<Peak>) {
    private val keys: List<String> = peaks.map { normalize(it.name) }

    /**
     * Prima le cime il cui nome (o una parola del nome) comincia con il testo cercato, poi quelle che lo
     * contengono; a parità, le più vicine. Con testo vuoto restituisce le cime più alte entro [nearKm].
     */
    fun search(query: String, lat: Double, lon: Double, limit: Int = 60, nearKm: Double = 60.0): List<SearchHit> {
        val q = normalize(query)
        if (q.isEmpty()) {
            return peaks.asSequence()
                .map { SearchHit(it, Geo.distance(lat, lon, it.lat, it.lon)) }
                .filter { it.distanceM < nearKm * 1000 }
                .sortedByDescending { it.peak.ele }
                .take(limit).toList()
        }
        val ranked = ArrayList<Pair<Int, SearchHit>>()
        for (i in peaks.indices) {
            val k = keys[i]
            val rank = when {
                k.startsWith(q) -> 0
                k.contains(" $q") -> 1
                k.contains(q) -> 2
                else -> continue
            }
            val p = peaks[i]
            ranked += rank to SearchHit(p, Geo.distance(lat, lon, p.lat, p.lon))
        }
        return ranked.sortedWith(compareBy({ it.first }, { it.second.distanceM })).take(limit).map { it.second }
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val separators = Regex("[^a-z0-9]+")

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()
                .replace(separators, " ").trim()
    }
}
