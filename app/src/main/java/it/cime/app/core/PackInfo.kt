package it.cime.app.core

import java.util.Locale

/** Un pacchetto regione pubblicato tra le Release del repository. */
data class RemotePack(
    /** Nome mostrato (es. "Lombardia"). */
    val title: String,
    /** Nome del file (es. "lombardia.pkpack"). */
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Data di pubblicazione del file, ISO 8601: serve a capire se c'è un aggiornamento. */
    val updatedAt: String,
    /** Riquadro sud, ovest, nord, est richiesto quando è stato creato, se noto. */
    val bbox: DoubleArray?,
) {
    fun covers(lat: Double, lon: Double): Boolean =
        bbox != null && lat in bbox[0]..bbox[2] && lon in bbox[1]..bbox[3]
}

object PackInfo {
    private val bboxRe = Regex("""Riquadro:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")

    /** Riquadro scritto nella descrizione della Release dal flusso "Crea pacchetto regione". */
    fun parseBbox(body: String?): DoubleArray? {
        val m = bboxRe.find(body ?: return null) ?: return null
        val v = m.groupValues.drop(1).map { it.toDouble() }
        return if (v[0] < v[2] && v[1] < v[3]) v.toDoubleArray() else null
    }

    /** "Pacchetto: Lombardia" → "Lombardia". */
    fun titleFrom(releaseName: String?, fileName: String): String =
        releaseName?.removePrefix("Pacchetto:")?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileName.removeSuffix(".pkpack")

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ITALY, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> "${bytes / 1000} kB"
        else -> "$bytes B"
    }
}
