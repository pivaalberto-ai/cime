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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import it.cime.app.core.Calibration
import it.cime.app.core.Geo
import it.cime.app.core.Projector
import it.cime.app.core.ViewResult
import it.cime.app.core.Viewshed
import it.cime.app.sensors.LocationTracker
import it.cime.app.sensors.OrientationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PeakOverlayView
    private lateinit var status: TextView
    private lateinit var doneButton: Button

    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private lateinit var packs: PackManager

    private var cameraInfo: CameraInfo? = null
    private var computeJob: Job? = null
    private var lastComputedAt: Location? = null
    private var manualLocation: Location? = null
    private var result: ViewResult? = null
    private var statusMessage = ""

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
                lastComputedAt = null
                currentLocation()?.let { recompute(it) }
            } catch (e: Exception) {
                toast("Pacchetto non valido: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        doneButton = findViewById(R.id.done)
        packs = PackManager(this)

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

        if (!orientation.available) setStatus("Questo telefono non ha il sensore di orientamento necessario")

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.all { hasPermission(it) }) {
            startCamera(); location.start()
        } else {
            permissionLauncher.launch(needed)
        }
        if (packs.list().isEmpty()) setStatus("Importa un pacchetto regione dal menu ⋮")
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
        super.onDestroy()
    }

    // --- Fotocamera ---

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
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
        overlay.focalPx = if (focal != null && size != null && size.width > 0 && size.height > 0) {
            Projector.focalPxPortrait(
                focal.toDouble(), maxOf(size.width, size.height).toDouble(), minOf(size.width, size.height).toDouble(),
                overlay.width.toFloat(), overlay.height.toFloat(),
            )
        } else {
            // Ripiego: ~65° di campo orizzontale, tipico di una fotocamera principale.
            overlay.width / 2.0 / Math.tan(Math.toRadians(65.0 / 2))
        }
        overlay.invalidate()
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
            statusMessage = if (r == null) "Nessun pacchetto copre questa posizione" else ""
            refreshStatus()
        }
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
            menu.add(0, 1, 0, "Importa pacchetto regione")
            menu.add(0, 2, 1, "Calibra (trascina / pizzica)")
            menu.add(0, 3, 2, "Azzera calibrazione")
            menu.add(0, 4, 3, if (overlay.showHidden) "Nascondi cime coperte" else "Mostra cime coperte")
            menu.add(0, 5, 4, if (manualLocation == null) "Posizione manuale…" else "Torna al GPS")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> importLauncher.launch(arrayOf("*/*"))
                    2 -> setCalibrating(true)
                    3 -> { overlay.calibration = Calibration(); saveCalibration(overlay.calibration) }
                    4 -> overlay.showHidden = !overlay.showHidden
                    5 -> if (manualLocation == null) askManualLocation() else {
                        manualLocation = null; lastComputedAt = null
                        location.last?.let { l -> onLocation(l) }
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
