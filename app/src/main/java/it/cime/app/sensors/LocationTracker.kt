package it.cime.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** Posizione da GPS (e rete come ripiego), senza dipendere dai Google Play Services. */
class LocationTracker(context: Context, private val onLocation: (Location) -> Unit) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var last: Location? = null
        private set

    @SuppressLint("MissingPermission") // Il chiamante verifica il permesso prima di start().
    fun start() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(p)) continue
            lm.getLastKnownLocation(p)?.let { accept(it) }
            lm.requestLocationUpdates(p, 2_000L, 5f, this, Looper.getMainLooper())
        }
    }

    fun stop() = lm.removeUpdates(this)

    override fun onLocationChanged(location: Location) = accept(location)

    private fun accept(l: Location) {
        val prev = last
        // Ignora una posizione di rete grossolana se ne abbiamo già una GPS recente e più precisa.
        if (prev != null && l.accuracy > prev.accuracy * 3 && l.time - prev.time < 60_000) return
        last = l
        onLocation(l)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
