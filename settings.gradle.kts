pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "MuYunSpring"

include(
    "muyun-common",
    "muyun-ability",
    "muyun-dynamic",
    "muyun-platform",
    "muyun-iam",
    "muyun-iam-welink",
    "muyun-web-adapter",
    "muyun-platform-web",
    "muyun-iam-web",
    "muyun-dynamic-web",
    "muyun-demo",
    "muyun-demo-web",
    "muyun-spring-bom",
    "muyun-spring-boot-starter",
    "muyun-boot"
)
