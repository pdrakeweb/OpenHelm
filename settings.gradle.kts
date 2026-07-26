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
// any JVM, which keeps the wire format verifiable without an emulator or a device. The Android app
// module is added in phase 1.
include(":protocol")
