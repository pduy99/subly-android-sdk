//package com.helios.subly.sdk.data.vision
//
//import android.media.projection.MediaProjection
//import com.helios.subly.sdk.domain.model.VisionFrame
//import com.helios.subly.sdk.domain.repository.VisionCaptureRepository
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.currentCoroutineContext
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.flow
//import kotlinx.coroutines.flow.flowOn
//import kotlinx.coroutines.isActive
//import kotlin.coroutines.CoroutineContext
//import kotlin.time.ExperimentalTime
//import kotlin.time.TimeSource
//
///**
// * [VisionCaptureRepository] backed by a pluggable [VirtualDisplayDataSource].
// *
// * The platform-specific projection/display/`ImageReader` lifecycle lives
// * behind [dataSource]; this class only:
// * - drives the data source's pull loop into a cold `Flow<VisionFrame>`,
// * - attaches session-relative timestamps (parity with the audio repo),
// * - manages Flow cancellation and idempotent teardown.
// *
// * Keeping the repository framework-free lets us unit-test the orchestration
// * with a fake data source - no `VirtualDisplay`/`ImageReader` mocks needed.
// */
//@OptIn(ExperimentalTime::class)
//internal class VirtualDisplayCaptureSource(
//    private val dataSource: VirtualDisplayDataSource,
//    private val timeSource: TimeSource = TimeSource.Monotonic,
//    private val ioContext: CoroutineContext = Dispatchers.IO,
//) : VisionCaptureRepository {
//
//    override fun frames(mediaProjection: MediaProjection, targetFps: Int): Flow<VisionFrame> = flow {
//        dataSource.open(mediaProjection, targetFps)
//        // Session-relative monotonic origin (matches AudioPlaybackCaptureSource).
//        val sessionStart = timeSource.markNow()
//        try {
//            while (currentCoroutineContext().isActive) {
//                val raw = dataSource.readFrame() ?: break
//                emit(
//                    VisionFrame(
//                        pixels = raw.pixels,
//                        width = raw.width,
//                        height = raw.height,
//                        rowStrideBytes = raw.rowStrideBytes,
//                        timestampMs = sessionStart.elapsedNow().inWholeMilliseconds,
//                    ),
//                )
//            }
//        } finally {
//            dataSource.close()
//        }
//    }.flowOn(ioContext)
//
//    override fun stop() {
//        dataSource.close()
//    }
//}
