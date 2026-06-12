package com.helios.subly.core.model

sealed class AsrResult(open val text: String) {
    data class Partial(override val text: String) : AsrResult(text)
    data class Final(override val text: String) : AsrResult(text)
}