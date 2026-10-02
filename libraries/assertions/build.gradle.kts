plugins { id("heimdall.java-conventions") }
dependencies { api(project(":libraries:contracts")); implementation(libs.cel); implementation(libs.jsonpath); implementation(libs.schema); testImplementation(testFixtures(project(":libraries:contracts"))) }
