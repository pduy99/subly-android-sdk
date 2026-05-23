package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.domain.repository.SublyEngine

internal object DefaultSublyEngineFactory : SublyEngine.Factory {
    override fun create(context: Context): SublyEngine = StubSublyEngine(context.applicationContext)
}
