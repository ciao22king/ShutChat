import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * Linux desktop client.
 *
 * Same protocol, same crypto and the same mesh as the Android app, because all
 * of that lives in :shared. What is here is only what a desktop needs: a window,
 * a local file store and a WebRTC wrapper for the JVM.
 */

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.zxing.core)

    implementation(libs.webrtc.java)
    // Prebuilt native libwebrtc for this platform; the classes live in the jar
    // above, the .so files in this classifier.
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtcJava.get()}:linux-x86_64")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Opt-in switch for the broker round-trip test: it needs internet, so it
    // must never run by accident in CI.
    systemProperty("shutchat.it", System.getProperty("shutchat.it") ?: "0")
    testLogging {
        events("passed", "failed", "skipped")
    }
}

compose.desktop {
    application {
        mainClass = "dev.shutchat.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "shutchat"
            packageVersion = "1.4.0"
            description = "ShutChat — peer-to-peer messenger with calls"
            vendor = "ShutChat"
            copyright = "Apache 2.0"
            modules("java.net.http", "jdk.crypto.ec", "java.management")
            linux {
                iconFile.set(project.file("icons/shutchat.png"))
                menuGroup = "Network"
                appCategory = "Network"
                // Without this jpackage ships no .desktop entry and no icon, and
                // the installed app never shows up in the desktop menu.
                shortcut = true
                debMaintainer = "shutchat@localhost"
            }
        }
    }
}
