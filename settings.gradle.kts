pluginManagement {
    repositories {
        // 官方优先（CI 海外访问更快更稳），阿里云镜像兜底避免单点故障
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 官方优先（androidx 走 google），阿里云镜像兜底；
        // 某镜像 502 时 Gradle 会自动尝试后续仓库，避免整仓禁用
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "composedTV"
include(":app")
