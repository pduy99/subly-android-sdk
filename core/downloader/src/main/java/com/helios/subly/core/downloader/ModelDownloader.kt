package com.helios.subly.core.downloader

import java.io.File
import kotlinx.coroutines.flow.Flow

interface ModelDownloader {
    fun downloadModel(url: String, destFile: File): Flow<Float>
}
