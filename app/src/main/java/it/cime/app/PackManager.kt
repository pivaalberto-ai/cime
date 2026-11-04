package it.cime.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import it.cime.app.core.RegionPack
import java.io.File

/** Gestisce i pacchetti regione salvati nella memoria privata dell'app. */
class PackManager(private val context: Context) {
    val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val opened = HashMap<String, RegionPack>()

    fun list(): List<File> = dir.listFiles { f -> f.extension == "pkpack" }?.sortedBy { it.name } ?: emptyList()

    fun file(name: String) = File(dir, name)

    /** Primo pacchetto che copre la posizione, o null. */
    @Synchronized
    fun packFor(lat: Double, lon: Double): RegionPack? {
        for (f in list()) {
            val p = try {
                opened.getOrPut(f.name) { RegionPack.open(f) }
            } catch (_: Exception) {
                continue   // file rovinato: lo si ignora
            }
            if (p.contains(lat, lon)) return p
        }
        return null
    }

    /** Copia un file scelto dall'utente, ne verifica il formato e restituisce il pacchetto. */
    fun importPack(uri: Uri): RegionPack {
        val name = displayName(uri)?.let { if (it.endsWith(".pkpack")) it else "$it.pkpack" }
            ?: "regione_${System.currentTimeMillis()}.pkpack"
        val tmp = tempFile(name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossibile leggere il file" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        return install(tmp, name)
    }

    /** File temporaneo nella stessa cartella, così l'installazione è un semplice rinomina. */
    fun tempFile(name: String) = File(dir, "$name.tmp")

    /** Verifica [tmp] e lo mette al posto del pacchetto [name] (sostituendo l'eventuale versione precedente). */
    @Synchronized
    fun install(tmp: File, name: String): RegionPack {
        try {
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

    @Synchronized
    fun delete(name: String) {
        opened.remove(name)
        File(dir, name).delete()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
