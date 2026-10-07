dependencies {
    api(project(":muyun-common"))
    api(project(":muyun-ability"))
    api(libs.muyun.database.core)

    implementation(libs.spring.tx)
    implementation(libs.jackson.databind)
    compileOnly(libs.postgresql)

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
}
