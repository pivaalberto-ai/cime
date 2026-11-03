package it.cime.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import it.cime.app.core.RegionPack
import java.io.File

/** Gestisce i pacchetti regione salvati nella memoria privata dell'app. */
class PackManager(private val context: Context) {
    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val opened = HashMap<String, RegionPack>()

    fun list(): List<File> = dir.listFiles { f -> f.extension == "pkpack" }?.sortedBy { it.name } ?: emptyList()

    /** Primo pacchetto che copre la posizione, o null. */
    @Synchronized
    fun packFor(lat: Double, lon: Double): RegionPack? {
        for (f in list()) {
            val p = opened.getOrPut(f.name) { RegionPack.open(f) }
            if (p.contains(lat, lon)) return p
        }
        return null
    }

    /** Copia un file scelto dall'utente, ne verifica il formato e restituisce il pacchetto. */
    @Synchronized
    fun importPack(uri: Uri): RegionPack {
        val name = displayName(uri)?.let { if (it.endsWith(".pkpack")) it else "$it.pkpack" }
            ?: "regione_${System.currentTimeMillis()}.pkpack"
        val tmp = File(dir, "$name.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossibile leggere il file" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        val pack = try {
            RegionPack.open(tmp)
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        val dest = File(dir, name)
        opened.remove(name)
        dest.delete()
        check(tmp.renameTo(dest)) { "Impossibile salvare il pacchetto" }
        return RegionPack.open(dest).also { opened[name] = it }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
