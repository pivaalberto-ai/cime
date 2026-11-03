package it.cime.app.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.floor

data class Peak(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Quota in metri (da OpenStreetMap, o dal DEM se mancante). */
    val ele: Double,
)

/** Modello digitale del terreno interrogabile per coordinate. */
interface Dem {
    /** Quota in metri, oppure NaN se fuori area o dato mancante. */
    fun elevationAt(lat: Double, lon: Double): Double
    /** Lato approssimativo di una cella, in metri. */
    val cellMeters: Double
}

/**
 * Pacchetto regione `.pkpack` (vedi tools/build_pack.py per il formato).
 *
 * Header di 128 byte, little-endian:
 *   0  magic "PKPK"         4  int32 versione (1)
 *   8  f64 latitudine centro prima riga (nord)
 *  16  f64 longitudine centro prima colonna (ovest)
 *  24  f64 passo cella in gradi
 *  32  int32 righe         36 int32 colonne
 *  40  int32 numero cime   44 int32 valore nodata
 *  48  int64 offset DEM    56 int64 offset cime
 *  64  uint16 lunghezza nome regione + UTF-8
 * DEM: righe*colonne int16, da nord a sud, da ovest a est.
 * Cime: f64 lat, f64 lon, f32 quota, int64 id OSM, uint16 lunghezza + nome UTF-8.
 */
class RegionPack private constructor(
    val file: File,
    val name: String,
    private val north: Double,
    private val west: Double,
    val cellDeg: Double,
    val rows: Int,
    val cols: Int,
    private val nodata: Int,
    private val dem: ShortBuffer,
    val peaks: List<Peak>,
) : Dem {

    val south: Double get() = north - (rows - 1) * cellDeg
    val east: Double get() = west + (cols - 1) * cellDeg

    override val cellMeters: Double = cellDeg * Math.PI / 180.0 * Geo.EARTH_RADIUS_M

    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    private fun raw(r: Int, c: Int): Int = dem.get(r * cols + c).toInt()

    /** Interpolazione bilineare. */
    override fun elevationAt(lat: Double, lon: Double): Double {
        val fr = (north - lat) / cellDeg
        val fc = (lon - west) / cellDeg
        if (fr < 0 || fc < 0 || fr > rows - 1 || fc > cols - 1) return Double.NaN
        val r0 = minOf(floor(fr).toInt(), rows - 2).coerceAtLeast(0)
        val c0 = minOf(floor(fc).toInt(), cols - 2).coerceAtLeast(0)
        val tr = fr - r0
        val tc = fc - c0
        val a = raw(r0, c0)
        val b = raw(r0, c0 + 1)
        val c = raw(r0 + 1, c0)
        val d = raw(r0 + 1, c0 + 1)
        if (a == nodata || b == nodata || c == nodata || d == nodata) return Double.NaN
        val top = a + (b - a) * tc
        val bottom = c + (d - c) * tc
        return top + (bottom - top) * tr
    }

    companion object {
        const val MAGIC = "PKPK"
        const val VERSION = 1
        const val HEADER_SIZE = 128

        fun open(file: File): RegionPack {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { map.get(it) }
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "Non è un pacchetto .pkpack" }
                val version = map.getInt(4)
                require(version == VERSION) { "Versione pacchetto non supportata: $version" }
                val north = map.getDouble(8)
                val west = map.getDouble(16)
                val cell = map.getDouble(24)
                val rows = map.getInt(32)
                val cols = map.getInt(36)
                val peakCount = map.getInt(40)
                val nodata = map.getInt(44)
                val demOffset = map.getLong(48)
                val peaksOffset = map.getLong(56)
                val nameLen = map.getShort(64).toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                map.position(66); map.get(nameBytes)
                require(rows >= 2 && cols >= 2 && cell > 0) { "Header DEM non valido" }
                require(demOffset + rows.toLong() * cols * 2 <= ch.size()) { "File troncato" }

                map.position(demOffset.toInt())
                val demBuf = map.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                demBuf.limit(rows * cols)

                map.position(peaksOffset.toInt())
                val peaks = ArrayList<Peak>(peakCount)
                repeat(peakCount) {
                    val lat = map.double
                    val lon = map.double
                    val ele = map.float.toDouble()
                    val id = map.long
                    val len = map.short.toInt() and 0xFFFF
                    val nb = ByteArray(len).also { map.get(it) }
                    peaks += Peak(id, String(nb, Charsets.UTF_8), lat, lon, ele)
                }
                // La mappatura resta valida anche dopo la chiusura del canale.
                return RegionPack(file, String(nameBytes, Charsets.UTF_8), north, west, cell,
                    rows, cols, nodata, demBuf, peaks)
            }
        }

        /** Scrive un pacchetto (usato dai test; lo strumento di produzione è build_pack.py). */
        fun write(
            file: File, name: String, north: Double, west: Double, cellDeg: Double,
            rows: Int, cols: Int, heights: ShortArray, peaks: List<Peak>, nodata: Int = -32768,
        ) {
            require(heights.size == rows * cols)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val demOffset = HEADER_SIZE.toLong()
            val peaksOffset = demOffset + rows.toLong() * cols * 2
            val peakBytes = peaks.sumOf { 8 + 8 + 4 + 8 + 2 + it.name.toByteArray(Charsets.UTF_8).size }
            val buf = ByteBuffer.allocate((peaksOffset + peakBytes).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(MAGIC.toByteArray(Charsets.US_ASCII)).putInt(VERSION)
                .putDouble(north).putDouble(west).putDouble(cellDeg)
                .putInt(rows).putInt(cols).putInt(peaks.size).putInt(nodata)
                .putLong(demOffset).putLong(peaksOffset)
                .putShort(nameBytes.size.toShort()).put(nameBytes)
            buf.position(HEADER_SIZE)
            heights.forEach { buf.putShort(it) }
            peaks.forEach {
                val nb = it.name.toByteArray(Charsets.UTF_8)
                buf.putDouble(it.lat).putDouble(it.lon).putFloat(it.ele.toFloat()).putLong(it.id)
                    .putShort(nb.size.toShort()).put(nb)
            }
            file.writeBytes(buf.array())
        }
    }
}
