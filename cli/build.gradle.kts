plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// 是否静态链接：静态产物可以直接丢到 Android（bionic）上执行。
// 若链接器报找不到 -lxxx，加 -PbootforgeStatic=false 退回动态链接。
val staticLink: Boolean = (project.findProperty("bootforgeStatic") as String?) != "false"

kotlin {
    linuxX64 {
        binaries {
            executable {
                baseName = "bootforge"
                if (staticLink) linkerOpts("-static")
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                baseName = "bootforge"
                if (staticLink) linkerOpts("-static")
            }
        }
    }

    // CLI 直接放 commonMain：本模块只有 native 目标，不需要 intermediate source set
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":core"))
            }
        }
    }
}
