plugins { id("heimdall.java-conventions") }
dependencies { api(project(":libraries:contracts")); implementation(project(":libraries:assertions")); implementation(libs.yaml); testImplementation(testFixtures(project(":libraries:contracts"))) }
tasks.register<JavaExec>("validateConfig") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.heimdall.configuration.Validate"
    args(rootProject.projectDir.absolutePath)
    inputs.dir(rootProject.file("config")); inputs.file(rootProject.file(".github/CODEOWNERS"))
}
