# Heimdall

**The Bifröst has a guardian. So do your APIs.**

Heimdall is a headless synthetic monitoring platform for testing real API workflows from global locations and private networks. Define checks in YAML, review changes in Git, and use the API to trigger runs, inspect results, and gate deployments.

Its specialty: **team-owned tests, durable workflows, and a watch across regions.** API synthetics are at the core, with an executor model extensible to Playwright browser tests.

## What the guardian watches

- **Whole journeys.** Chain requests, extract variables, branch on responses, refresh expired tokens, run parallel steps, and clean up afterward.
- **More than a green status code.** Assert headers, JSON paths, schemas, response times, and collection contents. Use isolated JavaScript for deeper checks—such as ensuring every returned item has a non-null field.
- **Jobs that take their sweet time.** Durable waits and polling handle long-running operations without reserving HTTP execution slots. Workflows resume after worker restarts. Even Asgard has asynchronous APIs.
- **Every chosen realm.** Run from selected geographic locations or inside private networks, with separate results and timings for each location.
- **The deployment gates.** GitHub Actions, OIDC, and JUnit results bring synthetics into promotion pipelines. Missing results cannot wave a deployment through.
- **The evidence.** Query run history, step attempts, assertion failures, and redacted diagnostics through APIs. Consecutive-failure and recovery notifications go to signed webhook destinations.

## One source of truth. Many watchtowers.

One monorepo holds platform components, central policies, and tests organized by **team → application → service**. Teams own their test directories; CODEOWNERS and required PR reviews govern changes.

```text
YAML → Pull request → Owner approval → Merge → Automatic reconciliation
```

Git owns configuration. APIs handle execution, integrations, results, and status. Every run pins its configuration revision, so a mid-flight edit cannot rewrite its instructions.

A central control plane coordinates independently deployed regional runners. Scoped runtime permissions, external secrets, and replaceable identity and authorization providers keep ownership explicit. Kubernetes deployments and pluggable integrations let Heimdall stand watch in your infrastructure.

## Under the helmet

| Layer | Stack |
|---|---|
| Core and build | Java 25, Spring Boot, Gradle multi-project build with Kotlin DSL |
| Durable orchestration | Temporal, managed or self-hosted |
| Requests and assertions | OkHttp, CEL, JSON Schema, JavaScript on Node.js in gVisor sandboxes |
| History and artifacts | PostgreSQL with partitioned history and aggregates; S3-compatible object storage |
| Result delivery | Apache Kafka |
| Deployment and telemetry | Kubernetes, Helm, OpenTelemetry, Prometheus-compatible metrics |
| Identity and secrets | OIDC, scoped RBAC, pluggable authorization, Vault-compatible secret providers |

Read the [architecture, configuration model, and execution semantics](plan.md) for the full picture.

New to Heimdall? Start with the [user guide](docs/user-guide/README.md) and its [first-run tutorial](docs/user-guide/01-first-run.md). The guide progresses from a single HTTP check to secrets, durable workflows, scripts, schedules, private locations and CI deployment gates. The [operator guide](docs/operator-guide.md) covers deployment and permissions; [implementation status](docs/implementation-status.md) records supported features and outstanding production work.

## Repository structure

Start with `config/` to understand what Heimdall monitors, `components/` to see how the platform runs, and `libraries/` for the shared logic those components use.

```text
heimdall/
├── config/                     # Git-owned monitoring configuration
│   ├── central/                # Platform limits, team/project catalogs, locations, role bindings
│   └── teams/                  # Checks and defaults owned by each team and application
├── components/                 # Runtime components and execution adapters
│   ├── control-plane/          # REST API, Git reconciliation, schedules, run admission, retention
│   ├── workflow-worker/        # Hosts Temporal workflows and result-publication activities
│   ├── http-runner/            # Executes HTTP steps, authentication, and assertions per location
│   ├── script-runner/          # JavaScript sandbox, Java adapter, and container image
│   ├── result-processor/       # Consumes Kafka results; persists history and notification intents
│   └── notification-worker/    # Delivers queued notifications through signed webhooks with retries
├── libraries/                  # Reusable Java modules
│   ├── contracts/              # Shared configuration models, execution records, IDs, JSON helpers
│   ├── configuration/          # Parses, validates, and compiles YAML into a configuration snapshot
│   ├── workflow-engine/        # Workflow interpreter: branching, polling, retries, cleanup
│   ├── assertions/             # Response checks, expressions, and variable templates
│   ├── provider-interfaces/    # Integration contracts and adapters for secrets, authorization, storage
│   └── telemetry/              # Shared metrics helpers
├── cli/                        # Validate configuration and run deployment gates with JSON/JUnit output
├── tests/fixtures/             # Target API server and image used by platform tests
├── docs/                       # Supporting guides and platform comparisons
├── build-logic/                # Shared Gradle conventions for Java, formatting, tests, applications
├── gradle/                     # Dependency version catalog and Gradle wrapper files
├── .github/                    # CODEOWNERS rules for platform and team configuration
├── .vscode/                    # Workspace settings and recommended extensions
├── settings.gradle.kts         # Registers the modules in the multi-project build
├── build.gradle.kts            # Repository-wide build, validation, formatting, and test tasks
└── plan.md                     # Detailed architecture and execution design
```

Team configuration follows **team → application → service**. The checked-in checkout example shows where each kind of change belongs:

```text
config/teams/payments/
├── team.yaml                              # Team registration and defaults
└── apps/checkout/
    ├── app.yaml                           # Application registration, project, and defaults
    ├── environments/staging.yaml          # Environment-specific values and production flag
    ├── authentication/orders-oauth.yaml   # Reusable authentication definition
    ├── scripts/validate-order-items.js    # Application-owned JavaScript assertions
    ├── suites/deployment.yaml             # Groups monitors, environments, and locations for a run
    └── services/orders-api/
        ├── service.yaml                   # Service registration and defaults
        └── monitors/order-processing.yaml # API workflow steps, assertions, and cleanup
```

To trace a run, follow the control plane into the workflow worker, then the HTTP runner (which invokes the script sandbox when needed). Workflow result events go through Kafka to the result processor; the notification worker delivers the resulting notification intents. The workflow definitions live in `libraries/workflow-engine/`, while `components/workflow-worker/` wires them into Temporal.

Useful starting points:

- **Add or change a check:** read the [order-processing monitor](config/teams/payments/apps/checkout/services/orders-api/monitors/order-processing.yaml) and its [deployment suite](config/teams/payments/apps/checkout/suites/deployment.yaml).
- **Understand configuration updates:** start at [Reconciler.java](components/control-plane/src/main/java/dev/heimdall/control/Reconciler.java), then follow the compiler in `libraries/configuration/`.
- **Understand workflow behavior:** start at [ApiWorkflowImpl.java](libraries/workflow-engine/src/main/java/dev/heimdall/workflow/ApiWorkflowImpl.java), then follow the activities in `components/http-runner/`.

Within Java modules, `src/main/java/` contains implementation code, `src/main/resources/` contains runtime resources where needed, and `src/test/java/` contains unit, integration, and end-to-end tests. Shared Java test helpers live in `libraries/contracts/src/testFixtures/`; PostgreSQL migrations live in `components/result-processor/src/main/resources/db/migration/`. The monitors under `config/teams/` are the API checks Heimdall runs; `tests/fixtures/` supplies a target for testing Heimdall itself.

## Java formatting

All Java sources use Google Java Format through [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-gradle). Versions are pinned in `gradle/libs.versions.toml`, and every Java module inherits the configuration from `heimdall.java-conventions`.

Compilation automatically formats sources, including when running tests or building. To format the entire repository explicitly, run `./gradlew spotlessApply`. Run `./gradlew spotlessCheck` for a read-only formatting check; the root `check` task includes it as well.

VS Code uses the recommended **Spotless Gradle** extension (`richardwillis.vscode-spotless-gradle`) to format Java on save with the same Gradle configuration. Install it when prompted by the workspace recommendations. For IntelliJ IDEA, install the [Spotless Gradle plugin](https://plugins.jetbrains.com/plugin/18321-spotless-gradle) to use the same formatter.

*Keep the Bifröst open. Make your deployments earn passage.*
