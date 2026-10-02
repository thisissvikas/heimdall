# Chaining requests and waiting for jobs

Steps run in list order unless a transition changes the next step. The checkout example demonstrates a common journey: create a job, save its ID, poll its state, verify items and delete it.

## Extract data for the next request

```yaml
- id: create
  type: http
  request:
    method: POST
    url: '${env.baseUrl}/jobs'
    headers:
      Idempotency-Key: '${run.id}-${step.instanceId}'
    json: {operation: '${vars.operation}'}
  assertions:
    - {source: status, operator: equals, value: 202}
  extract:
    jobId: {source: body, path: $.id}
- id: read-job
  type: http
  request:
    url: '${env.baseUrl}/jobs/${vars.jobId}'
  assertions:
    - {source: status, operator: equals, value: 200}
```

Extraction supports `body` JSONPath, `header` names, and `cookie` names. A missing or null extraction fails the step. Extracted values live in the branch's encrypted state and are available as `vars` in subsequent steps.

## Use declared runtime parameters

Add a parameter to the monitor's `spec`:

```yaml
parameters:
  operation:
    type: string
    required: false
    defaultValue: checkout
```

Parameters support `string`, `number` and `boolean`. Submit an override using the REST API's `parameters` object:

```json
{
  "references": ["payments/checkout/orders-api/order-processing"],
  "environment": "staging",
  "locations": ["local"],
  "parameters": {"operation": "checkout-canary"}
}
```

Only declared parameters are accepted. They cannot override URLs, steps, credentials or location policy unless an approved monitor explicitly uses the parameter as data. Parameter values are part of the admitted request and workflow input, so use secret references for credentials. The current CLI uses parameter defaults; use the REST API for parameter overrides.

## Set workflow variables

```yaml
- id: initialize
  type: set
  values:
    expectedCount: 1
    correlationId: '${run.id}'
- id: verify-count
  type: assert
  expression: vars.itemCount >= vars.expectedCount
```

An `assert` step evaluates a CEL boolean expression against `vars`, `env`, `run` and the most recent `response`. It fails if the expression is false or cannot be evaluated.

## Poll until a terminal state

```yaml
- id: wait-until-ready
  type: poll
  interval: 30s
  timeout: 20m
  request:
    url: '${env.baseUrl}/jobs/${vars.jobId}'
    timeout: 10s
  assertions:
    - {source: status, operator: equals, value: 200}
  until: response.body.state == 'READY'
  failWhen: response.body.state == 'FAILED'
```

`until` and `failWhen` are CEL expressions. Polling finishes when the assertions pass and `until` is true. `failWhen` ends the poll immediately as a failure. Otherwise the workflow persists a timer and tries again at the interval. A poll timeout or the overall monitor deadline ends the run as `timed_out`.

This waiting does not hold an HTTP execution slot. Temporal stores the timer; a worker can restart during the wait and resume the workflow. HTTP slots are occupied only while activities execute requests or local step logic.

## Wait for a fixed period

```yaml
- id: wait-for-propagation
  type: wait
  duration: 2m
```

Prefer a poll when you can observe readiness. A fixed wait is useful when the API has no progress endpoint. Both are bounded by the monitor's overall `timeout`.

## Retry only safe operations

```yaml
- id: read-job
  type: http
  request:
    url: '${env.baseUrl}/jobs/${vars.jobId}'
  assertions:
    - {source: status, operator: equals, value: 200}
  retry:
    maxAttempts: 3
    statuses: [429, 503]
    backoff: 2s
    idempotent: true
```

The first request counts as attempt one. Retrying is bounded to five attempts and happens only for the declared response statuses. Network failures and ambiguous outcomes do not silently retry the target. For a mutation, use a target-supported idempotency key and ensure the server honors it before declaring retries. `idempotent` documents that contract; it cannot make an unsafe API safe.

Completed request checkpoints are persisted before result publication. Replaying a recorded checkpoint republishes its observation without calling the target again. A worker crash after claiming a request but before recording the outcome is reported as ambiguous rather than resubmitting a possibly completed mutation.

## Always clean up

Put cleanup steps in `spec.cleanup`:

```yaml
cleanup:
  - id: delete-job
    type: http
    request:
      method: DELETE
      url: '${env.baseUrl}/jobs/${vars.jobId}'
    assertions:
      - {source: status, operator: equals, value: 204}
```

Cleanup runs after success, failure, timeout and a Heimdall cancellation signal. It has a separate 30-second deadline and a bounded step budget. A failed cleanup changes a successful journey to failure; after a primary failure, that primary failure remains visible alongside the cleanup result. If creation never produced a `jobId`, this cleanup can also fail, so design a target cleanup endpoint that safely tolerates missing or already deleted fixture data when appropriate.

Set a monitor deadline and `maxExecutions` large enough for the expected polls, retries and branch work. A visit or execution budget is a hard bound, not an instruction to keep retrying indefinitely.

Next: [secrets and authentication](05-secrets-and-authentication.md).
