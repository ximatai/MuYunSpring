dependencies {
    api(project(":muyun-iam"))
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
