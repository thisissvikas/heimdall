plugins { `java-library`; jacoco; id("com.diffplug.spotless") }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) }; withSourcesJar() }
val googleJavaFormatVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("google-java-format").get().requiredVersion
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(googleJavaFormatVersion)
    }
}
// Format handwritten sources before compiling any source set.
tasks.withType<JavaCompile>().configureEach { dependsOn(tasks.named("spotlessApply")) }
// Keep check/build deterministic when formatting and verification are scheduled together.
tasks.withType<com.diffplug.gradle.spotless.SpotlessCheck>().configureEach {
    mustRunAfter(tasks.named("spotlessApply"))
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.compilerArgs.add("-parameters") }
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "768m"
    maxParallelForks = 1
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("heimdall.root", rootProject.projectDir.absolutePath)
    environment("HEIMDALL_NODE", System.getenv("HEIMDALL_NODE") ?: "node")
    // Integration tests exercise external I/O; never reuse their results from a build cache.
}
tasks.test { useJUnitPlatform { excludeTags("integration", "e2e") } }
val integrationTest by tasks.registering(Test::class) {
    description = "Real adapter and Temporal integration tests"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    outputs.cacheIf { false }
    shouldRunAfter(tasks.test)
}
val endToEndTest by tasks.registering(Test::class) {
    description = "API to target to persisted results end-to-end tests"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    outputs.cacheIf { false }
    shouldRunAfter(integrationTest)
}
tasks.check { dependsOn(integrationTest, endToEndTest) }
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest, endToEndTest)
    executionData(fileTree(layout.buildDirectory) { include("jacoco/*.exec") })
    reports { xml.required = true; html.required = true }
}
