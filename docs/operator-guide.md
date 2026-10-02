# Operating Heimdall

Team authors define monitors. Operators register teams, projects, locations, trust and permissions, provision external services and workload identities, and deploy the platform. Start with [the local tutorial](user-guide/01-first-run.md) before configuring a shared environment.

## Build and verify

Use Java 25, Node.js 24.19.0, Git and Docker. Versions and dependency locks are checked in. `HEIMDALL_NODE` can point to a specific Node executable when it is not on PATH.

```sh
./gradlew validateConfig
./gradlew check
./gradlew build :cli:installDist
```

`check` includes formatting, configuration validation, unit tests, adapter/Temporal/PostgreSQL integration tests, API-to-target-to-Kafka-to-history end-to-end tests, Node sandbox checks and GitHub action tests. Docker must be running. Test reports are under each module's `build/reports/tests/`; `./gradlew jacocoTestReport` creates Java coverage reports. These checks verify the functional implementation; they do not establish the plan's production throughput, disaster recovery or Kubernetes isolation acceptance targets.

Package each Java component, then build its image:

```sh
docker build -f deploy/Dockerfile --build-arg COMPONENT=control-plane -t heimdall/control-plane:0.1.0 .
docker build -f deploy/Dockerfile --build-arg COMPONENT=workflow-worker -t heimdall/workflow-worker:0.1.0 .
docker build -f deploy/Dockerfile --build-arg COMPONENT=http-runner -t heimdall/http-runner:0.1.0 .
docker build -f deploy/Dockerfile --build-arg COMPONENT=result-processor -t heimdall/result-processor:0.1.0 .
docker build -f deploy/Dockerfile --build-arg COMPONENT=notification-worker -t heimdall/notification-worker:0.1.0 .
docker build -f components/script-runner/Dockerfile.service -t heimdall/script-service:0.1.0 components/script-runner
```

## Required services and settings

Provide PostgreSQL, Temporal, Kafka, S3-compatible storage and Vault-compatible secrets. Create the S3 bucket and `heimdall-results` Kafka topic before starting shared workloads. Configure Kafka replication, retention and ACLs for your reliability requirements. Workload credentials belong in deployment secrets, not team YAML.

| Workload | Required or typical settings |
|---|---|
| All Java data clients | `JDBC_URL`, `DB_USER`, `DB_PASSWORD` |
| Temporal clients | `TEMPORAL_ADDRESS`, `TEMPORAL_NAMESPACE` |
| Workflow/result workers | `KAFKA_BOOTSTRAP` |
| Control plane | `GIT_URL`, `GIT_BRANCH`, OIDC settings, `STATE_KEY`, S3 settings |
| HTTP runner | `LOCATION`, `STATE_KEY`, Vault settings, S3 settings, `SCRIPT_ENDPOINT` |
| Notification worker | `NOTIFICATION_DESTINATIONS` JSON reference→HTTPS URL map, base64 `NOTIFICATION_KEY` of at least 32 bytes |

OIDC settings are `OIDC_ISSUER`, `OIDC_JWKS`, `OIDC_AUDIENCE` (default `heimdall`). GitHub settings are `GITHUB_REPOSITORY`, `GITHUB_WEBHOOK_SECRET` and the protected `GIT_BRANCH`. Production must use the default OIDC mode; the explicit `local` Spring profile is for fixtures only.

`STATE_KEY` is a base64-encoded random 32-byte key used for authenticated encryption of state and artifacts. Manage key backups and rotation carefully: changing it makes previously encrypted data unreadable unless you migrate/re-encrypt that data. `VAULT_ADDRESS` and `VAULT_TOKEN` identify the HTTP runner's secret workload. Its Vault policy should cover only that location's required paths.

S3 settings are `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET` and the AWS SDK's workload credential chain. Provide short-lived credentials or a workload role. The current HTTP runner defaults to a local endpoint when none is supplied; explicitly configure your production endpoint. Provider adapters are replaceable through the interfaces in `libraries/provider-interfaces`.

The control plane can use `HEIMDALL_OPA_URL` for an OPA authorization endpoint. Built-in scoped RBAC is applied before OPA; OPA failure denies the operation. Kafka authentication/TLS and Temporal workload authentication are not exposed comprehensively yet; see [implementation status](implementation-status.md) before deploying across trust boundaries.

## Git governance and runtime permissions

Replace `@your-org` fixture owners with your organization in the central team catalog and `.github/CODEOWNERS`. Protect the configured branch with required PR checks and code-owner review, dismiss stale approvals and disable ordinary bypass/direct pushes. A central registration change and a CODEOWNERS change need platform ownership.

Run authorization is independent of file ownership. Runner roles grant exact environments, locations, optional production permission and secret prefixes. Viewer roles allow scoped reads. `PlatformAdministrator` is also scoped; sensitive artifacts need the explicit artifact grant. Prefix matching honors scope boundaries, so `payments/checkout` does not include `payments/checkout-other`.

The Git reconciler fetches the configured repository/branch itself; webhook JSON cannot supply an arbitrary source tree. Invalid configuration leaves the last valid snapshot active. The status API reports desired revision, active configuration revision, schedule-applied revision and the last error separately.

## Multi-instance coordination

Run at least two control-plane instances for availability. Reconciliation, pending dispatch/cancellation recovery and retention use ShedLock's PostgreSQL provider with database time and a shared `control.shedlock` table. Do not add process-local locks as a replacement for database coordination.

Run admission serializes each subject/idempotency key transactionally, and uses a database admission lock for the global active-run quota. Temporal workflow IDs prevent duplicate starts across dispatch retries. Result ingestion deduplicates stable event identities, rejects older sequences and preserves terminal states. Notification workers claim rows with `FOR UPDATE SKIP LOCKED`, so concurrent workers do not select the same intent at once. Delivery is at least once: receivers should deduplicate `X-Heimdall-Delivery` in case a worker crashes after delivery but before committing acknowledgement.

ShedLock leases have bounded durations; monitor job runtimes and choose lease limits appropriate to your workload. Temporal handles durable workflow ownership and timers; it does not need a ShedLock around every activity.

## Kubernetes deployment

`deploy/helm/central` deploys central Java services. `deploy/helm/location` deploys one regional HTTP runner pool and a separate script service. Chart values require image repositories, immutable image digests and names of existing deployment secrets. Provide your ingress, TLS termination, infrastructure endpoints, workload policies and node runtime installation separately.

```sh
helm template heimdall deploy/helm/central -f your-central-values.yaml
helm template checkout-private deploy/helm/location -f your-location-values.yaml
```

Create reviewed values for your environment before installation. The location ID must match central YAML and the runner's `LOCATION`. The location chart requires an installed gVisor `RuntimeClass` and a CNI that enforces NetworkPolicy. Script pods receive no database/Vault credentials, have no service-account token, run without privileges on a read-only root filesystem, and have CPU/memory bounds. Only their HTTP runner pods may connect; all sandbox egress is denied. Enable authenticated transport, such as your service mesh's mTLS, between these workloads as part of cluster policy.

The local Compose stack does not prove gVisor or Kubernetes policy enforcement. A real Linux deployment needs the isolation verification in the plan. Do not mount the host Docker socket into an HTTP runner. The alternative `SCRIPT_IMAGE` adapter invokes a digest-pinned gVisor Docker container on an operator-controlled host; the Kubernetes service mode uses `SCRIPT_ENDPOINT` instead.

## Alerts and notification delivery

Current scheduled alerts track consecutive failure and recovery per monitor/environment/location. Create an application policy named `default` and a registered `webhookRef`. Set `NOTIFICATION_DESTINATIONS` to map that reference to an operator-approved HTTPS endpoint. Verify `X-Heimdall-Signature` as HMAC-SHA256 over the original request body using `NOTIFICATION_KEY`, and deduplicate the delivery header.

Failed deliveries retry with bounded exponential backoff. Deployment/on-demand runs do not advance scheduled failure counters. Quorum, latency and missing-coverage alert policies and GitHub Checks delivery remain planned.

## Retention and recovery

The hourly retention task creates the next day's UTC event/attempt partitions and drops partitions older than seven days when they are not needed by active runs. It retains 90-day daily aggregates, 365-day audit events and 30-day webhook delivery deduplication. Run summaries, snapshots, idempotency mappings, encrypted checkpoints and object-storage lifecycle cleanup need an operational policy before production; their complete automated retention is not implemented yet.

Back up PostgreSQL, object storage, Git configuration and encryption key material coherently. Temporal persistence is an independent recovery dependency. Prove replay, restore ordering and key access before treating a restored deployment as promotion-authoritative. The checked-in Temporal development server uses SQLite and is for local/CI use; production should use an appropriately operated Temporal service.

Monitor reconciliation errors, pending runs, publication backlog, Kafka consumer lag, partition maintenance, notification retry backlog and runner availability. The current runtime exposes Spring health/basic metrics but does not yet provide the full planned OpenTelemetry dashboards or capacity acceptance evidence.
