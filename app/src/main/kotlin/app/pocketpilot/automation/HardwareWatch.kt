package app.pocketpilot.automation

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.pocketpilot.core.automation.TriggerEvent

/**
 * The torch and the camera from the camera service's callbacks, and auto-rotate and accessibility services
 * from settings observers. Each is registered only while a rule uses it. The camera and settings callbacks
 * run on the main thread, so the state below needs no locking.
 */
class HardwareWatch(
    private val context: Context,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val resolver = context.contentResolver
    private var torch: CameraManager.TorchCallback? = null
    private var availability: CameraManager.AvailabilityCallback? = null
    private val torchOn = mutableSetOf<String>()
    private val cameraInUse = mutableSetOf<String>()
    private var settings: ContentObserver? = null
    private var rotation: Boolean? = null
    private var accessibility: Set<String>? = null
    private var photos: ContentObserver? = null
    private var lastPhotoId = 0L

    /**
     * Listens to the torch and camera when [torchOrCamera], to the settings when [settingsWatch], and to new
     * photos when [photosWatch].
     */
    @Synchronized
    fun watch(
        torchOrCamera: Boolean,
        settingsWatch: Boolean,
        photosWatch: Boolean = false,
    ) {
        if (torchOrCamera) startCameras() else stopCameras()
        if (settingsWatch) startSettings() else stopSettings()
        if (photosWatch) startPhotos() else stopPhotos()
    }

    @Synchronized
    fun stop() = watch(torchOrCamera = false, settingsWatch = false)

    private fun startCameras() {
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

    private fun stopCameras() {
        torch?.let { cameras.unregisterTorchCallback(it) }
        availability?.let { cameras.unregisterAvailabilityCallback(it) }
        torch = null
        availability = null
        torchOn.clear()
        cameraInUse.clear()
    }

    private fun startSettings() {
        if (settings != null) return
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    settingsChanged()
                }
            }
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        settings = observer
        rotation = rotationOn()
        accessibility = accessibilityPackages()
    }

    private fun stopSettings() {
        settings?.let { resolver.unregisterContentObserver(it) }
        settings = null
        rotation = null
        accessibility = null
    }

    private fun settingsChanged() {
        val nowRotation = rotationOn()
        if (nowRotation != rotation) {
            rotation = nowRotation
            emit(state("autorotate.changed", nowRotation))
        }
        val before = accessibility.orEmpty()
        val now = accessibilityPackages()
        accessibility = now
        (now - before).forEach { emit(accessibilityEvent(it, true)) }
        (before - now).forEach { emit(accessibilityEvent(it, false)) }
    }

    private fun rotationOn(): Boolean = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** The packages of the enabled accessibility services, from the "package/service:package/service" list. */
    private fun accessibilityPackages(): Set<String> =
        Settings.Secure
            .getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun startPhotos() {
        if (photos != null) return
        lastPhotoId = newestPhoto()?.id ?: 0L
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    photoAdded()
                }
            }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        photos = observer
    }

    private fun stopPhotos() {
        photos?.let { resolver.unregisterContentObserver(it) }
        photos = null
        lastPhotoId = 0L
    }

    /** Reports the newest photo when it is in the camera folder. Without photo access nothing is readable, so nothing is reported. */
    private fun photoAdded() {
        val newest = newestPhoto() ?: return
        if (newest.id <= lastPhotoId) return
        lastPhotoId = newest.id
        if (newest.path.contains("DCIM")) {
            emit(TriggerEvent("photo.taken", mapOf("name" to newest.name, "path" to newest.path)))
        }
    }

    private fun newestPhoto(): Photo? =
        runCatching {
            val args =
                Bundle().apply {
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media._ID))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                }
            resolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
                    args,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Photo(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
                    } else {
                        null
                    }
                }
        }.getOrNull()

    private class Photo(
        val id: Long,
        val name: String,
        val path: String,
    )

    private fun accessibilityEvent(
        pkg: String,
        on: Boolean,
    ) = TriggerEvent("accessibility.changed", mapOf("package" to pkg, "state" to PhoneState.onOff(on)))

    private fun state(
        type: String,
        on: Boolean,
    ) = TriggerEvent(type, mapOf("state" to PhoneState.onOff(on)))
}
