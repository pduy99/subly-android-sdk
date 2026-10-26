package com.helios.subly.audio.impl

import android.content.Context
import android.media.AudioManager
import com.helios.subly.audio.api.MediaPlaybackDataSource

/** [MediaPlaybackDataSource] backed by [AudioManager.isMusicActive]. */
class AndroidMediaPlaybackDataSource(
    context: Context,
) : MediaPlaybackDataSource {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun isMediaPlaying(): Boolean = audioManager.isMusicActive
}
