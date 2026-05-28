package com.helios.subly.audio.impl

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.helios.subly.audio.api.AudioCaptureDataSource
import java.util.concurrent.atomic.AtomicReference

/**
 * Production [AudioCaptureDataSource] backed by `AudioRecord` +
 * `AudioPlaybackCaptureConfiguration`.
 */
class AudioRecordDataSource(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    override val framesPerRead: Int = DEFAULT_SAMPLE_RATE_HZ * DEFAULT_FRAME_DURATION_MS / 1_000,
) : AudioCaptureDataSource {

    override val channelCount: Int = 1

    private val recorderRef = AtomicReference<AudioRecord?>()

    @SuppressLint("MissingPermission")
    override fun open(mediaProjection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(framesPerRead * Short.SIZE_BYTES * 4)

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (state=${recorder.state})"
        }
        recorderRef.set(recorder)
        recorder.startRecording()
    }

    override fun read(buffer: ShortArray): Int {
        val recorder = recorderRef.get() ?: return AudioCaptureDataSource.READ_STOPPED
        val n = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        // ERROR_INVALID_OPERATION (-3) is raised when the recorder has been stopped from another thread.
        return if (n == AudioRecord.ERROR_INVALID_OPERATION) AudioCaptureDataSource.READ_STOPPED else n
    }

    override fun close() {
        val recorder = recorderRef.getAndSet(null) ?: return
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }
        runCatching { recorder.release() }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
        const val DEFAULT_FRAME_DURATION_MS: Int = 50
    }
}
