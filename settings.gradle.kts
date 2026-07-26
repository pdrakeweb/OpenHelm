// OpenHelm — a clean-room remote for Wi-Fi marine multifunction displays.
// See CLEAN-ROOM.md before adding anything to this project.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "openhelm"

// `protocol` is pure Kotlin with no Android dependencies: it builds and tests in milliseconds on
// any JVM, which keeps the wire format verifiable without an emulator or a device.
include(":protocol")

// `app` is the Android client: Compose UI, NsdManager discovery, the RRC connection, and (from
// phase 2) the low-latency video pipeline.
include(":app")
