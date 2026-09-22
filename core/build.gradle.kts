plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    linuxX64()
    linuxArm64()

    sourceSets {
        val commonMain by getting
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        // 注意：目标专属源集（linuxX64Main 等）在 Kotlin DSL 里没有类型安全访问器，
        // 必须用 getByName 按名字取，否则会报 Unresolved reference
        getByName("linuxX64Main").dependsOn(nativeMain)
        getByName("linuxArm64Main").dependsOn(nativeMain)
    }
}
