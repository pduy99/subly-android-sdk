import java.net.URI

// Script plugin: Subly SDK Whisper AI core bootstrap.
//
// Applied from :sdk's build.gradle.kts via `apply(from = ...)`. Owns the
// on-demand fetch of the upstream whisper.cpp source tree and the quantized
// GGML model file - both are too large/binary to commit, so we keep them out
// of git and let developers (and CI) materialize them with a single task.
//
// Tasks registered (group "subly"):
//   - fetchWhisperSource       : git clone whisper.cpp at $whisperTag
//   - downloadWhisperTinyModel : pull ggml-tiny-q5_1.bin from Hugging Face
//   - prepareWhisper           : aggregator; run this once after `git clone`.
//
// Auto-detection of the CMake `SUBLY_HAS_WHISPER` flag stays in the SDK
// build script (it has to flow into `android.defaultConfig.externalNativeBuild`).
// The plugin also exposes `extra["sublyHasWhisper"]` so the consumer script
// doesn't need to re-derive the path constants.

// Pinned upstream tag and model URL. Bump deliberately - new whisper.cpp
// releases occasionally change the GGML format or JNI signatures.
val whisperTag = "v1.7.4"
val whisperModelUrl =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"

val whisperRepoDir = layout.projectDirectory.dir("src/main/cpp/whisper.cpp")
val whisperRepoMarker = whisperRepoDir.file("CMakeLists.txt")
val whisperModelFile =
    layout.projectDirectory.file("src/main/assets/models/ggml-tiny-q5_1.bin")

// Surfaced to the applying build script.
extra["sublyHasWhisper"] = whisperRepoMarker.asFile.exists()

val fetchWhisperSource by tasks.registering {
    group = "subly"
    description = "Clone whisper.cpp at $whisperTag into src/main/cpp/whisper.cpp."
    val marker = whisperRepoMarker.asFile
    outputs.file(marker)
    outputs.upToDateWhen { marker.exists() }
    doLast {
        if (marker.exists()) return@doLast
        val dir = whisperRepoDir.asFile
        if (dir.exists()) dir.deleteRecursively()
        dir.parentFile.mkdirs()
        logger.lifecycle("Cloning whisper.cpp@$whisperTag -> ${dir.absolutePath}")
        val rc = ProcessBuilder(
            "git", "clone",
            "--depth", "1",
            "--branch", whisperTag,
            "https://github.com/ggerganov/whisper.cpp",
            dir.absolutePath,
        ).inheritIO().start().waitFor()
        check(rc == 0) { "git clone failed (exit=$rc). Is git on PATH?" }
    }
}

val downloadWhisperTinyModel by tasks.registering {
    group = "subly"
    description = "Download ggml-tiny-q5_1.bin (~31 MB) into assets/models."
    val target = whisperModelFile.asFile
    outputs.file(target)
    // Sanity floor: a stray LFS pointer or 404 page is < 1 MB.
    outputs.upToDateWhen { target.exists() && target.length() > 1_000_000 }
    doLast {
        if (target.exists() && target.length() > 1_000_000) return@doLast
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading $whisperModelUrl -> ${target.absolutePath}")
        URI(whisperModelUrl).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val bytes = target.length()
        check(bytes > 1_000_000) {
            "Downloaded model is only $bytes bytes; likely an LFS pointer or 404."
        }
        logger.lifecycle("Model OK ($bytes bytes).")
    }
}

@Suppress("unused")
val prepareWhisper by tasks.registering {
    group = "subly"
    description = "One-shot: clone whisper.cpp + download the tiny model. Idempotent."
    dependsOn(fetchWhisperSource, downloadWhisperTinyModel)
    doLast {
        logger.lifecycle(
            "Whisper assets ready. Re-run any build task; CMake will flip " +
                "SUBLY_HAS_WHISPER=ON on the next configuration."
        )
    }
}
