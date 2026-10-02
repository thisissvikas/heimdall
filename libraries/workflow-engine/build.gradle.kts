plugins { id("heimdall.java-conventions") }
dependencies { api(project(":libraries:contracts")); api(libs.temporal); testImplementation(libs.temporal.testing); testImplementation(libs.testcontainers.core); testImplementation(testFixtures(project(":libraries:contracts"))) }
