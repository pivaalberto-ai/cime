package it.cime.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import it.cime.app.core.Alignment
import it.cime.app.core.Calibration
import it.cime.app.core.Geo
import it.cime.app.core.PackInfo
import it.cime.app.core.Peak
import it.cime.app.core.PeakSearch
import it.cime.app.core.Projector
import it.cime.app.core.RemotePack
import it.cime.app.core.SearchHit
import it.cime.app.core.ViewResult
import it.cime.app.core.Viewshed
import it.cime.app.sensors.LocationTracker
import it.cime.app.sensors.OrientationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PeakOverlayView
    private lateinit var status: TextView
    private lateinit var doneButton: Button
    private lateinit var targetChip: TextView

    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private lateinit var packs: PackManager
    private val catalog = PackCatalog()

    private var cameraInfo: CameraInfo? = null
    private var computeJob: Job? = null
    private var lastComputedAt: Location? = null
    private var manualLocation: Location? = null
    @Volatile private var result: ViewResult? = null
    private var statusMessage = ""

    // Allineamento automatico
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var optics: Triple<Double, Double, Double>? = null
    @Volatile private var autoAlign = true
    private var pendingAlign: Alignment? = null

    // Ricerca
    private var targetPeak: Peak? = null
    private val searchIndex = HashMap<String, PeakSearch>()

    private val prefs by lazy { getSharedPreferences("cime", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            location.start()
        } else {
            setStatus("Senza posizione puoi usare \"Posizione manuale\" dal menu")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) { packs.importPack(uri) }
                toast("Importato \"${pack.name}\": ${pack.peaks.size} cime")
                packsChanged()
            } catch (e: Exception) {
                toast("Pacchetto non valido: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disegna a tutto schermo su ogni versione di Android (da Android 15 è comunque obbligatorio).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        doneButton = findViewById(R.id.done)
        targetChip = findViewById(R.id.targetChip)
        packs = PackManager(this)
        applySystemBarInsets()

        autoAlign = prefs.getBoolean("autoAlign", true)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlay.calibration = loadCalibration()
        overlay.onCalibrationChanged = { saveCalibration(it) }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFocal() }

        orientation = OrientationTracker(this) {
            overlay.rotation9 = orientation.rotation
            overlay.postInvalidateOnAnimation()
        }
        location = LocationTracker(this) { if (manualLocation == null) onLocation(it) }

        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }
        doneButton.setOnClickListener { setCalibrating(false) }
        targetChip.setOnClickListener { setTarget(null) }

        if (!orientation.available) setStatus("Questo telefono non ha il sensore di orientamento necessario")

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.all { hasPermission(it) }) {
            startCamera(); location.start()
        } else {
            permissionLauncher.launch(needed)
        }
        if (packs.list().isEmpty()) {
            setStatus("Nessun pacchetto: ⋮ → Pacchetti regione")
            showPacks()
        }
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
    }

    override fun onPause() {
        orientation.stop()
        super.onPause()
    }

    override fun onDestroy() {
        location.stop()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Tiene barra superiore, etichette, bussola e pulsanti fuori dalle barre di sistema e dal notch,
     * altrimenti il menu finisce sotto l'orologio e non si riesce a toccare.
     */
    private fun applySystemBarInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding((12 * dp).toInt() + bars.left, bars.top, bars.right, 0)
            (doneButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (72 * dp).toInt() + bars.bottom
            doneButton.requestLayout()
            overlay.insetBottom = bars.bottom.toFloat()
            topBar.post {
                overlay.insetTop = topBar.height.toFloat()
                (targetChip.layoutParams as ViewGroup.MarginLayoutParams).topMargin = topBar.height + (8 * dp).toInt()
                targetChip.requestLayout()
            }
            insets
        }
    }

    // --- Fotocamera ---

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Fotogrammi piccoli per l'allineamento automatico: stesso campo visivo (4:3) della preview.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio43)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, HorizonAligner(
                rotation = { orientation.snapshot(it) },
                view = { result },
                declination = { overlay.declinationDeg },
                calibration = { overlay.calibration },
                optics = { optics },
                enabled = { autoAlign && !overlay.calibrating },
                onResult = { onAlignment(it) },
            ))
            provider.unbindAll()
            val camera = try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // Alcuni telefoni non reggono due flussi: si rinuncia all'allineamento automatico.
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }
            cameraInfo = camera.cameraInfo
            updateFocal()
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun updateFocal() {
        val info = cameraInfo ?: return
        if (overlay.width == 0) return
        val c2 = Camera2CameraInfo.from(info)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal != null && size != null && size.width > 0 && size.height > 0) {
            val o = Triple(focal.toDouble(), maxOf(size.width, size.height).toDouble(), minOf(size.width, size.height).toDouble())
            optics = o
            overlay.focalPx = Projector.focalPxPortrait(o.first, o.second, o.third, overlay.width.toFloat(), overlay.height.toFloat())
        } else {
            // Ripiego: ~65° di campo orizzontale, tipico di una fotocamera principale.
            val f = overlay.width / 2.0 / Math.tan(Math.toRadians(65.0 / 2))
            overlay.focalPx = f
            // Ottica equivalente: sensore 4:3 di 4,8 mm di lato corto.
            optics = Triple(f * 4.8 / overlay.width, 6.4, 4.8)
        }
        overlay.invalidate()
    }

    // --- Allineamento automatico ---

    /**
     * Applica una correzione trovata dall'analisi dell'immagine. Piccoli aggiustamenti vengono
     * smussati; un salto grande va confermato da due analisi concordi, per non seguire un errore isolato.
     */
    private fun onAlignment(a: Alignment) {
        if (!autoAlign || overlay.calibrating) return
        val cur = overlay.calibration
        val dh = Geo.angleDiff(a.headingOffsetDeg, cur.headingOffsetDeg)
        val dp = a.pitchOffsetDeg - cur.pitchOffsetDeg
        val next = if (abs(dh) < 1.5 && abs(dp) < 1.5) {
            pendingAlign = null
            cur.copy(
                headingOffsetDeg = Geo.angleDiff(cur.headingOffsetDeg + dh * 0.5, 0.0),
                pitchOffsetDeg = cur.pitchOffsetDeg + dp * 0.5,
            )
        } else {
            val p = pendingAlign
            pendingAlign = a
            if (p == null || abs(Geo.angleDiff(a.headingOffsetDeg, p.headingOffsetDeg)) > 1.0 ||
                abs(a.pitchOffsetDeg - p.pitchOffsetDeg) > 1.0) return
            pendingAlign = null
            cur.copy(headingOffsetDeg = a.headingOffsetDeg, pitchOffsetDeg = a.pitchOffsetDeg)
        }
        overlay.calibration = next
        overlay.autoAlignedAt = SystemClock.elapsedRealtime()
        saveCalibration(next)
    }

    // --- Posizione e calcolo ---

    private fun currentLocation(): Location? = manualLocation ?: location.last

    private fun onLocation(l: Location) {
        overlay.declinationDeg = GeomagneticField(
            l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
        val prev = lastComputedAt
        if (prev == null || prev.distanceTo(l) > 50f) recompute(l)
        refreshStatus()
    }

    private fun recompute(l: Location) {
        lastComputedAt = l
        computeJob?.cancel()
        computeJob = lifecycleScope.launch {
            setStatus("Calcolo delle cime visibili…")
            val r = withContext(Dispatchers.Default) {
                val pack = packs.packFor(l.latitude, l.longitude) ?: return@withContext null
                Viewshed(pack).compute(l.latitude, l.longitude, pack.peaks)
            }
            result = r
            overlay.view = r
            updateTarget()
            statusMessage = when {
                r != null -> ""
                packs.list().isEmpty() -> "Nessun pacchetto: ⋮ → Pacchetti regione"
                else -> "Nessun pacchetto copre questa posizione: ⋮ → Pacchetti regione"
            }
            refreshStatus()
        }
    }

    /** Da chiamare dopo aver aggiunto o tolto un pacchetto. */
    private fun packsChanged() {
        searchIndex.clear()
        lastComputedAt = null
        currentLocation()?.let { recompute(it) } ?: refreshStatus()
    }

    private fun refreshStatus() {
        if (statusMessage.isNotEmpty()) { status.text = statusMessage; return }
        val r = result ?: return
        val l = currentLocation()
        val visible = r.peaks.count { it.visible }
        val parts = mutableListOf("$visible cime visibili", "${r.eyeEle.toInt()} m")
        if (manualLocation != null) parts += "posizione manuale"
        else if (l != null) parts += "GPS ±${l.accuracy.toInt()} m"
        if (orientation.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) parts += "⚠ bussola imprecisa: muovi il telefono a ∞"
        status.text = parts.joinToString(" · ")
    }

    private fun setStatus(s: String) {
        statusMessage = s
        status.text = s
    }

    // --- Menu ---

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 6, 0, "Cerca una cima…")
            menu.add(0, 1, 1, "Pacchetti regione…")
            menu.add(0, 7, 2, "Allineamento automatico").apply { isCheckable = true; isChecked = autoAlign }
            menu.add(0, 2, 3, "Calibra a mano (trascina / pizzica)")
            menu.add(0, 3, 4, "Azzera calibrazione")
            menu.add(0, 4, 5, if (overlay.showHidden) "Nascondi cime coperte" else "Mostra cime coperte")
            menu.add(0, 5, 6, if (manualLocation == null) "Posizione manuale…" else "Torna al GPS")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showPacks()
                    2 -> setCalibrating(true)
                    3 -> { overlay.calibration = Calibration(); saveCalibration(overlay.calibration) }
                    4 -> overlay.showHidden = !overlay.showHidden
                    5 -> if (manualLocation == null) askManualLocation() else {
                        manualLocation = null; lastComputedAt = null
                        location.last?.let { l -> onLocation(l) }
                    }
                    6 -> showSearch()
                    7 -> {
                        autoAlign = !autoAlign
                        prefs.edit().putBoolean("autoAlign", autoAlign).apply()
                        toast(if (autoAlign) "Allineamento automatico attivo: inquadra le montagne e tieni fermo il telefono"
                              else "Allineamento automatico disattivato")
                    }
                }
                true
            }
        }.show()
    }

    private fun setCalibrating(on: Boolean) {
        overlay.calibrating = on
        doneButton.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast("Inquadra una cima nota e trascina finché l'etichetta combacia")
    }

    @SuppressLint("SetTextI18n")
    private fun askManualLocation() {
        val input = EditText(this).apply {
            hint = "lat, lon  (es. 45.9763, 7.6586)"
            currentLocation()?.let { setText(String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude)) }
        }
        AlertDialog.Builder(this)
            .setTitle("Posizione manuale")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val nums = input.text.split(",", " ", ";").mapNotNull { it.trim().toDoubleOrNull() }
                if (nums.size == 2 && nums[0] in -90.0..90.0 && nums[1] in -180.0..180.0) {
                    val l = Location("manual").apply { latitude = nums[0]; longitude = nums[1] }
                    manualLocation = l
                    lastComputedAt = null
                    onLocation(l)
                } else toast("Formato non valido")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // --- Ricerca di una cima ---

    private fun showSearch() {
        val l = currentLocation() ?: return toast("Serve la posizione (GPS o posizione manuale)")
        lifecycleScope.launch {
            val search = withContext(Dispatchers.Default) {
                val pack = packs.packFor(l.latitude, l.longitude) ?: return@withContext null
                synchronized(searchIndex) { searchIndex.getOrPut(pack.file.name) { PeakSearch(pack.peaks) } }
            } ?: return@launch toast("Nessun pacchetto copre questa posizione")
            val visibility = result?.peaks?.associate { it.peak.id to it.visible } ?: emptyMap()

            val dp = resources.displayMetrics.density
            val input = EditText(this@MainActivity).apply { hint = "Nome della cima"; isSingleLine = true }
            val list = ListView(this@MainActivity)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
                addView(input)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * dp).toInt()))
            }
            var hits: List<SearchHit> = emptyList()
            val adapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1)
            list.adapter = adapter
            fun refresh() {
                hits = search.search(input.text.toString(), l.latitude, l.longitude)
                adapter.clear()
                adapter.addAll(hits.map { h ->
                    val km = h.distanceM / 1000
                    val dist = if (km < 10) String.format(Locale.ITALY, "%.1f km", km) else "${km.roundToInt()} km"
                    val vis = when (visibility[h.peak.id]) { true -> " · visibile"; false -> " · nascosta"; null -> "" }
                    "${h.peak.name}\n${h.peak.ele.roundToInt()} m · $dist$vis"
                })
            }
            refresh()
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Cerca una cima")
                .setView(box)
                .setNegativeButton("Chiudi", null)
                .create()
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            list.setOnItemClickListener { _, _, pos, _ ->
                setTarget(hits[pos].peak)
                dialog.dismiss()
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dialog.show()
            input.requestFocus()
        }
    }

    private fun setTarget(p: Peak?) {
        targetPeak = p
        updateTarget()
        if (p != null) toast("Segui la freccia gialla. Tocca il nome in alto per smettere.")
    }

    /** Ricalcola direzione e distanza della cima cercata dalla posizione attuale. */
    private fun updateTarget() {
        val p = targetPeak
        val l = currentLocation()
        if (p == null || l == null) {
            overlay.target = null
            targetChip.visibility = View.GONE
            return
        }
        val r = result
        val d = Geo.distance(l.latitude, l.longitude, p.lat, p.lon)
        val eye = r?.eyeEle ?: l.altitude
        val el = Math.toDegrees(Geo.elevationAngle(eye, p.ele, d))
        val vis = r?.peaks?.firstOrNull { it.peak.id == p.id }?.visible
        overlay.target = Target(p, Geo.bearing(l.latitude, l.longitude, p.lat, p.lon), el, d, vis)
        targetChip.text = "◎ ${p.name}  ✕"
        targetChip.visibility = View.VISIBLE
    }

    // --- Pacchetti regione ---

    private fun showPacks() {
        val loading = AlertDialog.Builder(this)
            .setTitle("Pacchetti regione")
            .setMessage("Carico l'elenco da GitHub…")
            .setNegativeButton("Annulla", null)
            .show()
        lifecycleScope.launch {
            val remote = try {
                catalog.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Elenco online non disponibile: ${e.message}")
                null
            }
            if (!loading.isShowing) return@launch
            loading.dismiss()
            showPackList(remote ?: emptyList(), online = remote != null)
        }
    }

    private sealed class PackEntry {
        class Remote(val pack: RemotePack, val installed: Boolean, val update: Boolean) : PackEntry()
        class Local(val fileName: String, val size: Long) : PackEntry()
        object Import : PackEntry()
    }

    private fun showPackList(remote: List<RemotePack>, online: Boolean) {
        val l = currentLocation()
        val local = packs.list()
        val localNames = local.map { it.name }.toSet()
        val entries = ArrayList<PackEntry>()
        val labels = ArrayList<String>()
        for (rp in remote) {
            val installed = rp.fileName in localNames
            val update = installed && rp.updatedAt > prefs.getString("packver_${rp.fileName}", "").orEmpty()
            entries += PackEntry.Remote(rp, installed, update)
            val state = when { update -> "aggiornamento disponibile"; installed -> "installato ✓"; else -> "da scaricare" }
            val here = if (l != null && rp.covers(l.latitude, l.longitude)) " · copre la tua posizione" else ""
            labels += "${rp.title}\n${PackInfo.formatSize(rp.sizeBytes)} · $state$here"
        }
        for (f in local) if (remote.none { it.fileName == f.name }) {
            entries += PackEntry.Local(f.name, f.length())
            labels += "${f.nameWithoutExtension}\n${PackInfo.formatSize(f.length())} · importato da file"
        }
        entries += PackEntry.Import
        labels += "Importa da file…"

        AlertDialog.Builder(this)
            .setTitle(if (online) "Pacchetti regione" else "Pacchetti regione (offline)")
            .setItems(labels.toTypedArray()) { _, i ->
                when (val e = entries[i]) {
                    is PackEntry.Remote -> when {
                        !e.installed -> download(e.pack)
                        else -> AlertDialog.Builder(this)
                            .setTitle(e.pack.title)
                            .setItems(arrayOf(if (e.update) "Aggiorna" else "Scarica di nuovo", "Elimina dal telefono")) { _, k ->
                                if (k == 0) download(e.pack) else deletePack(e.pack.fileName)
                            }.show()
                    }
                    is PackEntry.Local -> AlertDialog.Builder(this)
                        .setTitle(e.fileName)
                        .setItems(arrayOf("Elimina dal telefono")) { _, _ -> deletePack(e.fileName) }
                        .show()
                    PackEntry.Import -> importLauncher.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun download(rp: RemotePack) {
        val dp = resources.displayMetrics.density
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val text = TextView(this).apply { text = "Connessione…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(bar); addView(text)
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("Scarico ${rp.title}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Annulla") { _, _ -> job?.cancel() }
            .show()
        job = lifecycleScope.launch {
            val tmp = packs.tempFile(rp.fileName)
            try {
                catalog.download(rp, tmp) { done, total ->
                    runOnUiThread {
                        if (total > 0) bar.progress = (done * 1000 / total).toInt()
                        text.text = "${PackInfo.formatSize(done)} di ${PackInfo.formatSize(total)}"
                    }
                }
                withContext(Dispatchers.Main) { text.text = "Verifica…" }
                val pack = withContext(Dispatchers.IO) { packs.install(tmp, rp.fileName) }
                prefs.edit().putString("packver_${rp.fileName}", rp.updatedAt).apply()
                toast("Installato \"${rp.title}\": ${pack.peaks.size} cime")
                packsChanged()
            } catch (e: CancellationException) {
                tmp.delete()
                toast("Download annullato")
            } catch (e: Exception) {
                tmp.delete()
                toast("Download non riuscito: ${e.message}")
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun deletePack(fileName: String) {
        packs.delete(fileName)
        prefs.edit().remove("packver_$fileName").apply()
        toast("Pacchetto eliminato")
        packsChanged()
    }

    // --- Utilità ---

    private fun loadCalibration() = Calibration(
        Geo.angleDiff(prefs.getFloat("heading", 0f).toDouble(), 0.0),
        prefs.getFloat("pitch", 0f).toDouble(),
        prefs.getFloat("fov", 1f).toDouble(),
    )

    private fun saveCalibration(c: Calibration) {
        prefs.edit().putFloat("heading", c.headingOffsetDeg.toFloat())
            .putFloat("pitch", c.pitchOffsetDeg.toFloat())
            .putFloat("fov", c.fovScale.toFloat()).apply()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
