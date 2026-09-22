plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Kotlin/Native 目标：x64 电脑 + arm64（手机 / aarch64 Linux）
    linuxX64()
    linuxArm64()

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
    }
}
