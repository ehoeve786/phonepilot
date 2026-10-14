package app.pocketpilot.automation

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.pocketpilot.capability.notifications.PocketPilotNotificationListener
import app.pocketpilot.core.automation.TriggerEvent

/**
 * "Music or video": follows every app's media session, which Android shares with apps that have
 * notification access. Fires when playback starts or stops and when the track changes.
 */
internal class MediaWatch(
    private val context: Context,
    private val hidden: (String) -> Boolean,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, PocketPilotNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val followed = HashMap<MediaSession, Followed>()

    private class Followed(
        val controller: MediaController,
        val callback: MediaController.Callback,
    )

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> follow(controllers.orEmpty()) }

    fun start() {
        // Without notification access Android refuses; the trigger then stays quiet until it is allowed.
        runCatching {
            sessions.addOnActiveSessionsChangedListener(changed, listener, main)
            follow(sessions.getActiveSessions(listener))
        }
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(changed) }
        followed.values.forEach { it.controller.unregisterCallback(it.callback) }
        followed.clear()
    }

    private fun follow(controllers: List<MediaController>) {
        val now = controllers.associateBy { MediaSession(it.packageName, it.sessionToken.hashCode()) }
        (followed.keys - now.keys).forEach { key -> followed.remove(key)?.let { it.controller.unregisterCallback(it.callback) } }
        (now.keys - followed.keys).forEach { key ->
            val controller = now.getValue(key)
            if (hidden(controller.packageName)) return@forEach
            val callback = Callback(controller)
            controller.registerCallback(callback, main)
            followed[key] = Followed(controller, callback)
        }
    }

    private data class MediaSession(
        val packageName: String,
        val token: Int,
    )

    private inner class Callback(
        private val controller: MediaController,
    ) : MediaController.Callback() {
        private var playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        private var track = trackOf(controller.metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val now = state?.state == PlaybackState.STATE_PLAYING
            // Buffering and skipping pass through other states; only playing and stopped or paused count.
            val settled =
                state?.state in
                    setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE)
            if (!settled || now == playing) return
            playing = now
            fire(if (now) "playing" else "paused")
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val next = trackOf(metadata)
            if (next == null || next == track) return
            val first = track == null
            track = next
            if (!first) fire("track")
        }

        private fun fire(state: String) {
            val metadata = controller.metadata
            emit(
                TriggerEvent(
                    "media.changed",
                    mapOf(
                        "state" to state,
                        "title" to metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                        "artist" to metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                        "package" to controller.packageName,
                    ),
                ),
            )
        }
    }

    private fun trackOf(metadata: MediaMetadata?): String? =
        metadata
            ?.let {
                it.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty() + "\u0000" +
                    it.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            }?.takeIf { it.length > 1 }
}
