package app.pocketpilot.capability.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.pocketpilot.capability.api.SensorReader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Listens to the sensor until its first value, then stops listening. Gives up after [READ_TIMEOUT_MS]. */
class AndroidSensorReader(
    context: Context,
) : SensorReader {
    private val sensors = context.getSystemService(SensorManager::class.java)

    override suspend fun read(sensor: String): Double? {
        val type = TYPES[sensor] ?: return null
        val device = sensors.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            sensors.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(event.values[0].toDouble())
                        }

                        override fun onAccuracyChanged(
                            sensor: Sensor?,
                            accuracy: Int,
                        ) = Unit
                    }
                sensors.registerListener(listener, device, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L

        val TYPES =
            mapOf(
                "light" to Sensor.TYPE_LIGHT,
                "pressure" to Sensor.TYPE_PRESSURE,
                "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
                "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            )
    }
}
