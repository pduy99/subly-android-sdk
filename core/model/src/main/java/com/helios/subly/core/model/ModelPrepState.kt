package com.helios.subly.core.model

sealed interface ModelPrepState {

    data object Checking : ModelPrepState

    data class Preparing(val progress: Float) : ModelPrepState

    data object Ready : ModelPrepState

    data class Error( val cause: Throwable? = null) : ModelPrepState
}
