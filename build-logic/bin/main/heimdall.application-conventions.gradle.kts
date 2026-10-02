plugins { id("heimdall.java-conventions"); id("org.springframework.boot") }
dependencies { implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1")) }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName = "app.jar" }
