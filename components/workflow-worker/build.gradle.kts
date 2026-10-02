plugins { id("heimdall.application-conventions") }
dependencies { implementation(project(":libraries:workflow-engine")); implementation(project(":components:result-processor")); implementation("org.springframework.boot:spring-boot-starter") }
