# API implementation status

The repository contains a functional API-synthetics implementation and automated unit, integration and end-to-end verification. `plan.md` remains the production architecture and acceptance target. This page distinguishes executable features from outstanding production work.

## Implemented and verified

- Strict Git-owned YAML, application script syntax validation, ownership/catalog/reference validation, typed snapshots and pinned run configuration.
- Scoped OIDC/RBAC, exact GitHub CI trust, Vault references, encrypted state and S3 diagnostics.
- Transactional idempotent admission, stable Temporal workflow identities, durable dispatch and cancellation recovery.
- HTTP chaining, JSON/text/form bodies, URL encoding, assertions, JSON Schema, CEL, extraction, bounded explicit retries and OAuth 401 refresh.
- Durable waits/polls, conditions, bounded loops, isolated parallel state, cleanup and history replay.
- Public/private SSRF policy and exact private host grants, with redirect rejection and disabled hidden request retries.
- Stable Kafka publication, partitioned PostgreSQL events/attempts, deduplication, monotonic projections and daily aggregates.
- ShedLock for scheduled central jobs and transactional claims for notifications; multi-instance tests exercise both.
- Interval schedule reconciliation, paused schedules and skip/bufferOne overlap policy.
- Scheduled consecutive-failure/recovery alerts and signed webhook delivery with retries.
- Run/history/steps/artifacts/events/summary/status/audit/location APIs, CLI JSON/JUnit gates and a GitHub OIDC action.
- Local Compose, component images, central/location Helm templates and a separate script service with a gVisor deployment policy.

Unit tests cover validation, expressions/assertions/templates, RBAC, trust isolation, IDs and deployment gates. Integration tests use real HTTP I/O, PostgreSQL, Temporal test history and a real Temporal container for worker replacement; provider tests exercise Vault/S3 protocols and encryption tampering. End-to-end tests run a real Spring API, Temporal execution, target HTTP requests, JavaScript, Kafka and PostgreSQL together. Node tests cover script escape surfaces, execution/output bounds and service recovery.

## Functional work still planned

- Cron/calendar schedules, maintenance windows, central templates and location groups.
- Target mTLS, multipart/binary uploads, richer cookie handling and securely validated redirect following.
- Quorum, latency and missing-coverage alerts, incident correlation and GitHub Checks publication.
- Runtime location capacity/agent health enforcement, fair per-team/location quotas and full coverage health APIs.
- Full OpenTelemetry tracing/metrics/dashboards and the complete replaceable-provider configuration catalog.
- Complete automated retention for run metadata, encrypted state/checkpoints and object-storage artifacts.
- Broader Kafka/Temporal workload authentication and TLS deployment settings, key rotation migrations and operational recovery tooling.

## Production acceptance still required

The plan's 500-monitor/three-location load target, 1,000 simultaneous waiting workflows, controlled outage/recovery benchmarks, p95 history latency, restore drills and Linux/gVisor isolation proof have not been established by the functional test suite. Helm templates do not provision external services, install a CNI/gVisor, configure ingress identity or prove workload connectivity/security. Operators must complete those checks before production rollout.

Browser synthetics are intentionally excluded from the current build.
