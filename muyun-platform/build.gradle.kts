plugins {
    id("java-test-fixtures")
}

dependencies {
    api(project(":muyun-ability"))
    api(project(":muyun-dynamic"))

    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.poi.ooxml)
    implementation(libs.metadata.extractor)

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
    testImplementation(project(":muyun-iam"))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesImplementation(libs.spring.boot.starter.test)
}
