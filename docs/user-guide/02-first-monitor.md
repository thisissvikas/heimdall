# Write your first monitor

Start with one HTTP request and one assertion. Add workflow features only when your API journey needs them.

## Understand the directory

The checkout application already has its team, project, application, service, staging environment and local location registered. Add the file below at `config/teams/payments/apps/checkout/services/orders-api/monitors/health.yaml`:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: Monitor
metadata:
  name: health
spec:
  type: api
  environments: [staging]
  locations: [local]
  timeout: 1m
  maxExecutions: 10
  steps:
    - id: check-health
      type: http
      request:
        method: GET
        url: '${env.baseUrl}/health'
      assertions:
        - source: status
          operator: equals
          value: 200
        - source: body
          path: $.ok
          operator: equals
          value: true
```

Its identity is `payments/checkout/orders-api/health`. The filename helps organize the repository; `metadata.name` establishes the monitor's name. Renaming the file within the same service preserves identity. Moving it to a different team, application or service changes its scope and requires an ownership migration.

`${env.baseUrl}` comes from `apps/checkout/environments/staging.yaml`. Keep deployment-specific values in environment files instead of duplicating URLs in monitors.

## Validate and run it

```sh
./gradlew validateConfig
```

In the local stack, wait for the next reconciliation pass. In a shared environment, open a PR, obtain the code-owner approval and merge first. Then execute:

```sh
cli/build/install/cli/bin/cli run \
  --url http://localhost:8080 \
  --reference payments/checkout/orders-api/health \
  --environment staging --locations local \
  --idempotency-key health-tutorial-1 --wait
```

A monitor reference runs one monitor. A suite reference runs every monitor in that approved suite.

## Group checks into a suite

Edit the checkout deployment suite's `spec.monitors` to include the new monitor:

```yaml
monitors:
  - payments/checkout/orders-api/health
  - payments/checkout/orders-api/order-processing
environments: [staging]
locations: [local]
```

Validate, approve and reconcile the change. Running `payments/checkout/deployment` now requires both monitors to pass at each selected location. A suite cannot grant access to an environment or location that a monitor does not declare.

## Move from the fixture to your API

Create or edit an environment file in your application:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: Environment
metadata:
  name: staging
spec:
  production: false
  values:
    baseUrl: https://staging.api.example.com
```

An operator must register a location capable of reaching that host and grant your identity permission for the monitor scope, environment, location and required secret paths. Changing an environment URL does not create those grants. See [locations](07-schedules-and-locations.md).

For a new application, also add `app.yaml`, `service.yaml`, and environment files under your registered team. `app.yaml` must select a central project. A new team requires central team registration and a matching CODEOWNERS entry; ask your platform operator to make that reviewed change.

Next: [requests and assertions](03-requests-and-assertions.md).
