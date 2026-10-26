plugins {
    // No version: AGP's built-in Kotlin already provides KGP on the classpath.
    id("org.jetbrains.kotlin.jvm")
    application
}

// Host-side stage 2 of the accuracy benchmark: judges device-stage results
// with the Claude API and renders the report. Run: ./gradlew :benchmark:judge:run
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.helios.subly.benchmark.judge.MainKt"
}

dependencies {
    implementation(libs.anthropic.java)
    implementation(libs.gson)
    testImplementation(libs.junit)
}

tasks.named<JavaExec>("run") {
    // Absolute paths so the judge works regardless of Gradle's working dir.
    systemProperty(
        "benchmark.results.root",
        rootProject.layout.projectDirectory.dir("benchmark/build/outputs").asFile.absolutePath,
    )
    systemProperty(
        "benchmark.dataset.dir",
        rootProject.layout.projectDirectory
            .dir("benchmark/src/androidTest/assets/dataset").asFile.absolutePath,
    )
    systemProperty(
        "benchmark.reports.dir",
        rootProject.layout.projectDirectory.dir("reports").asFile.absolutePath,
    )
    systemProperty(
        "benchmark.local.properties",
        rootProject.layout.projectDirectory.file("local.properties").asFile.absolutePath,
    )
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
