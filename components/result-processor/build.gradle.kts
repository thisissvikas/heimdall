plugins { id("heimdall.application-conventions") }
dependencies {
    api(project(":libraries:provider-interfaces")); api(project(":libraries:workflow-engine"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core"); runtimeOnly("org.flywaydb:flyway-database-postgresql"); runtimeOnly("org.postgresql:postgresql")
    api("org.apache.kafka:kafka-clients")
    testImplementation(libs.testcontainers.postgres)
    testImplementation(testFixtures(project(":libraries:contracts")))
}
