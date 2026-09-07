dependencies {
    api(project(":muyun-common"))

    implementation(libs.caffeine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.tx)
    testImplementation(libs.spring.context)
    testRuntimeOnly(libs.junit.platform.launcher)
}
