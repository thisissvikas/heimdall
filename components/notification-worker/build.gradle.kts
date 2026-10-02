plugins { id("heimdall.application-conventions") }
dependencies { implementation(project(":components:result-processor")); implementation(libs.okhttp) }
