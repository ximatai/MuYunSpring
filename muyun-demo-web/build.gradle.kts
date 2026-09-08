configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson-test")
    exclude(group = "org.springframework.boot", module = "spring-boot-jackson")
}

dependencies {
    api(project(":muyun-demo"))
    implementation(project(":muyun-web-adapter"))
    implementation(project(":muyun-platform-web"))
    implementation(libs.spring.boot.starter.web)

    testImplementation(project(":muyun-boot"))
    testImplementation(project(":muyun-iam"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
