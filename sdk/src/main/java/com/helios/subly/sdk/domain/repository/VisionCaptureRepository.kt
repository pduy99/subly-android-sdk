//package com.helios.subly.sdk.domain.repository
//
//import android.media.projection.MediaProjection
//import com.helios.subly.sdk.domain.model.VisionFrame
//import kotlinx.coroutines.flow.Flow
//
///**
// * Boundary over the fallback vision capture pipeline.
// *
// * Implementations spawn a `VirtualDisplay` bound to an `ImageReader` only after
// * the audio pipeline reports `DrmBlocked`, to keep idle cost low.
// */
//interface VisionCaptureRepository {
//
//    /**
//     * Cold stream of throttled, downscaled screen frames.
//     *
//     * @param mediaProjection Same projection token used by the audio pipeline.
//     * @param targetFps Upper bound on emission rate; implementations may emit slower.
//     */
//    fun frames(mediaProjection: MediaProjection, targetFps: Int = 2): Flow<VisionFrame>
//
//    /** Eagerly release the virtual display and image reader. */
//    fun stop()
//}
