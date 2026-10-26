package com.helios.subly.audio.api

import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.flow.Flow

/**
 * An [AudioCapture] whose frames don't come from system-audio playback and so
 * need no `MediaProjection` token — file decoders (the accuracy benchmark),
 * microphone sources, fakes in tests.
 *
 * Installing one via `Subly.Builder.setAudioCapture` unlocks the
 * projection-less `SublySession.start()` overload. Purely additive: existing
 * [AudioCapture] implementations are unaffected.
 */
interface ProjectionlessAudioCapture : AudioCapture {

    /**
     * Cold stream of PCM frames, same contract as
     * [AudioCapture.frames] minus the projection token: collecting starts
     * capture, cancelling the collecting scope (or calling [stop]) releases
     * the source.
     */
    fun frames(): Flow<AudioFrame>
}
