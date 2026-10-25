plugins {
    id("java-test-fixtures")
}

dependencies {
    api(project(":muyun-ability"))
    api(project(":muyun-platform"))
    implementation(libs.jackson.databind)
    compileOnly(libs.muyun.database.spring.boot.starter)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.muyun.database.spring.boot.starter)
    testImplementation(libs.postgresql)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesImplementation(libs.spring.boot.starter.test)
}
