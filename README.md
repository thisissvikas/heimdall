# Heimdall

**The Bifröst has a guardian. So do your APIs.**

Heimdall is a headless synthetic monitoring platform for testing real API workflows from global locations and private networks. Define checks in YAML, review changes in Git, and use the API to trigger runs, inspect results, and gate deployments.

Its specialty: **team-owned tests, durable workflows, and a watch across regions.** API synthetics are at the core, with an executor model extensible to Playwright browser tests.

## What the guardian watches

- **Whole journeys.** Chain requests, extract variables, branch on responses, refresh expired tokens, run parallel steps, and clean up afterward.
- **More than a green status code.** Assert headers, JSON paths, schemas, response times, and collection contents. Use isolated JavaScript for deeper checks—such as ensuring every returned item has a non-null field.
- **Jobs that take their sweet time.** Durable waits and polling handle long-running operations without reserving HTTP execution slots. Workflows resume after worker restarts. Even Asgard has asynchronous APIs.
- **Every chosen realm.** Run from selected geographic locations or inside private networks, with separate results and timings for each location.
- **The deployment gates.** GitHub Actions, OIDC, GitHub Checks, and JUnit results bring synthetics into promotion pipelines. Missing results cannot wave a deployment through.
- **The evidence.** Query run history, step attempts, assertion failures, and redacted artifacts through APIs. Failure, recovery, latency, and missing-coverage alerts sound the horn when needed.

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

*Keep the Bifröst open. Make your deployments earn passage.*
