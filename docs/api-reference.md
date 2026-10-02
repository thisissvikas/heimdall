# API reference

Use HTTPS in shared environments. Authenticated endpoints accept `Authorization: Bearer <token>`. The local tutorial maps explicit fixture tokens to central roles. Production validates OIDC issuer, signature and audience, with separate exact GitHub CI trust mapping.

## Endpoints

| Method and path | Purpose | Access |
|---|---|---|
| `POST /v1/runs` | Admit approved monitors/suites | Runner grant for every selected scope/environment/location |
| `GET /v1/runs/{id}` | Run and regional outcomes | Scoped read |
| `GET /v1/runs` | Bounded, cursor-paged history | Scoped read, filtered per run |
| `POST /v1/runs/{id}/cancel` | Record cancellation intent | Scoped cancel |
| `GET /v1/runs/{id}/steps` | Redacted step attempts, max 1000 | Scoped read |
| `GET /v1/runs/{id}/artifacts` | Diagnostic metadata | Read plus artifact grant |
| `GET /v1/runs/{id}/artifacts/{artifact}` | Diagnostic JSON | Read plus artifact grant |
| `GET /v1/runs/{id}/events` | Persisted updates via SSE, max 60 seconds | Scoped read rechecked during streaming |
| `GET /v1/results/summary` | Daily pass/failure counts | Read for monitor/environment/locations |
| `GET /v1/locations` | Visible central locations | Scoped read |
| `GET /v1/status/reconciliation` | Desired/active/schedule revisions and errors | Platform administrator |
| `GET /v1/audit-events` | Bounded audit history | Platform administrator |
| `GET /v1/agents` | Registered agent status | Platform administrator |
| `POST /v1/integrations/agents/register` | Heartbeat approved private agent identity | Exact `agent:{id}` subject and location role grant |
| `POST /v1/integrations/github/webhook` | Signed protected-branch push notification | Valid HMAC and configured repository/branch |
| `GET /actuator/health` | Process health | Unauthenticated |

## Start a run

```sh
curl --fail -X POST http://localhost:8080/v1/runs \
  -H 'Authorization: Bearer fixture-runner-token' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-api-tutorial-1' \
  --data '{"references":["payments/checkout/deployment"],"environment":"staging","locations":["local"],"parameters":{}}'
```

HTTP 202 returns `{"id":"..."}` and a `Location` header. It confirms durable admission, not successful execution. If Temporal is temporarily unavailable after admission, dispatch recovery starts the persisted run later.

| JSON field | Meaning |
|---|---|
| `references` | One to 100 approved monitor or suite identities |
| `environment` | Required declared application environment |
| `locations` | Optional permitted subset; defaults to monitor locations |
| `parameters` | Optional declared parameter overrides, validated per monitor |
| `deployment` | Optional `{commit, artifactDigest, repository}` metadata for the tested application |

The idempotency key is required, nonblank and at most 200 characters. It is scoped to the authenticated subject. The same key/body returns the same ID across concurrent API instances. A different body with that key is rejected. The configuration snapshot is pinned at first admission. Persistent YAML/configuration upload fields are rejected.

## Run response

Fields include `id`, `subject`, `createdAt`, `environment`, `configCommit`, `bundleDigest`, `status`, `publication`, `outcomes` and optional `deployment`. Each outcome has `monitorRef`, `location`, `status`, `cleanup`, `error` and `sequence`. See [result meanings](user-guide/09-results-and-troubleshooting.md).

Cancel returns HTTP 202 with `cancellationRequested: true`. The intent is persisted before delivery. A worker/control-plane outage can delay cleanup and final results; poll afterward.

## Query bounds

`GET /v1/runs` requires UTC `from` and `to` with a positive range up to 30 days. Optional `limit` is 1–100, default 50. Optional `cursor` comes from the previous page's `nextCursor`; keep the same bounds while paging.

Summary requires `monitor`, `environment`, `from`, `to`, bounded to 90 days. Audit requires `from`, `to`, up to 365 days, and `limit` 1–100. Query ranges are half-open at the timestamp level; summaries group UTC calendar days.

## Agent heartbeat

```json
{"id":"checkout-agent-1","location":"checkout-private","runtime":"api-runner/0.1.0"}
```

The verified subject must be `agent:checkout-agent-1`, with the location centrally granted. This registration reports metadata; it does not deploy or enroll an arbitrary runner. Runners poll Temporal task queues configured by their `LOCATION` environment variable.

## GitHub webhook

Send the original push JSON body with `X-Hub-Signature-256: sha256=<hex HMAC>`, `X-GitHub-Delivery` and `X-GitHub-Event: push`. The receiver verifies the configured repository and protected branch, persists delivery deduplication, and returns 202. Periodic polling resolves the current protected-branch head, including missed notifications; the payload cannot select an arbitrary configuration commit.

## Errors

Application errors return a JSON `{code, message}` object. Common codes are `invalid_request` (400), `forbidden` (403), `not_found` (404), and `unavailable` (503). Authentication failure returns 401. Treat HTTP 202 as admission/cancellation acknowledgement and inspect the run for its eventual outcome.
