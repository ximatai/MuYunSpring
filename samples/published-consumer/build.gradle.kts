plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val muyunRepository = providers.gradleProperty("muyunRepository").orNull
    ?: error("Provide -PmuyunRepository=<published Maven repository>")
val muyunVersion = providers.gradleProperty("muyunVersion").orNull
    ?: error("Provide -PmuyunVersion=<MuYunSpring version>")

repositories {
    maven { url = uri(muyunRepository) }
    mavenCentral()
}

dependencies {
    implementation(platform("net.ximatai.muyun.spring:muyun-spring-bom:$muyunVersion"))
    implementation("net.ximatai.muyun.spring:muyun-spring-boot-starter")
    runtimeOnly("org.postgresql:postgresql")
}
