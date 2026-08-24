package com.helios.subly.benchmark.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Host-side dataset gate, exposed as `./gradlew :benchmark:validateDataset`.
 * Fails when `benchmark/dataset/` and its manifest drift apart, with messages
 * that say exactly what to fix.
 */
class DatasetValidationTest {

    private val datasetDir = File(
        requireNotNull(System.getProperty("benchmark.dataset.dir")) {
            "benchmark.dataset.dir system property not set — run via Gradle"
        }
    )

    @Test
    fun `checked-in dataset parses and validates clean`() {
        val manifest = DatasetManifest.parse(File(datasetDir, "manifest.json").readText())
        val errors = manifest.validate(datasetDir)

        assertEquals("dataset should be valid, got: $errors", emptyList<String>(), errors)
        assertTrue("dataset must contain at least one clip", manifest.clips.isNotEmpty())
    }

    @Test
    fun `validation reports missing files, bad languages, and missing targets`() {
        val manifest = DatasetManifest.parse(
            """
            {
              "clips": [
                {
                  "id": "xx_missing_01",
                  "language": "fr",
                  "audio": "audio/nope.mp4",
                  "durationSec": 30,
                  "transcript": "references/nope.transcript.txt",
                  "translations": {}
                }
              ]
            }
            """.trimIndent()
        )

        val errors = manifest.validate(datasetDir)

        assertTrue(errors.any { "audio/nope.mp4" in it })
        assertTrue(errors.any { "language" in it && "fr" in it })
        assertTrue(errors.any { "translation" in it })
    }

    @Test
    fun `duplicate clip ids are rejected`() {
        val clip = """
            {
              "id": "en_dup",
              "language": "en",
              "audio": "audio/nope.mp4",
              "durationSec": 30,
              "transcript": "references/nope.transcript.txt",
              "translations": { "vi": "references/nope.translation.vi.txt" }
            }
        """.trimIndent()
        val manifest = DatasetManifest.parse("""{ "clips": [$clip, $clip] }""")

        assertTrue(manifest.validate(datasetDir).any { "duplicate" in it && "en_dup" in it })
    }
}
