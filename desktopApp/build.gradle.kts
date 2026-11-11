import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.example.holzaffe.MainKt"

        // jpackage comes from a Temurin 21 toolchain (auto-provisioned): Android Studio's JBR also
        // satisfies "JDK 21" but ships without jpackage. The app bundles that JDK's runtime via jlink.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            // Each format can only be built on its own operating system: the DMG on the Mac, the DEB on Linux.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "HolzAffe"
            packageVersion = libs.versions.app.get()
            description = "HolzAffe – Akkordfolgen nach Gehör erkennen, gegen die Uhr"
            vendor = "TheBigSchabowski"
            // java.prefs: high score and settings.
            modules("java.desktop", "java.prefs")
            macOS {
                bundleID = "com.example.holzaffe"
                iconFile.set(project.file("icon.icns"))
            }
            linux {
                packageName = "holzaffe"
                iconFile.set(project.file("icon.png"))
                debMaintainer = "TheBigSchabowski@users.noreply.github.com"
                appCategory = "games"
                menuGroup = "Game;Education;"
            }
        }
    }
}
