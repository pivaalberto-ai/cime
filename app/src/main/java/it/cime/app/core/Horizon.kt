package it.cime.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Immagine in toni di grigio già raddrizzata (in verticale), riga per riga, valori 0..1. */
class LumaGrid(val w: Int, val h: Int, val data: FloatArray) {
    init { require(data.size == w * h) }
    operator fun get(c: Int, r: Int) = data[r * w + c]
}

/**
 * Trova nell'immagine la linea tra cielo e montagne.
 *
 * Per ogni colonna cerca un bordo netto (cambio di luminosità in verticale), preferendo quello più in alto
 * con cielo "pulito" sopra; la programmazione dinamica impone che la linea sia continua da sinistra a destra.
 */
object SkylineDetector {
    /**
     * Posizione verticale del bordo per ogni colonna, in pixel con decimali
     * (0 = bordo superiore dell'immagine), oppure NaN se la colonna non ha un bordo affidabile.
     */
    fun detect(g: LumaGrid): FloatArray {
        val w = g.w; val h = g.h
        // Bordo verticale della singola colonna (mediarlo con le vicine sposterebbe la linea sui pendii ripidi;
        // il rumore è già ridotto dal rimpicciolimento dell'immagine).
        val edge = FloatArray(w * h)
        for (r in 1 until h - 1) for (c in 0 until w) edge[r * w + c] = abs(g[c, r - 1] - g[c, r + 1])
        // Normalizzazione: il 98° percentile dei bordi vale 1, così le soglie non dipendono dall'esposizione.
        val sorted = edge.copyOf().also { it.sort() }
        val ref = max(sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)], 0.02f)
        val noise = 0.12f
        for (i in edge.indices) edge[i] = max(0f, edge[i] / ref - noise)

        // Punteggio: forza del bordo meno il bordo più forte che ha sopra di sé nella stessa colonna.
        // Un crinale vicino, sotto la vera linea dell'orizzonte, ha sopra il bordo netto del cielo e perde;
        // i bordi deboli delle nuvole pesano poco. La riga subito sopra non conta: un bordo netto
        // occupa sempre due righe vicine.
        val alpha = 0.6f
        val score = FloatArray(w * h)
        for (c in 0 until w) {
            var maxAbove = 0f
            for (r in 0 until h) {
                score[r * w + c] = edge[r * w + c] - alpha * maxAbove
                if (r >= 1) maxAbove = max(maxAbove, edge[(r - 1) * w + c])
            }
        }

        // Percorso continuo di punteggio massimo. Il salto ammesso tra colonne vicine è ampio
        // perché i profili delle montagne possono essere molto ripidi; la penalità è lieve.
        val jump = 6
        val beta = 0.03f
        val dp = FloatArray(w * h)
        val from = IntArray(w * h)
        for (r in 0 until h) dp[r * w] = score[r * w]
        for (c in 1 until w) for (r in 0 until h) {
            var best = Float.NEGATIVE_INFINITY; var arg = r
            for (d in -jump..jump) {
                val rr = r + d
                if (rr < 0 || rr >= h) continue
                val v = dp[rr * w + c - 1] - beta * abs(d)
                if (v > best) { best = v; arg = rr }
            }
            dp[r * w + c] = best + score[r * w + c]
            from[r * w + c] = arg
        }
        var r = 0
        for (rr in 1 until h) if (dp[rr * w + w - 1] > dp[r * w + w - 1]) r = rr
        val out = IntArray(w)
        for (c in w - 1 downTo 0) {
            out[c] = r
            if (c > 0) r = from[r * w + c]
        }
        // Scarta le colonne dove il percorso passa su un bordo debole o sui margini dell'immagine;
        // per le altre, posizione sotto il pixel con una parabola sui valori di bordo vicini.
        val res = FloatArray(w)
        for (c in 0 until w) {
            val rr = out[c]
            val e0 = edge[rr * w + c]
            if (rr < 2 || rr > h - 3 || e0 < 0.25f) { res[c] = Float.NaN; continue }
            val em = edge[(rr - 1) * w + c]
            val ep = edge[(rr + 1) * w + c]
            val den = em - 2 * e0 + ep
            val delta = if (den < -1e-6f) (0.5f * (em - ep) / den).coerceIn(-0.5f, 0.5f) else 0f
            // Il bordo calcolato sulla riga r sta tra i pixel r-1 e r+1, cioè al centro del pixel r.
            res[c] = rr + delta + 0.5f
        }
        return res
    }
}

data class Alignment(
    /** Nuova correzione di direzione e inclinazione (sostituisce quella attuale). */
    val headingOffsetDeg: Double,
    val pitchOffsetDeg: Double,
    /** Errore medio residuo tra linea vista e linea calcolata, gradi. */
    val errorDeg: Double,
    /** Colonne usate nel confronto. */
    val samples: Int,
)

/**
 * Confronta la linea dell'orizzonte vista dalla fotocamera con quella calcolata dal terreno e trova la
 * correzione di direzione (bussola) e inclinazione che le fa combaciare.
 */
object HorizonMatcher {
    /** Valori dell'ultimo confronto, per capire perché è stato accettato o rifiutato. */
    @Volatile var lastDiagnostics = ""

    /**
     * @param rows linea rilevata (posizione verticale per colonna, NaN = assente) su una griglia [w]×[h]
     * @param focalPx focale in pixel della griglia (già moltiplicata per la correzione del campo visivo)
     * @param rotation matrice telefono → mondo magnetico al momento dello scatto
     * @return la correzione, oppure null se il confronto non è abbastanza sicuro
     */
    fun match(
        rows: FloatArray, w: Int, h: Int, focalPx: Double, rotation: FloatArray, declinationDeg: Double,
        view: ViewResult, current: Calibration, searchDeg: Double = 20.0,
    ): Alignment? {
        // Direzione (azimut geografico senza correzione, elevazione) di ogni punto della linea rilevata.
        val az = ArrayList<Double>(); val el = ArrayList<Double>()
        val r = rotation
        for (c in 0 until w) {
            val row = rows[c]
            if (row.isNaN()) continue
            val x = (c + 0.5 - w / 2.0) / focalPx
            val y = -(row - h / 2.0) / focalPx
            val z = -1.0
            val e = r[0] * x + r[1] * y + r[2] * z
            val n = r[3] * x + r[4] * y + r[5] * z
            val u = r[6] * x + r[7] * y + r[8] * z
            az += Math.toDegrees(atan2(e, n)) + declinationDeg
            el += Math.toDegrees(atan2(u, hypot(e, n)))
        }
        val m = az.size
        if (m < w * 0.4) { lastDiagnostics = "colonne=$m (poche)"; return null }

        val diffs = DoubleArray(m)
        val weight = DoubleArray(m)
        val tmp = ArrayList<Double>(m)
        val clip = 1.0
        /** Colonne considerate (false = ostacolo vicino o tratto che non combacia, scartato). */
        val use = BooleanArray(m) { true }

        /**
         * Errore medio (robusto) per una correzione di direzione; restituisce anche la correzione di inclinazione.
         * L'orizzonte vicino pesa meno: un errore GPS di qualche decina di metri lo sposta di gradi
         * (40 m a 1 km ≈ 2°), mentre le montagne lontane non si muovono.
         */
        fun cost(hOff: Double, outPitch: DoubleArray?): Double {
            tmp.clear()
            for (i in 0 until m) {
                val a = az[i] + hOff
                diffs[i] = sky(view, a) - el[i]
                weight[i] = ((skyDist(view, a) - 500.0) / 4000.0).coerceIn(0.1, 1.0)
                if (use[i] && weight[i] >= 0.5) tmp += diffs[i]
            }
            if (tmp.size < m / 4) { tmp.clear(); for (i in 0 until m) if (use[i]) tmp += diffs[i] }
            tmp.sort()
            val p = tmp[tmp.size / 2]
            var s = 0.0; var ws = 0.0
            for (i in 0 until m) if (use[i]) { s += weight[i] * min(abs(diffs[i] - p), clip); ws += weight[i] }
            outPitch?.set(0, p)
            return s / ws
        }

        val center = current.headingOffsetDeg
        val coarseStep = 0.25
        val steps = (2 * searchDeg / coarseStep).toInt()
        val costs = DoubleArray(steps + 1)

        /** Ricerca della direzione migliore sulle colonne attive: grossolana e poi fine. */
        fun search(): Double {
            var bestI = 0
            for (i in 0..steps) {
                costs[i] = cost(center - searchDeg + i * coarseStep, null)
                if (costs[i] < costs[bestI]) bestI = i
            }
            var bestH = center - searchDeg + bestI * coarseStep
            var bestCost = costs[bestI]
            var hh = bestH - coarseStep
            while (hh <= bestH + coarseStep + 1e-9) {
                val c = cost(hh, null)
                if (c < bestCost) { bestCost = c; bestH = hh }
                hh += 0.05
            }
            return bestH
        }

        // 1ª fase su tutte le colonne; poi si scartano quelle che anche nella posizione migliore restano
        // lontane (rocce, persone, la croce di vetta, un tratto di cresta vicino spostato dall'errore GPS)
        // e si ripete il confronto. Così un ostacolo non rovina l'allineamento, ma una cima piccola e
        // ben riconoscibile continua a contare.
        var bestH = search()
        val pitch = DoubleArray(1)
        cost(bestH, pitch)
        var kept = 0
        for (i in 0 until m) { use[i] = abs(diffs[i] - pitch[0]) < 0.4; if (use[i]) kept++ }
        if (kept < m / 2) {
            lastDiagnostics = "colonne=$m coerenti=$kept (poche)"
            return null
        }
        bestH = search()
        val bestCost = cost(bestH, pitch)

        // Sicurezza: errore basso, minimo netto (nessun'altra direzione combacia quasi altrettanto bene)
        // e profilo abbastanza movimentato da fissare la direzione.
        var second = Double.MAX_VALUE
        for (i in 0..steps) {
            val hOff = center - searchDeg + i * coarseStep
            if (abs(hOff - bestH) > 2.0) second = min(second, costs[i])
        }
        var mean = 0.0; var sq = 0.0
        for (i in 0 until m) if (use[i]) { val sv = sky(view, az[i] + bestH); mean += sv; sq += sv * sv }
        mean /= kept
        val relief = sqrt(max(0.0, sq / kept - mean * mean))

        lastDiagnostics = "colonne=$m coerenti=$kept direzione=${"%.2f".format(bestH)} errore=${"%.3f".format(bestCost)} " +
            "secondo=${"%.3f".format(second)} rilievo=${"%.2f".format(relief)} inclinazione=${"%.2f".format(pitch[0])}"
        if (bestCost > 0.15) return null
        // Il minimo deve essere netto: nessun'altra direzione (oltre 2°) combacia quasi altrettanto bene.
        if (second < bestCost * 2.5 || second - bestCost < 0.05) return null
        if (relief < 0.2) return null
        if (abs(pitch[0]) > 10) return null
        return Alignment(Geo.angleDiff(bestH, 0.0), pitch[0], bestCost, kept)
    }

    private fun skyDist(view: ViewResult, azDeg: Double): Double {
        val i = (Geo.normalizeDeg(azDeg) / view.skylineStepDeg).toInt() % view.skylineDistM.size
        return view.skylineDistM[i].toDouble()
    }

    /** Elevazione dell'orizzonte calcolato all'azimut [azDeg], interpolata. */
    fun sky(view: ViewResult, azDeg: Double): Double {
        val n = view.skyline.size
        val f = Geo.normalizeDeg(azDeg) / view.skylineStepDeg
        val i = f.toInt() % n
        val t = f - f.toInt()
        return view.skyline[i] * (1 - t) + view.skyline[(i + 1) % n] * t
    }
}
