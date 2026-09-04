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
        // 本地与 CI 的最优仓库顺序相反：
        // - 本地（国内）：阿里云优先。否则新增依赖会先走 dl.google.com，
        //   国内访问不畅会让依赖解析长时间 Hang 住（Gradle 解析无默认超时）。
        // - CI（GitHub Actions 自动设置 CI=true）：官方优先，海外访问更快更稳。
        // 某镜像 502 时 Gradle 会自动尝试后续仓库，避免整仓禁用。
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "composedTV"
include(":app")
