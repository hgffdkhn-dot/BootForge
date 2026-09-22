plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                // 静态链接，产出的二进制可以直接丢到 Android（bionic）上跑
                linkerOpts("-static")
                baseName = "bootforge"
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                linkerOpts("-static")
                baseName = "bootforge"
            }
        }
    }

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":core"))
            }
        }
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
    }
}
