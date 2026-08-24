package com.helios.subly.benchmark.dataset

import com.google.gson.Gson
import java.io.File

/**
 * The benchmark corpus manifest — `benchmark/dataset/manifest.json` is the
 * single source of truth for which clips exist and where their verified
 * references live. Parsed on the device (to enumerate clips) and on the host
 * (to validate the checked-in dataset via `:benchmark:validateDataset`).
 *
 * Fields are nullable because Gson populates whatever the JSON contains;
 * [validate] is the completeness gate.
 */
data class DatasetManifest(val clips: List<Clip> = emptyList()) {

    data class Clip(
        val id: String? = null,
        /** BCP-47 primary subtag; one of [REQUIRED_TARGETS]' keys. */
        val language: String? = null,
        /** Path of the mp4, relative to the dataset root. */
        val audio: String? = null,
        val durationSec: Int = 0,
        /** Path of the verified, punctuated transcript. */
        val transcript: String? = null,
        /** Target language → path of the verified translation. */
        val translations: Map<String, String> = emptyMap(),
        val notes: String? = null,
    )

    /** @return human-actionable problems; empty means the dataset is sound. */
    fun validate(root: File): List<String> {
        val errors = mutableListOf<String>()
        if (clips.isEmpty()) errors += "manifest has no clips"

        val seen = mutableSetOf<String>()
        clips.forEachIndexed { index, clip ->
            val id = clip.id?.takeIf { it.isNotBlank() } ?: run {
                errors += "clip[$index]: missing id"
                "clip[$index]"
            }
            if (clip.id != null && !seen.add(clip.id)) {
                errors += "duplicate clip id '$id'"
            }

            val requiredTargets = REQUIRED_TARGETS[clip.language]
            if (requiredTargets == null) {
                errors += "$id: unsupported language '${clip.language}' — expected one of ${REQUIRED_TARGETS.keys}"
            }
            if (clip.durationSec <= 0) {
                errors += "$id: durationSec must be positive"
            }

            checkFile(root, clip.audio, "$id: audio", errors, requireText = false)
            checkFile(root, clip.transcript, "$id: transcript", errors, requireText = true)

            if (clip.translations.isEmpty()) {
                errors += "$id: no translation references"
            }
            requiredTargets?.forEach { target ->
                if (target !in clip.translations) {
                    errors += "$id: missing translation target '$target'"
                }
            }
            clip.translations.forEach { (target, path) ->
                checkFile(root, path, "$id: translation[$target]", errors, requireText = true)
            }
        }
        return errors
    }

    private fun checkFile(
        root: File,
        path: String?,
        label: String,
        errors: MutableList<String>,
        requireText: Boolean,
    ) {
        if (path.isNullOrBlank()) {
            errors += "$label: no path in manifest"
            return
        }
        val file = File(root, path)
        when {
            !file.isFile -> errors += "$label file not found: $path"
            requireText && file.readText().isBlank() -> errors += "$label file is blank: $path"
        }
    }

    companion object {
        /** Benchmark language → translation targets every clip must provide. */
        val REQUIRED_TARGETS: Map<String, Set<String>> = mapOf(
            "en" to setOf("vi"),
            "zh" to setOf("vi", "en"),
            "ja" to setOf("vi", "en"),
        )

        fun parse(json: String): DatasetManifest =
            Gson().fromJson(json, DatasetManifest::class.java) ?: DatasetManifest()
    }
}
