plugins { id("heimdall.application-conventions") }
dependencies {
    implementation(project(":libraries:configuration")); implementation(project(":libraries:workflow-engine")); implementation(project(":components:result-processor")); implementation(project(":libraries:telemetry"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.shedlock.spring); implementation(libs.shedlock.jdbc)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(project(":components:http-runner")); testImplementation(libs.testcontainers.postgres); testImplementation(libs.testcontainers.kafka)
    testImplementation(project(":components:script-runner"))
    testImplementation(project(":components:notification-worker"))
    testImplementation(libs.mockwebserver); testImplementation(libs.temporal.testing)
    testImplementation(testFixtures(project(":libraries:contracts")))
}
