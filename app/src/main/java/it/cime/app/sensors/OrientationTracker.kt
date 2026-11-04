package it.cime.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Orientamento del telefono dal sensore "rotation vector" (fusione accelerometro + giroscopio + magnetometro),
 * con un filtro passa-basso sul quaternione per eliminare il tremolio delle etichette.
 */
class OrientationTracker(context: Context, private val onUpdate: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val q = FloatArray(4)          // w, x, y, z filtrato
    private val qNew = FloatArray(4)
    private var hasValue = false

    /** Matrice di rotazione corrente (telefono → mondo magnetico). */
    val rotation = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }

    /** Accuratezza magnetometro: SENSOR_STATUS_ACCURACY_* */
    var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        private set

    val available get() = sensor != null

    /** 0 = nessun filtro, 0.9 = molto smussato ma lento. */
    var smoothing = 0.8f

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(qNew, event.values)
        if (!hasValue) {
            qNew.copyInto(q); hasValue = true
        } else {
            // Stesso emisfero: q e -q sono la stessa rotazione.
            val dot = q[0] * qNew[0] + q[1] * qNew[1] + q[2] * qNew[2] + q[3] * qNew[3]
            val sign = if (dot < 0) -1f else 1f
            val a = 1f - smoothing
            for (i in 0..3) q[i] = q[i] * smoothing + sign * qNew[i] * a
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            for (i in 0..3) q[i] /= n
        }
        val w = if (q[0] < 0) -1f else 1f
        synchronized(rotation) {
            SensorManager.getRotationMatrixFromVector(rotation, floatArrayOf(q[1] * w, q[2] * w, q[3] * w, q[0] * w))
        }
        onUpdate()
    }

    /** Copia coerente della matrice, leggibile da un altro thread (analisi dell'immagine). */
    fun snapshot(out: FloatArray) = synchronized(rotation) { rotation.copyInto(out) }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        this.accuracy = accuracy
    }
}
