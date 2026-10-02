plugins { id("heimdall.java-conventions"); application }
dependencies { implementation(project(":libraries:configuration")); implementation(project(":libraries:contracts")) }
application { mainClass = "dev.heimdall.cli.Main" }
