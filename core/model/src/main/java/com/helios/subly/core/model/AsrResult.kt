package com.helios.subly.core.model

sealed class AsrResult {
    data class Partial(val text: String) : AsrResult()
    data class Final(val text: String) : AsrResult()
}