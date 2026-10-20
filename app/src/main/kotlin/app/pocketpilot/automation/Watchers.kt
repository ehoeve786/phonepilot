package app.pocketpilot.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.pocketpilot.capability.api.shell.SystemReader
import app.pocketpilot.core.automation.Place
import app.pocketpilot.core.automation.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * "Arrive at or leave a place" through the platform's proximity alerts, which need neither Google
 * services nor PocketPilot running all the time: Android sends [PlaceReceiver] a broadcast on entering or
 * leaving each circle. The alerts set are remembered so ones no rule needs any more are removed.
 */
class PlaceWatcher(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val permitted: Boolean get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Sets an alert for every place in [places] and removes the rest. */
    @Synchronized
    fun watch(places: Collection<Place>) {
        val locations = context.getSystemService(LocationManager::class.java)
        val wanted = places.associateBy { it.key }
        val old = prefs.getStringSet(KEY, emptySet()).orEmpty()
        (old - wanted.keys).forEach {
            try {
                locations.removeProximityAlert(alert(it))
            } catch (e: SecurityException) {
                // The permission was taken back; Android dropped the alert with it.
            }
        }
        val set =
            if (!permitted) {
                emptySet()
            } else {
                wanted
                    .filter { (key, place) ->
                        try {
                            locations.addProximityAlert(
                                place.latitude,
                                place.longitude,
                                place.radiusMeters.toFloat(),
                                NO_EXPIRY,
                                alert(key),
                            )
                            true
                        } catch (e: SecurityException) {
                            false
                        }
                    }.keys
            }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    /** "latitude,longitude" of the newest location any provider has, or null. */
    fun lastLocation(): String? {
        if (!permitted && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val locations = context.getSystemService(LocationManager::class.java)
        val newest =
            locations
                .getProviders(true)
                .mapNotNull { provider ->
                    try {
                        locations.getLastKnownLocation(provider)
                    } catch (e: SecurityException) {
                        null
                    }
                }.maxByOrNull { it.time } ?: return null
        return String.format(Locale.ROOT, "%.6f,%.6f", newest.latitude, newest.longitude)
    }

    private fun alert(key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            Intent(context, PlaceReceiver::class.java)
                .setData(Uri.parse("pocketpilot-place:$key"))
                .putExtra(PlaceReceiver.EXTRA_PLACE, key),
            // The system adds whether the phone entered or left.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS = "place_alerts"
        const val KEY = "places"
        const val NO_EXPIRY = -1L
    }
}

/**
 * Shake, face down or up, and the proximity sensor. Each sensor is listened to only while an enabled
 * rule uses it, since the accelerometer costs battery.
 */
class SensorWatcher(
    context: Context,
    private val emit: (TriggerEvent) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var listening = emptySet<Int>()
    private var thresholds = emptyList<SensorThreshold>()
    private val side = HashMap<SensorThreshold, Boolean>()
    private var lastShake = 0L
    private var faceDown: Boolean? = null
    private var flipSince = 0L
    private var flipCandidate: Boolean? = null
    private var near: Boolean? = null
    private var watchShake = false
    private var watchFlip = false

    @Synchronized
    fun watch(
        shake: Boolean,
        flip: Boolean,
        proximity: Boolean,
        thresholds: List<SensorThreshold> = emptyList(),
    ) {
        watchShake = shake
        watchFlip = flip
        this.thresholds = thresholds
        side.keys.retainAll(thresholds.toSet())
        val wanted =
            buildSet {
                if (shake || flip) add(Sensor.TYPE_ACCELEROMETER)
                if (proximity) add(Sensor.TYPE_PROXIMITY)
                thresholds.forEach { add(it.sensorType) }
            }
        if (wanted == listening) return
        sensors.unregisterListener(this)
        faceDown = null
        near = null
        wanted.forEach { type ->
            sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        listening = wanted
    }

    fun stop() = watch(shake = false, flip = false, proximity = false)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> motion(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_PROXIMITY -> proximity(event.values[0] < event.sensor.maximumRange.coerceAtMost(NEAR_CM))
            else -> reading(event.sensor.type, event.values[0])
        }
    }

    /** Reports a reading crossing a threshold; the first reading only sets the side it is on. */
    private fun reading(
        type: Int,
        value: Float,
    ) {
        for (threshold in thresholds) {
            if (threshold.sensorType != type) continue
            val above = value > threshold.value
            val previous = side.put(threshold, above)
            if (previous != null && previous != above) {
                emit(
                    TriggerEvent(
                        "sensor.reading",
                        mapOf(
                            "sensor" to threshold.name,
                            "direction" to if (above) "above" else "below",
                            "threshold" to threshold.text,
                            "reading" to "%.1f".format(value),
                        ),
                    ),
                )
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int,
    ) = Unit

    private fun motion(
        x: Float,
        y: Float,
        z: Float,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (watchShake) {
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            val strength =
                when {
                    g >= HARD_G -> "hard"
                    g >= MEDIUM_G -> "medium"
                    g >= LIGHT_G -> "light"
                    else -> null
                }
            if (strength != null && now - lastShake > SHAKE_GAP_MS) {
                lastShake = now
                emit(TriggerEvent("sensor.shake", mapOf("strength" to strength)))
            }
        }
        if (watchFlip) {
            // Flat and still for a moment, screen down or up; anything in between keeps the last position.
            val position =
                when {
                    z < -FLAT && now - lastShake > SHAKE_GAP_MS -> true
                    z > FLAT -> false
                    else -> null
                }
            if (position == null || position != flipCandidate) {
                flipCandidate = position
                flipSince = now
                return
            }
            if (now - flipSince < FLIP_HOLD_MS || position == faceDown) return
            val first = faceDown == null
            faceDown = position
            // The position when listening starts is not a change.
            if (!first) emit(TriggerEvent("sensor.flip", mapOf("position" to if (position) "down" else "up")))
        }
    }

    private fun proximity(isNear: Boolean) {
        val first = near == null
        if (isNear == near) return
        near = isNear
        if (!first) emit(TriggerEvent("sensor.proximity", mapOf("state" to if (isNear) "near" else "far")))
    }

    private companion object {
        const val LIGHT_G = 1.8f
        const val MEDIUM_G = 2.5f
        const val HARD_G = 3.3f
        const val SHAKE_GAP_MS = 1_500L
        const val FLAT = 8.5f
        const val FLIP_HOLD_MS = 800L
        const val NEAR_CM = 5f
    }
}

/** A "Sensor reading" trigger's threshold: [name] is the sensor, reported when the reading goes [above] [value]. */
data class SensorThreshold(
    val sensorType: Int,
    val name: String,
    val above: Boolean,
    val value: Float,
    val text: String,
)

private val SENSOR_TYPES =
    mapOf(
        "light" to Sensor.TYPE_LIGHT,
        "pressure" to Sensor.TYPE_PRESSURE,
        "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
    )

/** The threshold a rule's "Sensor reading" trigger sets, or null when it is incomplete or names no known sensor. */
fun sensorThresholdOf(params: Map<String, String>): SensorThreshold? {
    val name = params["sensor"]?.trim().orEmpty()
    val type = SENSOR_TYPES[name] ?: return null
    val text = params["value"]?.trim().orEmpty()
    val value = text.toFloatOrNull() ?: return null
    return SensorThreshold(type, name, above = params["direction"]?.trim() != "below", value = value, text = text)
}

/**
 * "Log line" and "App crashed": reads new system log lines through Shizuku's read-only path every few
 * seconds while a rule uses them. Logs of apps on the deny list are dropped.
 */
class LogWatcher(
    private val reader: SystemReader,
    private val scope: CoroutineScope,
    private val hidden: (String) -> Boolean,
    private val emit: (TriggerEvent) -> Unit,
) {
    private var job: Job? = null
    private var texts: Set<String> = emptySet()
    private var crashes = false

    /** Watches for lines containing any of [lineTexts], and for crashes when [crashes]. */
    @Synchronized
    fun watch(
        lineTexts: Set<String>,
        crashes: Boolean,
    ) {
        texts = lineTexts
        this.crashes = crashes
        val on = lineTexts.isNotEmpty() || crashes
        if (!on) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job =
            scope.launch {
                var since = System.currentTimeMillis()
                // Lines logged in the same millisecond as the newest one seen; only a repeat of one of them is skipped.
                val seenAtSince = HashSet<String>()
                while (isActive) {
                    delay(POLL_MS)
                    if (!reader.available.value) continue
                    val result =
                        runCatching {
                            val start = String.format(Locale.ROOT, "%.3f", since / 1000.0)
                            reader.read(listOf("logcat", "-d", "-v", "epoch", "-T", start, "-b", "main,system,crash"), filter(), TIMEOUT_MS)
                        }.getOrNull() ?: continue
                    result.stdout.lineSequence().forEach { line ->
                        val parsed = LINE.find(line) ?: return@forEach
                        val at = (parsed.groupValues[1].toDoubleOrNull()?.times(1000))?.toLong() ?: return@forEach
                        if (at < since) return@forEach
                        if (at > since) {
                            since = at
                            seenAtSince.clear()
                        }
                        if (!seenAtSince.add(line)) return@forEach
                        handle(parsed.groupValues[2].trim(), parsed.groupValues[3])
                    }
                }
            }
    }

    fun stop() = watch(emptySet(), crashes = false)

    /** With one text and no crash watching, the service keeps only matching lines. */
    private fun filter(): String = texts.singleOrNull()?.takeIf { !crashes }.orEmpty()

    private fun handle(
        tag: String,
        message: String,
    ) {
        if (crashes) {
            val pkg = CRASH.find(message)?.groupValues?.get(1) ?: ANR.find(message)?.groupValues?.get(1)
            if (pkg != null && !hidden(pkg)) {
                emit(TriggerEvent("app.crash", mapOf("package" to pkg, "error" to message.take(MAX_LINE))))
            }
        }
        if (texts.any { message.contains(it, ignoreCase = true) || tag.contains(it, ignoreCase = true) }) {
            if (hidden(tag) || PACKAGE.findAll(message).any { hidden(it.value) }) return
            emit(TriggerEvent("log.line", mapOf("line" to message.take(MAX_LINE), "tag" to tag)))
        }
    }

    private companion object {
        const val POLL_MS = 10_000L
        const val TIMEOUT_MS = 8_000L
        const val MAX_LINE = 1_000

        /** "1760212345.123  1234  5678 E Tag: message", from `logcat -v epoch`. */
        val LINE = Regex("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]*?)\\s*:\\s?(.*)$")
        val CRASH = Regex("^Process: ([A-Za-z0-9_.]+), PID")
        val ANR = Regex("^ANR in ([A-Za-z0-9_.]+)")
        val PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
    }
}
