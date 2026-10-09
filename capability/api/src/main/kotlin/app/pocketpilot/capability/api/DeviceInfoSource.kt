package app.pocketpilot.capability.api

import kotlinx.serialization.Serializable

/** Reads basic facts about the phone for `device.info`. Implemented on Android in capability/device. */
fun interface DeviceInfoSource {
    suspend fun read(): DeviceInfo
}

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val screen: Screen,
    val battery: Battery,
    val network: Network,
) {
    @Serializable
    data class Screen(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
    )

    @Serializable
    data class Battery(
        /** 0 to 100, or null when the system does not report it. */
        val levelPercent: Int?,
        val charging: Boolean,
    )

    @Serializable
    data class Network(
        val connected: Boolean,
        /** wifi, cellular, ethernet, vpn, other or none. */
        val transport: String,
    )
}
