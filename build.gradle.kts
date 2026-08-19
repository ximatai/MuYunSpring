import java.io.File
import java.util.Base64
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    signing
    id("io.github.jeadyx.sonatype-uploader") version "2.8" apply false
    java
}

val publicArtifactProjectNames = listOf(
    "muyun-common",
    "muyun-ability",
    "muyun-dynamic",
    "muyun-platform",
    "muyun-iam",
    "muyun-web-adapter",
    "muyun-platform-web",
    "muyun-iam-web",
    "muyun-dynamic-web",
    "muyun-spring-bom",
    "muyun-spring-boot-starter"
)
rootProject.extra["publicArtifactProjectNames"] = publicArtifactProjectNames

fun Project.releaseValue(propertyName: String, environmentName: String): String? =
    findProperty(propertyName)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        ?: providers.environmentVariable(environmentName).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun Project.releaseTag(): String? = findProperty("release.tag")?.toString()?.trim()
    ?.takeIf { it.isNotEmpty() && it != "null" }
    ?: providers.environmentVariable("GITHUB_REF_TYPE").orNull
        ?.takeIf { it == "tag" }
        ?.let { providers.environmentVariable("GITHUB_REF_NAME").orNull?.trim() }

fun Project.releaseVersion(): String? = releaseTag()
    ?.also { require(it.matches(Regex("v\\d+\\.\\d+\\.\\d+"))) { "Invalid release tag '$it'." } }
    ?.removePrefix("v")

val developmentVersion = providers.gradleProperty("muyunVersion").get()
val effectiveVersion = releaseVersion() ?: developmentVersion

allprojects {
    group = "net.ximatai.muyun.spring"
    version = effectiveVersion
}

fun Project.releaseSigningSecretKey(): String? {
    releaseValue("signing.secretKey", "SIGNING_SECRET_KEY")?.let { return it }
    return releaseValue("signing.secretKeyBase64", "SIGNING_SECRET_KEY_BASE64")?.let { encoded ->
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }
}

fun Project.requireReleaseCredentials() {
    val credentials = mapOf(
        "sonatype.token or SONATYPE_TOKEN" to releaseValue("sonatype.token", "SONATYPE_TOKEN"),
        "sonatype.password or SONATYPE_PASSWORD" to releaseValue("sonatype.password", "SONATYPE_PASSWORD"),
        "signing.keyId or SIGNING_KEY_ID" to releaseValue("signing.keyId", "SIGNING_KEY_ID"),
        "signing.secretKey/signing.secretKeyBase64 or SIGNING_SECRET_KEY/SIGNING_SECRET_KEY_BASE64" to releaseSigningSecretKey(),
        "signing.password or SIGNING_PASSWORD" to releaseValue("signing.password", "SIGNING_PASSWORD")
    )
    val missing = credentials.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) { "Missing required Maven Central release credentials: ${missing.joinToString(", ")}" }
}

tasks.register("verifyReleaseCredentials") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that Maven Central publishing credentials and signing keys are available."
    doLast { requireReleaseCredentials() }
}

tasks.register("verifyReleaseTagVersion") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the release tag matches the current development version."
    doLast {
        require(developmentVersion.endsWith("-SNAPSHOT")) {
            "muyunVersion '$developmentVersion' must describe the next development version."
        }
        val tag = releaseTag()
            ?: error("Missing release tag. Provide -Prelease.tag=v${developmentVersion.removeSuffix("-SNAPSHOT")} or set GITHUB_REF_NAME.")
        val expectedTag = "v${developmentVersion.removeSuffix("-SNAPSHOT")}"
        require(tag == expectedTag) {
            "Release tag '$tag' must match development version '$developmentVersion' (expected '$expectedTag')."
        }
        require(version.toString() == tag.removePrefix("v")) {
            "Release build version '${project.version}' must be derived from tag '$tag'."
        }
    }
}

tasks.register("publishReleaseToLocalRepository") {
    group = "publishing"
    description = "Publishes all public MuYunSpring artifacts to their local staging repositories."
    dependsOn(publicArtifactProjectNames.map { ":$it:publishAllPublicationsToMavenRepository" })
}

tasks.register("publishReleaseToConsumerRepository") {
    group = "publishing"
    description = "Publishes all public artifacts to one repository consumable by an external sample application."
    dependsOn(publicArtifactProjectNames.map { ":$it:publishAllPublicationsToConsumerRepository" })
}

tasks.register<Exec>("verifyPublishedConsumer") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Builds and starts an isolated application that consumes only published Maven artifacts."
    dependsOn("publishReleaseToConsumerRepository")
    environment("MUYUN_CONSUMER_VERSION", project.version.toString())
    commandLine("bash", "scripts/verify-published-consumer.sh")
}

tasks.register<Exec>("verifyMavenCentralConsumer") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Waits for Maven Central indexing, then starts the isolated consumer from Maven Central artifacts."
    commandLine("bash", "scripts/verify-maven-central-consumer.sh")
}

tasks.register("publishReleaseToSonatype") {
    group = "publishing"
    description = "Publishes all public MuYunSpring artifacts to Maven Central through Sonatype."
    dependsOn("verifyReleaseCredentials")
    dependsOn("verifyReleaseTagVersion")
    dependsOn(publicArtifactProjectNames.map { ":$it:publishToSonatype" })
    doFirst { requireReleaseCredentials() }
}

val releaseTagVerification = tasks.named("verifyReleaseTagVersion")
val releaseCredentialVerification = tasks.named("verifyReleaseCredentials")
gradle.projectsEvaluated {
    publicArtifactProjectNames.forEach { projectName ->
        val artifactProject = project(":$projectName")
        artifactProject.tasks.named("publishToSonatype") {
            mustRunAfter(releaseTagVerification, releaseCredentialVerification)
        }
        artifactProject.tasks.named("2.uploadDeploymentDir") {
            mustRunAfter(releaseTagVerification, releaseCredentialVerification)
        }
    }

    // The Sonatype Portal uploader creates and polls a deployment per module. Running any part
    // of that upload-to-publish flow concurrently can race the Portal API and produce transient
    // 404 responses, so the final remote publishing phase is deliberately serialized.
    publicArtifactProjectNames.zipWithNext().forEach { (precedingProjectName, projectName) ->
        val precedingProject = project(":$precedingProjectName")
        val artifactProject = project(":$projectName")

        artifactProject.tasks.named("2.uploadDeploymentDir") {
            mustRunAfter(precedingProject.tasks.named("publishToSonatype"))
        }
        artifactProject.tasks.named("publishToSonatype") {
            mustRunAfter(precedingProject.tasks.named("publishToSonatype"))
        }
    }
}

val testcontainersVersion = libs.versions.testcontainers.get()

subprojects {
    if (name != "muyun-spring-bom") {
        apply(plugin = "java-library")
    }

    extra["testcontainers.version"] = testcontainersVersion

    plugins.withId("java") {
        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxParallelForks = 1
            forkEvery = 0
            systemProperty("junit.jupiter.execution.parallel.enabled", "false")

            if (name == "test") {
                exclude("**/*IT.class")
            }

            if (project.name == "muyun-platform") {
                reports.html.required.set(false)
                reports.junitXml.includeSystemOutLog.set(false)
                reports.junitXml.includeSystemErrLog.set(false)
            }
        }

        val testSourceSet = extensions.getByType<SourceSetContainer>().named("test")
        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests against real external resources such as Testcontainers."
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            testClassesDirs = testSourceSet.get().output.classesDirs
            classpath = testSourceSet.get().runtimeClasspath
            shouldRunAfter(tasks.named("test"))
            include("**/*IT.class")
        }

        dependencies {
            "compileOnly"(rootProject.libs.lombok)
            "annotationProcessor"(rootProject.libs.lombok)
            "testCompileOnly"(rootProject.libs.lombok)
            "testAnnotationProcessor"(rootProject.libs.lombok)
        }
    }
}

configure(subprojects.filter { it.name in publicArtifactProjectNames }) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    apply(plugin = "io.github.jeadyx.sonatype-uploader")

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
    }

    plugins.withId("java-test-fixtures") {
        (components["java"] as AdhocComponentWithVariants).apply {
            withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
            withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
        }
    }

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    val componentName = if (plugins.hasPlugin("java-platform")) "javaPlatform" else "java"
                    from(components[componentName])
                pom {
                    name = "MuYunSpring"
                    description = "A dynamic-static unified enterprise application platform for Spring Boot."
                    url = "https://github.com/ximatai/MuYunSpring"
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    developers {
                        developer {
                            id = "aruis"
                            name = "Rui Liu"
                            email = "lovearuis@gmail.com"
                            organization = "戏码台"
                        }
                    }
                    scm {
                        connection = "scm:git:git://github.com/ximatai/MuYunSpring.git"
                        developerConnection = "scm:git:ssh://github.com/ximatai/MuYunSpring.git"
                        url = "https://github.com/ximatai/MuYunSpring"
                    }
                }
                }
            }
            repositories {
                maven { url = uri(layout.buildDirectory.dir("repo")) }
                maven {
                    name = "consumer"
                    url = uri(rootProject.layout.buildDirectory.dir("consumer-repo"))
                }
            }
        }

        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(
                releaseValue("signing.keyId", "SIGNING_KEY_ID").orEmpty(),
                releaseSigningSecretKey().orEmpty(),
                releaseValue("signing.password", "SIGNING_PASSWORD").orEmpty()
            )
            sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
        }

        extensions.configure<io.github.jeadyx.UploaderExtension> {
            repositoryPath = layout.buildDirectory.dir("repo").get().asFile.path
            tokenName = releaseValue("sonatype.token", "SONATYPE_TOKEN").orEmpty()
            tokenPasswd = releaseValue("sonatype.password", "SONATYPE_PASSWORD").orEmpty()
        }

        tasks.withType<Javadoc>().configureEach {
            val options = options as StandardJavadocDocletOptions
            options.addStringOption("Xdoclint:none", "-quiet")
            options.addBooleanOption("Xwerror", false)
        }
        tasks.matching { it.name == "publishToSonatype" }.configureEach {
            dependsOn("publishAllPublicationsToMavenRepository")
        }
        tasks.matching { it.name == "1.createDeploymentDir" || it.name == "2.uploadDeploymentDir" }.configureEach {
            dependsOn("publishAllPublicationsToMavenRepository")
        }
    }
}

val libraryProjects = subprojects.filter { it.name != "muyun-spring-bom" }
val unitTestTasks = libraryProjects.map { it.tasks.named<Test>("test") }
val integrationTestTasks = libraryProjects.map { it.tasks.named<Test>("integrationTest") }

tasks.register("demoClasses") {
    description = "Compiles the standard application and optional demo delivery for local demo development."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(":muyun-boot:classes", ":muyun-demo-web:classes")
}

tasks.register("demoBootRun") {
    description = "Runs the standard application with the optional school demo on its runtime classpath."
    group = "application"
    dependsOn(":muyun-boot:demoBootRun")
}

val coreModulePaths = setOf(
    ":muyun-common",
    ":muyun-ability",
    ":muyun-dynamic",
    ":muyun-platform",
    ":muyun-iam",
    ":muyun-demo",
)
val deliveryModulePaths = setOf(
    ":muyun-web-adapter",
    ":muyun-platform-web",
    ":muyun-iam-web",
    ":muyun-dynamic-web",
    ":muyun-demo-web",
    ":muyun-spring-boot-starter",
)
val webDeliveryModulePaths = deliveryModulePaths - ":muyun-web-adapter"
val productionDependencyConfigurations = setOf(
    "api",
    "implementation",
    "compileOnly",
    "compileOnlyApi",
    "runtimeOnly",
)
val allowedProductionProjectDependencies = mapOf(
    ":muyun-common" to emptySet(),
    ":muyun-ability" to setOf(":muyun-common"),
    ":muyun-dynamic" to setOf(":muyun-common", ":muyun-ability"),
    ":muyun-platform" to setOf(":muyun-ability", ":muyun-dynamic"),
    ":muyun-iam" to setOf(":muyun-ability", ":muyun-platform"),
    ":muyun-demo" to setOf(":muyun-ability", ":muyun-platform", ":muyun-iam"),
    ":muyun-web-adapter" to setOf(":muyun-ability"),
    ":muyun-platform-web" to setOf(":muyun-platform", ":muyun-web-adapter"),
    ":muyun-iam-web" to setOf(":muyun-iam", ":muyun-web-adapter", ":muyun-platform-web"),
    ":muyun-dynamic-web" to setOf(":muyun-dynamic", ":muyun-platform", ":muyun-web-adapter", ":muyun-platform-web"),
    ":muyun-demo-web" to setOf(":muyun-demo", ":muyun-web-adapter", ":muyun-platform-web"),
    ":muyun-spring-boot-starter" to setOf(
        ":muyun-platform", ":muyun-iam", ":muyun-web-adapter", ":muyun-platform-web",
        ":muyun-iam-web", ":muyun-dynamic-web"
    ),
    ":muyun-boot" to setOf(
        ":muyun-spring-boot-starter"
    ),
)

val moduleDependencyViolations = objects.listProperty<String>()
val bootSourceRoot = project(":muyun-boot").file("src/main/java")

tasks.register("verifyModuleBoundaries") {
    description = "Verifies production Gradle dependency direction and Boot host boundaries."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    inputs.property("dependencyViolations", moduleDependencyViolations)
    inputs.files(fileTree(bootSourceRoot) { include("**/*.java") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("bootSourceRoot", bootSourceRoot.absolutePath)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val violations = (inputs.properties.getValue("dependencyViolations") as List<String>).toMutableList()
        val sourceRoot = File(inputs.properties.getValue("bootSourceRoot") as String)
        val forbiddenBootStereotypes = Regex("@(RestController|Controller|Service|Repository)\\b")
        inputs.files.files.forEach { source ->
            if (forbiddenBootStereotypes.containsMatchIn(source.readText())) {
                violations += ":muyun-boot must not declare delivery or domain stereotype: " +
                        source.relativeTo(sourceRoot)
            }
        }
        check(violations.isEmpty()) {
            "Module boundary violations:\n${violations.joinToString("\n") { " - $it" }}"
        }
    }
}

gradle.projectsEvaluated {
    val violations = mutableListOf<String>()
    subprojects.forEach { sourceProject ->
        productionDependencyConfigurations.forEach { configurationName ->
            sourceProject.configurations.findByName(configurationName)
                ?.dependencies
                ?.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                ?.forEach { dependency: org.gradle.api.artifacts.ProjectDependency ->
                    val targetPath = dependency.path
                    if (targetPath !in allowedProductionProjectDependencies.getValue(sourceProject.path)) {
                        violations += ("${sourceProject.path} must not depend on $targetPath "
                                + "through production configuration $configurationName")
                    }
                    if (sourceProject.path in coreModulePaths && targetPath in deliveryModulePaths) {
                        violations += "${sourceProject.path} must not depend on delivery module $targetPath"
                    }
                    if (sourceProject.path == ":muyun-web-adapter"
                            && (targetPath in webDeliveryModulePaths || targetPath == ":muyun-boot")) {
                        violations += ":muyun-web-adapter must not depend on $targetPath"
                    }
                    if (sourceProject.path in webDeliveryModulePaths && targetPath == ":muyun-boot") {
                        violations += "${sourceProject.path} must not depend on application host :muyun-boot"
                    }
                }
        }
    }
    moduleDependencyViolations.set(violations)
}

integrationTestTasks.forEach { integrationTest ->
    integrationTest.configure {
        mustRunAfter(unitTestTasks)
    }
}

tasks.register("verifyAll") {
    description = "Runs all backend unit and integration tests across subprojects."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(unitTestTasks)
    dependsOn(integrationTestTasks)
    dependsOn("verifyModuleBoundaries")
}
