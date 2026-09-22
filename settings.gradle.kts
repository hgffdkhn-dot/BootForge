pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 必须是 PREFER_SETTINGS：Kotlin/Native 插件在配置期会往工程里加一个 ivy 仓库
    // 用来下载 konan 工具链，FAIL_ON_PROJECT_REPOS 会直接把它判为非法
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BootForge"
include(":app")
include(":core")
include(":cli")
