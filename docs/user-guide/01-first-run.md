# Your first run

This tutorial runs the checked-in checkout example. It creates an asynchronous job, polls until the job is ready, checks its items with declarative and JavaScript assertions, and deletes the job. Its credentials come from a local Vault instance.

## 1. Check prerequisites

Install Java 25, Node.js 24.19.0, Git, and Docker with Compose. Start Docker. Run every command below from the repository root, the directory containing `gradlew` and `plan.md`.

```sh
java -version
node --version
docker compose version
```

The Gradle wrapper downloads the pinned Gradle version. The first build also downloads dependencies and test images. Ensure Docker has enough memory for PostgreSQL, Kafka, Temporal and the Java services; 8 GB allocated to Docker is a useful local starting point.

## 2. Validate and build

```sh
./gradlew validateConfig
./gradlew build :cli:installDist
```

`build` runs unit, integration and end-to-end checks and packages the services. Integration tests use disposable Docker containers. A successful validation reports the approved example's monitor count and bundle digest.

## 3. Start dependencies

```sh
docker compose -f deploy/local/compose.yaml up -d postgres temporal kafka vault storage fixture
```

The local Compose file uses fixture identities and encryption keys. Real deployments need operator-provisioned credentials and keys.

The fixture is available at `http://localhost:8090/health` from your laptop. Its container name is `fixture`; the checked-in staging environment uses `http://fixture:8090` so the runner can reach it inside Compose.

## 4. Start Heimdall

```sh
docker compose -f deploy/local/compose.yaml up -d --build
curl --fail http://localhost:8080/actuator/health
curl --fail -H 'Authorization: Bearer fixture-admin-token' http://localhost:8080/v1/status/reconciliation
```

The API can take a short time to start. Repeat the health check until it succeeds. Reconciliation should report `status: applied`, with matching `activeConfigurationCommit` and `schedulesAppliedCommit`. If it reports an error, use [troubleshooting](09-results-and-troubleshooting.md) before starting a run.

The local tokens map to the example's centrally defined roles:

| Token | Allowed actions |
|---|---|
| `fixture-runner-token` | Run and cancel checkout staging monitors at `local`, using the granted secret prefix |
| `fixture-viewer-token` | Read checkout staging history and steps |
| `fixture-admin-token` | Read reconciliation, audit and sensitive artifact APIs; platform administration |

Seed Vault after the stack has started:

```sh
docker compose -f deploy/local/compose.yaml exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=fixture-vault-root vault vault kv put secret/payments/orders clientId=fixture-client clientSecret=fixture-client-secret probeKey=fixture-probe-key
```

These are disposable fixture credentials. Seeding after startup ensures a recreated Vault container has the required values.

## 5. Execute the deployment suite

```sh
export HEIMDALL_TOKEN=fixture-runner-token
cli/build/install/cli/bin/cli run \
  --url http://localhost:8080 \
  --reference payments/checkout/deployment \
  --environment staging \
  --locations local \
  --idempotency-key checkout-tutorial-1 \
  --wait \
  --timeout-seconds 1800 \
  --json .local/checkout-result.json \
  --junit .local/checkout-junit.xml
```

The CLI reads `HEIMDALL_TOKEN` by default. It prints the run ID and waits for persisted results. The fixture usually takes around a minute because the poll interval is 30 seconds. Expect final `status: passed`, `publication: published`, and `cleanup: passed` for the `local` outcome. The exit code is zero only when the completed deployment gate passes.

Repeat the command with `checkout-tutorial-1` to retrieve the same logical run. Change the key, for example to `checkout-tutorial-2`, to create a new run. Keep a submission's body and key together when recovering from an uncertain network response.

## 6. Inspect what happened

Copy the printed ID into the shell variable below:

```sh
export RUN_ID='paste-the-run-id'
curl --fail -H 'Authorization: Bearer fixture-viewer-token' "http://localhost:8080/v1/runs/$RUN_ID"
curl --fail -H 'Authorization: Bearer fixture-viewer-token' "http://localhost:8080/v1/runs/$RUN_ID/steps"
```

Steps show status codes, timings and assertion outcomes. They omit OAuth tokens, request credentials, extracted sensitive state and response bodies. The source YAML contains Vault references, which the HTTP runner resolves at execution time. See [the secret walkthrough](05-secrets-and-authentication.md).

The Temporal development UI at `http://localhost:8233` shows durable workflow history. It is useful for developers; the Heimdall API is the user-facing result contract.

## 7. Stop the tutorial

```sh
docker compose -f deploy/local/compose.yaml down
```

This retains the PostgreSQL and Temporal named volumes. `down --volumes` deletes the local tutorial's stored runs and workflows. Vault dev mode and fixture target data are transient; seed Vault again after restarting it.

Continue with [your first monitor](02-first-monitor.md).
