package app.pocketpilot.automation

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.pocketpilot.core.automation.TriggerEvent

/**
 * The torch and the camera, from the camera service's callbacks: the flashlight turning on or off, and
 * another app starting or stopping the camera. Registered only while a rule uses them. Both callbacks
 * run on the main thread, so the sets below need no locking.
 */
class HardwareWatch(
    private val context: Context,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private var torch: CameraManager.TorchCallback? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private val torchOn = mutableSetOf<String>()
    private val cameraInUse = mutableSetOf<String>()

    @Synchronized
    fun start() {
        if (torch != null) return
        val torchCallback =
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(
                    cameraId: String,
                    enabled: Boolean,
                ) {
                    // One callback per camera with a flash; report only when the first light comes on or the last goes off.
                    val before = torchOn.isNotEmpty()
                    if (enabled) torchOn += cameraId else torchOn -= cameraId
                    if (before != torchOn.isNotEmpty()) emit(state("torch.changed", torchOn.isNotEmpty()))
                }
            }
        cameras.registerTorchCallback(ContextCompat.getMainExecutor(context), torchCallback)
        torch = torchCallback

        // Registering reports the cameras already free, which are not changes.
        availability =
            object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) {
                    val before = cameraInUse.isNotEmpty()
                    cameraInUse += cameraId
                    if (!before) emit(state("camera.inuse", true))
                }

                override fun onCameraAvailable(cameraId: String) {
                    val before = cameraInUse.isNotEmpty()
                    cameraInUse -= cameraId
                    if (before && cameraInUse.isEmpty()) emit(state("camera.inuse", false))
                }
            }.also { cameras.registerAvailabilityCallback(it, Handler(Looper.getMainLooper())) }
    }

    @Synchronized
    fun stop() {
        torch?.let { cameras.unregisterTorchCallback(it) }
        availability?.let { cameras.unregisterAvailabilityCallback(it) }
        torch = null
        availability = null
        torchOn.clear()
        cameraInUse.clear()
    }

    private fun state(
        type: String,
        on: Boolean,
    ) = TriggerEvent(type, mapOf("state" to PhoneState.onOff(on)))
}
