package com.helios.subly.translator.mlkit

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

interface LanguageIdentifier {
    suspend fun identify(text: String): String?
}

internal class LanguageIdentifierImpl : LanguageIdentifier {
    private val client = LanguageIdentification.getClient()

    override suspend fun identify(text: String): String? = withContext(Dispatchers.IO) {
        val result = client.identifyLanguage(text).await()
        if (result == "und") null else result
    }
}