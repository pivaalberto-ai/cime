package it.cime.app

import it.cime.app.core.PackInfo
import it.cime.app.core.RemotePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Elenco e download dei pacchetti pubblicati tra le Release del repository GitHub. */
class PackCatalog(private val repo: String = BuildConfig.PACK_REPO) {

    /** Pacchetti disponibili (Release con tag "pack-…" e un file .pkpack). */
    suspend fun fetch(): List<RemotePack> = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases?per_page=100")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> {}
                404 -> throw IOException("Repository non trovato o privato ($repo)")
                403, 429 -> throw IOException("GitHub ha limitato le richieste, riprova tra qualche minuto")
                else -> throw IOException("Risposta GitHub $code")
            }
            val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<RemotePack>()
            for (i in 0 until json.length()) {
                val rel = json.getJSONObject(i)
                if (!rel.optString("tag_name").startsWith("pack-")) continue
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    if (!name.endsWith(".pkpack")) continue
                    out += RemotePack(
                        title = PackInfo.titleFrom(rel.optString("name"), name),
                        fileName = name,
                        url = a.getString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                        updatedAt = a.optString("updated_at"),
                        bbox = PackInfo.parseBbox(rel.optString("body")),
                    )
                }
            }
            out.sortedBy { it.title.lowercase() }
        } finally {
            conn.disconnect()
        }
    }

    /** Scarica [pack] in [dest]; [onProgress] riceve byte scaricati e totali. Annullabile. */
    suspend fun download(pack: RemotePack, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open(pack.url)
        try {
            if (conn.responseCode != 200) throw IOException("Download non riuscito (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.sizeBytes
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && done != total) throw IOException("Download incompleto")
        } catch (e: Exception) {
            dest.delete(); throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Cime-app")
        }
}
