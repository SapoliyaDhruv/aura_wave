plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.newpipe.extractor)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Tests named Live* talk to YouTube. They run only with -Plive, so a normal
    // build never depends on the network or on YouTube being reachable.
    if (!project.hasProperty("live")) exclude("**/Live*")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    outputs.upToDateWhen { !project.hasProperty("live") }
}
