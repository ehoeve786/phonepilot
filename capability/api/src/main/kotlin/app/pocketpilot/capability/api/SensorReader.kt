package app.pocketpilot.capability.api

/** Reads one value from a named sensor: light, pressure, temperature or humidity. Null when it is absent or silent. */
fun interface SensorReader {
    suspend fun read(sensor: String): Double?

    companion object {
        val SENSORS = setOf("light", "pressure", "temperature", "humidity")
    }
}
