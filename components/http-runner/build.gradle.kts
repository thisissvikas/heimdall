plugins { id("heimdall.application-conventions") }
dependencies {
    api(project(":libraries:workflow-engine")); implementation(project(":libraries:assertions")); implementation(project(":components:result-processor")); implementation(project(":components:script-runner"))
    implementation(libs.okhttp); testImplementation(libs.mockwebserver); testImplementation(libs.temporal.testing)
    testImplementation(testFixtures(project(":libraries:contracts")))
}
