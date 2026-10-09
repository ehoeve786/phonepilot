package app.pocketpilot.capability.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import app.pocketpilot.capability.api.DeviceInfo
import app.pocketpilot.capability.api.DeviceInfoSource

/** Reads device facts from public Android APIs; needs no runtime permission. */
class AndroidDeviceInfoSource(
    private val context: Context,
) : DeviceInfoSource {
    override suspend fun read(): DeviceInfo =
        DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            screen = screen(),
            battery = battery(),
            network = network(),
        )

    private fun screen(): DeviceInfo.Screen {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return DeviceInfo.Screen(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            densityDpi = context.resources.configuration.densityDpi,
        )
    }

    private fun battery(): DeviceInfo.Battery {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        // A sticky broadcast read with a null receiver registers nothing.
        val status =
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceInfo.Battery(levelPercent = level, charging = charging)
    }

    private fun network(): DeviceInfo.Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities =
            manager.activeNetwork?.let(manager::getNetworkCapabilities)
                ?: return DeviceInfo.Network(connected = false, transport = "none")
        val transport =
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return DeviceInfo.Network(connected = connected, transport = transport)
    }
}
