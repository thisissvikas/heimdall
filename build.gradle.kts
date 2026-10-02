plugins { base }
allprojects { group = "dev.heimdall"; version = "0.1.0" }
for (name in listOf("spotlessApply", "spotlessCheck")) {
    val aggregate = tasks.register(name) {
        group = if (name == "spotlessApply") "formatting" else "verification"
        description = if (name == "spotlessApply") "Format all Java sources" else "Check Java formatting"
    }
    subprojects {
        pluginManager.withPlugin("com.diffplug.spotless") {
            val projectTask = tasks.named(name)
            aggregate.configure { dependsOn(projectTask) }
        }
    }
}
tasks.register("validateConfig") { dependsOn(":libraries:configuration:validateConfig") }
for (name in listOf("test", "integrationTest", "endToEndTest", "jacocoTestReport")) {
    val aggregate = tasks.register(name)
    subprojects {
        pluginManager.withPlugin("heimdall.java-conventions") {
            val projectTask = tasks.named(name)
            aggregate.configure { dependsOn(projectTask) }
        }
    }
}
tasks.register<Exec>("githubActionTest") {
    commandLine(System.getenv("HEIMDALL_NODE") ?: "node", "--test", "integrations/github-action/run.test.mjs")
    inputs.files("integrations/github-action/run.mjs", "integrations/github-action/run.test.mjs")
    outputs.cacheIf { false }
}
tasks.named("check") { dependsOn("githubActionTest", ":components:script-runner:scriptTest", "spotlessCheck", "validateConfig", "test", "integrationTest", "endToEndTest") }
val aggregateBuild = tasks.named("build") { dependsOn("check") }
subprojects {
    pluginManager.withPlugin("heimdall.java-conventions") {
        val projectBuild = tasks.named("build")
        aggregateBuild.configure { dependsOn(projectBuild) }
    }
}
