import org.gradle.api.tasks.SourceSetContainer
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson-test")
    exclude(group = "org.springframework.boot", module = "spring-boot-jackson")
}

dependencies {
    implementation(project(":muyun-spring-boot-starter"))
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.restclient.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(testFixtures(project(":muyun-iam")))
    testImplementation(testFixtures(project(":muyun-platform")))
}

val demoRuntimeClasspath = configurations.create("demoRuntimeClasspath") {
    extendsFrom(configurations.runtimeClasspath.get())
}

dependencies {
    add(demoRuntimeClasspath.name, project(":muyun-demo-web"))
}

val standardRuntimeProjectPaths = listOf(
    ":muyun-common",
    ":muyun-ability",
    ":muyun-dynamic",
    ":muyun-platform",
    ":muyun-iam",
    ":muyun-web-adapter",
    ":muyun-platform-web",
    ":muyun-iam-web",
    ":muyun-dynamic-web",
)
val demoRuntimeProjectPaths = standardRuntimeProjectPaths + listOf(
    ":muyun-demo",
    ":muyun-demo-web",
)
val standardRuntimeOutputs = files(standardRuntimeProjectPaths.map { projectPath ->
    rootProject.project(projectPath)
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .map { sourceSet -> sourceSet.output }
})
val demoRuntimeOutputs = files(demoRuntimeProjectPaths.map { projectPath ->
    rootProject.project(projectPath)
        .extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .map { sourceSet -> sourceSet.output }
})
val bootMainOutput = extensions
    .getByType<SourceSetContainer>()
    .named("main")
    .map { sourceSet -> sourceSet.output }
val standardRuntimeBuildDirectories = standardRuntimeProjectPaths.map { projectPath ->
    rootProject.project(projectPath).layout.buildDirectory
}
val demoRuntimeBuildDirectories = demoRuntimeProjectPaths.map { projectPath ->
    rootProject.project(projectPath).layout.buildDirectory
}

tasks.named<BootRun>("bootRun") {
    // Run against current project outputs without duplicate classes from matching project JARs.
    classpath = standardRuntimeOutputs + classpath.filter { entry ->
        standardRuntimeBuildDirectories.none { buildDirectory ->
            entry.toPath().startsWith(buildDirectory.get().asFile.toPath())
        }
    }
}

tasks.register<BootRun>("demoBootRun") {
    description = "Runs the standard application with the optional school demo delivery."
    group = "application"
    mainClass.set("net.ximatai.muyun.spring.boot.MuYunSpringApplication")
    systemProperty("spring.profiles.include", "school-demo")
    // Include current project outputs for every assembled module, including the optional demo.
    classpath = bootMainOutput.get() + demoRuntimeOutputs + demoRuntimeClasspath.filter { entry ->
        demoRuntimeBuildDirectories.none { buildDirectory ->
            entry.toPath().startsWith(buildDirectory.get().asFile.toPath())
        }
    }
}
