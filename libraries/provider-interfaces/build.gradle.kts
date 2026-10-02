plugins { id("heimdall.java-conventions") }
dependencies { api(project(":libraries:contracts")); implementation(libs.aws.s3); testImplementation(testFixtures(project(":libraries:contracts"))) }
