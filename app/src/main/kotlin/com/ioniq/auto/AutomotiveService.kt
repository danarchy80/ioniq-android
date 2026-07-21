package com.ioniq.auto

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.ioniq.R
import timber.log.Timber

/**
 * Android Auto MediaBrowserService
 *
 * Exposes vehicle telemetry as browsable media items in Android Auto's
 * media centre interface. Uses MediaBrowserServiceCompat so the same
 * service also works for the handheld app's notification-based media controls.
 *
 * Reference: https://developer.android.com/training/cars/media
 */
class AutomotiveService : MediaBrowserServiceCompat() {

    companion object {
        const val ROOT_ID = "__ioniq_root"
        private const val MEDIA_ID_DASHBOARD = "dashboard"
        private const val MEDIA_ID_BATTERY = "battery"
        private const val MEDIA_ID_CELLS = "cells"
        private const val MEDIA_ID_TEMPERATURE = "temperature"
        private const val MEDIA_ID_CHARGING = "charging"
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "IoniqAutomotiveService").apply {
            isActive = true
        }
        sessionToken = mediaSession?.sessionToken
        Timber.d("AutomotiveService created with media session")
    }

    /**
     * Called by Android Auto to authenticate this service as a content source.
     * Returning null rejects unauthorised clients.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Allow all callers for now; tighten with package allowlist later
        return BrowserRoot(ROOT_ID, null)
    }

    /**
     * Called by Android Auto to enumerate the content tree.
     * Returns a list of MediaItem objects representing browsable children.
     */
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = when (parentId) {
            ROOT_ID -> listOf(
                buildBrowsableChild(MEDIA_ID_DASHBOARD, "Dashboard", MediaDescriptionCompat.BT_FOLDER_TYPE_ALBUMS),
                buildBrowsableChild(MEDIA_ID_CHARGING, "Charging Status", MediaDescriptionCompat.BT_FOLDER_TYPE_PLAYLISTS),
            )
            MEDIA_ID_DASHBOARD -> listOf(
                buildPlayableChild(MEDIA_ID_BATTERY, "Battery", "State of Charge & Voltage"),
                buildPlayableChild(MEDIA_ID_TEMPERATURE, "Temperature", "Battery & Ambient Temps"),
            )
            MEDIA_ID_CHARGING -> listOf(
                buildPlayableChild(MEDIA_ID_CELLS, "Cell Voltages", "Min / Max / Delta"),
            )
            else -> emptyList()
        }
        result.sendResult(items.toMutableList())
    }

    // ── Helpers ──

    private fun buildBrowsableChild(id: String, title: String, type: Long):
            MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle("Tap to browse")
            .build()
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun buildPlayableChild(id: String, title: String, subtitle: String):
            MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
