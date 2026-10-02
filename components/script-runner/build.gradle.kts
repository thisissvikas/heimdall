plugins { id("heimdall.java-conventions") }
dependencies { api(project(":libraries:contracts")) }
tasks.register<Exec>("scriptTest") {
    commandLine(System.getenv("HEIMDALL_NODE") ?: "node", "--test", "sandbox.test.mjs", "service.test.mjs")
    inputs.files("sandbox.mjs", "sandbox.test.mjs", "service.mjs", "service.test.mjs")
    outputs.cacheIf { false }
}
tasks.check { dependsOn("scriptTest") }
