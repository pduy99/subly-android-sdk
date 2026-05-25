package com.helios.subly.sdk.domain.repository

/**
 * Provides access to system-level media playback state.
 */
interface MediaPlaybackRepository {
    /**
     * Returns true if the system is currently rendering audio
     * (e.g. music or video is actively playing).
     */
    fun isMediaPlaying(): Boolean
}
