package com.helios.subly.audio.api

/**
 * Provides access to system-level media playback state.
 */
interface MediaPlaybackDataSource {
    /**
     * Returns true if the system is currently rendering audio
     * (e.g. music or video is actively playing).
     */
    fun isMediaPlaying(): Boolean
}
