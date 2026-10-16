package com.helios.subly.core.model

/** Compute unit an engine ended up running inference on. */
enum class DelegateType { CPU, GPU, NPU }

/**
 * One attempt to bring an engine up on a [delegate].
 *
 * @property succeeded whether the engine initialised on this delegate.
 * @property initMs wall-clock time the attempt took, success or not.
 * @property failure why it failed — an exception message, or a note such as
 *   "skipped: crashed the process on a previous launch". Null on success.
 */
data class BackendAttempt(
    val delegate: DelegateType,
    val succeeded: Boolean,
    val initMs: Long,
    val failure: String? = null,
)

/**
 * Which delegate an engine runs on, and how it got there.
 *
 * [attempts] is in the order tried. A report whose [active] delegate is not
 * the first attempt's delegate is a fallback; [isFallback] says so without
 * the caller re-deriving it.
 */
data class AccelerationReport(
    val component: String,
    val active: DelegateType,
    val attempts: List<BackendAttempt>,
) {
    val isFallback: Boolean
        get() = attempts.firstOrNull()?.delegate?.let { it != active } ?: false

    val isHardwareAccelerated: Boolean
        get() = active != DelegateType.CPU
}

/**
 * Implemented by engines that can report their compute delegate. Null until
 * the engine has been prepared.
 */
interface AccelerationAware {
    val accelerationReport: AccelerationReport?
}
