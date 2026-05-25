package com.helios.subly.sdk.data.audio

import android.content.Context
import android.media.AudioManager
import com.helios.subly.sdk.domain.repository.MediaPlaybackRepository

internal class AndroidMediaPlaybackRepository(
    context: Context
) : MediaPlaybackRepository {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun isMediaPlaying(): Boolean {
        return audioManager.isMusicActive
    }
}
