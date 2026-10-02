# Configuration reference

This describes the current API-only implementation. The compiler is strict: unknown fields, duplicate YAML keys, unsafe aliases, symbolic links, invalid ownership paths and unknown resource references fail validation. Validate the whole repository with `./gradlew validateConfig`; this also syntax-checks application JavaScript using Node.js.

## Resource envelope and identities

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: Monitor
metadata:
  name: order-processing
spec:
  # Fields determined by kind.
```

Names use lowercase letters, digits and hyphens, start with a letter and have a maximum of 63 characters. Resource `spec` is typed. Metadata supports `name` and optional `labels`.

| Kind | Location | Principal fields in `spec` |
|---|---|---|
| `Platform` | `config/central/` | `defaults`, `maxConcurrentRuns`, `maxSteps`, `maxResponseBytes` |
| `Teams` | `config/central/` | `entries`: team name → GitHub owner |
| `Projects` | `config/central/` | `entries`: project name → description |
| `Location` | `config/central/` | `network`, `allowedHosts`, `scopes`, `capabilities`, `enabled`, `capacity`, `region` |
| `RoleBinding` | `config/central/role-bindings/` | `subjects`, `role`, `scopes`, `environments`, `locations`, `secretPrefixes`, `production`, `artifacts` |
| `CiTrust` | `config/central/` | `repository`, `workflow`, `ref`, `environment`, `subject`, `suites` |
| `Team` | `config/teams/{team}/team.yaml` | `defaults` |
| `Application` | `.../apps/{app}/app.yaml` | `project`, `defaults` |
| `Service` | `.../services/{service}/service.yaml` | `defaults` |
| `Environment` | `.../apps/{app}/environments/` | `values`, `production`, `defaults` |
| `Authentication` | `.../apps/{app}/authentication/` | Auth type and secret references, described below |
| `Suite` | `.../apps/{app}/suites/` | `monitors`, `environments`, `locations` |
| `Monitor` | `.../services/{service}/monitors/` | Workflow fields, described below |
| `AlertPolicy` | Central or application-owned `alerting/` | `consecutiveFailures`, `webhookRef`; current policy selection uses the application's `default` policy |

Exactly one Platform, Teams and Projects resource is required. Team owners must match `.github/CODEOWNERS`. An application selects a registered project; a service must be registered before its monitors. Team resources cannot declare central role bindings, locations or CI trust.

## Monitor

| Field | Meaning |
|---|---|
| `type` | Required `api` |
| `environments` | Nonempty list of registered application environment names |
| `locations` | Nonempty list of central location names with this scope granted |
| `authRef` | Optional application-local Authentication resource name |
| `timeout` | Overall main journey deadline, maximum 24 hours |
| `maxExecutions` | Global main-step/poll budget, bounded by platform `maxSteps` |
| `parameters` | Name → `{type, required, defaultValue}`; string, number or boolean |
| `steps` | Nonempty ordered step list |
| `cleanup` | Optional ordered cleanup list; separate 30-second deadline and 100-step budget |
| `schedule` | Optional `{every, timezone, paused, overlap, jitter}`; interval minimum 10 seconds; overlap skip or bufferOne |
| `defaults` | Optional `{timeout, values}` |

Durations accept `ms`, `s`, `m`, `h`, `d` suffixes or ISO durations such as `PT30S`. Positive durations are required for waits, polls, deadlines, backoff and jitter. The fallback monitor timeout is five minutes; an omitted execution budget uses the platform limit.

Environment values merge platform, team, application and environment defaults, then explicit environment `values`. Service and monitor `defaults.values` override these in the execution context. Monitor timeout selection follows platform → team → application → service → monitor defaults, then explicit `timeout`. A request's `timeout` is independent and defaults to 30 seconds when omitted.

## Steps

All steps need unique `id` and supported `type` within their step list. `next` names an optional transition target; a backward edge requires `maxVisits` on its entry. Nested parallel lists have their own transition scope.

| Type | Type-specific fields |
|---|---|
| `http` | `request`, optional `assertions`, `extract`, `retry`, `postResponse` |
| `poll` | Same request/check fields plus `interval`, `timeout`, required CEL `until`, optional CEL `failWhen` |
| `wait` | `duration` |
| `set` | `values`: name → JSON-compatible value or template |
| `assert` | Boolean CEL `expression` |
| `condition` | Boolean CEL `expression`, optional `onTrue`, `onFalse` |
| `parallel` | `branches`: `{id, steps, outputs}`; one to eight, isolated state, nonconflicting outputs |
| `end` | End the current list |

`request` supports `method`, `url`, `headers`, `query`, one of `json`/`text`/`form`, and `timeout`. HTTP methods are GET, HEAD, POST, PUT, PATCH, DELETE and OPTIONS. Redirects and hidden connection retries are disabled. Maximum response bytes are enforced centrally.

`assertions` entries are `{source, path, operator, value, message}`. `source` is status, body, text, header, cookie, latency or certificateExpiry. Operators and units are listed in [requests and assertions](user-guide/03-requests-and-assertions.md).

`extract` maps a variable name to `{source, path}` using body, header or cookie. Missing/null values fail extraction.

`retry` is `{maxAttempts, statuses, backoff, idempotent}` with one to five attempts. Matching response statuses may retry; ambiguous transport failures are terminal. `postResponse` is `{script, outputs}` referencing an application-owned `scripts/*.js` file. Output names must be declared.

## Template and expression context

| Namespace | Available data |
|---|---|
| `env` | Merged non-secret environment/default values |
| `vars` | Declared parameter values, extracted values, set variables and declared outputs |
| `run` | `id`, `location` |
| `step` | `id`, `instanceId` for request templates |
| `secrets` | Explicit `${secrets.mount/path#field}` references resolved under central grants |
| `response` | CEL context: most recent `status`, `headers`, `body`, `text` |

Templates use `${namespace.key}`. Exact structured substitutions preserve JSON types. URL substitution percent-encodes dynamic data; `env.baseUrl` is the approved whole URL prefix. Missing or null template values fail execution. CEL expressions compile at configuration validation and must evaluate to booleans at runtime.

## Authentication

`type: basic` uses `usernameRef` and `passwordRef`; bearer and apiKey use `tokenRef`, with optional apiKey `header`. OAuth2 uses `tokenUrl`, `clientIdRef`, `clientSecretRef`, optional `scope`, and `maxRefresh` between zero and three. Set one refresh for the checkout example. Profile references are application-local; actual values come from Vault.

## Alert policy supported today

An application-owned policy named `default` can enable consecutive-failure and recovery notifications for **scheduled** runs:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: AlertPolicy
metadata:
  name: default
spec:
  consecutiveFailures: 3
  webhookRef: payments-operations
```

Each monitor/environment/location tracks scheduled failures independently. Crossing the threshold emits a failure notification; the next successful scheduled result emits recovery. On-demand and deployment-triggered runs do not advance scheduled alert counters. An operator maps `payments-operations` to a delivery URL in the notification worker's deployment. Quorum, latency and coverage fields are reserved in the model but are not operational; leave them unset.

For complete examples, begin with the [user guide](user-guide/README.md).
