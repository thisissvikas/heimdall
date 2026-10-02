# Results and troubleshooting

Start with the run, then inspect its steps. A run records the configuration commit and bundle digest that it actually executed, together with any application deployment metadata.

## Read one run and its steps

```sh
curl --fail -H "Authorization: Bearer $HEIMDALL_TOKEN" "http://localhost:8080/v1/runs/$RUN_ID"
curl --fail -H "Authorization: Bearer $HEIMDALL_TOKEN" "http://localhost:8080/v1/runs/$RUN_ID/steps"
```

Every monitor/location outcome has its own status, cleanup result and safe error. Step attempts include status code, timing breakdown, assertion outcomes, error classification and recovery metadata. An OAuth 401 recovery can leave a failed initial observation followed by a successful final observation; inspect the final run result and its recovered flag together.

| Status | Meaning |
|---|---|
| `queued` | Admission succeeded; execution or its result has not started arriving |
| `running` | The workflow is executing |
| `waiting` | It is in a durable wait or poll interval |
| `passed` | The journey and cleanup succeeded |
| `failed` | A step, assertion, bounded budget or cleanup failed; inspect its classification |
| `timed_out` | The monitor or poll reached its deadline |
| `cancelled` | A Heimdall cancellation request was handled |
| `error` | The workflow or activity could not execute normally |

`publication: pending` means persisted terminal coverage is incomplete. `publication: published` means all expected monitor/location terminal outcomes have been ingested. A deployment gate requires complete published coverage, passed outcomes and passed cleanup.

## Find recent runs

Use UTC ISO timestamps and a range no longer than 30 days:

```sh
curl --fail --get -H "Authorization: Bearer $HEIMDALL_TOKEN" \
  --data-urlencode from=2026-10-02T00:00:00Z \
  --data-urlencode to=2026-10-03T00:00:00Z \
  --data-urlencode limit=50 \
  http://localhost:8080/v1/runs
```

Replace the dates with your desired range. The response contains `items` and `nextCursor`. Send the next cursor with the same range to fetch another page; follow it even when permission filtering leaves a page empty. Maximum page size is 100.

Daily pass/failure summaries use `/v1/results/summary` with `monitor`, `environment`, `from` and `to`, up to 90 days. Detailed events and attempts are retained for seven days by default; daily aggregates have longer retention. The operator guide explains the current retention boundary.

## Watch or cancel a run

```sh
curl --no-buffer -H "Authorization: Bearer $HEIMDALL_TOKEN" "http://localhost:8080/v1/runs/$RUN_ID/events"
curl --fail -X POST -H "Authorization: Bearer $HEIMDALL_TOKEN" "http://localhost:8080/v1/runs/$RUN_ID/cancel"
```

The event endpoint sends persisted run updates over Server-Sent Events for up to 60 seconds. Reconnect or poll the run afterward if it remains active. Cancellation returns HTTP 202 after recording the intent; cleanup and final result publication are asynchronous. Recovery retries an accepted cancellation after a control-plane outage.

## Read redacted failure artifacts

```sh
curl --fail -H 'Authorization: Bearer fixture-admin-token' "http://localhost:8080/v1/runs/$RUN_ID/artifacts"
```

The list returns artifact IDs and metadata. Fetch a listed ID from `/v1/runs/{run-id}/artifacts/{artifact-id}`. The current artifacts contain encrypted-at-rest, redacted attempt diagnostics, including safe timing and assertion metadata. They do not contain raw bodies or request credentials. Artifact access requires the separate `artifacts` grant; the tutorial Viewer and Runner do not have it.

## Common problems

| Symptom | What to check |
|---|---|
| HTTP 401 | Missing, invalid, expired, wrong-issuer or wrong-audience bearer token |
| HTTP 403 | Scope, environment, location, production, secret or artifact grant; exact CI trust claims |
| HTTP 400 | Unknown fields/references, undeclared parameter, type mismatch, disallowed location or reused key with different request |
| HTTP 503 | No active configuration, unavailable dependency or exhausted admission quota |
| New YAML has no effect | `validateConfig`, protected-branch merge, then reconciliation status; an existing run keeps its pinned revision |
| Run remains queued | HTTP/workflow workers for the correct queue, Temporal connectivity, Kafka consumer and result publication |
| Secret failure | Vault data path and field, Vault workload token/policy, central secret prefix, environment and location grant |
| TLS/DNS/network failure | Final URL, exact private allowed host, DNS addresses, certificate validity and network routing |
| Poll timed out | Inspect observed status/assertions, `until`, `failWhen`, poll deadline and monitor deadline |
| Script fails | Valid default function, JSON response shape, declared outputs, synchronous logic and sandbox limits |
| Ambiguous request outcome | Check the target using its idempotency/correlation ID before starting another mutation |
| Cleanup failed | Whether creation/extraction succeeded, target delete behavior and 30-second cleanup deadline |

For the local stack:

```sh
docker compose -f deploy/local/compose.yaml ps
docker compose -f deploy/local/compose.yaml logs --tail=100 control-plane workflow-worker http-runner result-processor
```

Admin users can inspect `/v1/status/reconciliation` and bounded `/v1/audit-events`. Reconciliation preserves the last valid active configuration when a newer revision fails validation. Configuration activation and schedule application are reported separately so partial application remains visible and can be retried.

If your next question is an exact field name or endpoint, use the [configuration reference](../configuration-reference.md) or [API reference](../api-reference.md).
