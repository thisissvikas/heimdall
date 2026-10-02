pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "heimdall"
includeBuild("build-logic")
include("libraries:contracts", "libraries:configuration", "libraries:assertions", "libraries:provider-interfaces", "libraries:workflow-engine", "libraries:telemetry")
include("components:control-plane", "components:workflow-worker", "components:http-runner", "components:script-runner", "components:result-processor", "components:notification-worker", "cli")
