package com.helios.subly.sdk.data.vision

import com.helios.subly.sdk.domain.model.VisionFrame

/**
 * Boundary over the on-device OCR engine used by the vision fallback pipeline.
 *
 * Implementations are expected to be cheap to construct and safe to call
 * concurrently from a single coroutine consumer. Result text is the raw
 * recognised string in the *source* language; translation is delegated to
 * [com.helios.subly.sdk.domain.repository.TranslatorRepository] downstream.
 */
internal interface OcrRecognizer {

    /** Recognise text in [frame]; returns an empty string when nothing was found. */
    suspend fun recognize(frame: VisionFrame): String

    /** Release native resources held by the recogniser. */
    fun close()
}
