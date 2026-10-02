# Schedules and locations

## Run on an interval

Add a schedule to the monitor's `spec`:

```yaml
schedule:
  every: 5m
  timezone: UTC
  paused: false
  overlap: skip
  jitter: 10s
```

Schedules are Git-managed. Validate and merge the change; the reconciler creates or updates the Temporal schedule. `every` must be at least ten seconds. The first checkout schedule is checked in as `paused: true`; change it to false only when you want recurring runs.

Choose `overlap: skip` to skip an occurrence while its previous scheduled workflow is still running. Choose `bufferOne` to retain one pending occurrence. The scheduling workflow waits for its monitor/location children, so overlap covers the whole journey, including durable waits. Jitter spreads starts within the configured interval. The current implementation supports interval schedules; cron, maintenance windows and location groups are not implemented.

Schedule actions carry a generation derived from the relevant monitor, environment and referenced dependencies. Admission rejects stale actions after an approved change, and active runs retain their existing snapshot. Pausing or deleting a schedule affects future starts; it does not cancel an already admitted run.

## Select execution networks

A monitor lists the locations where it may run:

```yaml
locations: [eu-public, checkout-private]
```

A run may choose a permitted subset:

```json
{
  "references": ["payments/checkout/deployment"],
  "environment": "staging",
  "locations": ["checkout-private"]
}
```

Omitting `locations` selects each monitor's declared locations. For a deployment gate, explicitly select the intended locations and ensure the approved suite includes them. Every selected monitor/location combination must pass; a missing result cannot pass the gate.

Locations are central resources. Operators register their network policy, permitted scopes, capabilities, enabled state and capacity, deploy a runner with the same location ID, and grant runtime permissions. For example:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: Location
metadata:
  name: checkout-private
spec:
  network: private
  allowedHosts: [orders.internal.example.com]
  scopes: [payments/checkout]
  capabilities: [api]
  enabled: true
  capacity: 20
  region: eu-west-1
```

Private `allowedHosts` are exact hostnames. The runner validates resolved addresses and blocks metadata, link-local and other forbidden addresses. Public locations additionally reject private and loopback targets. Redirects are rejected. A host grant controls access; it does not create network routes, DNS entries or a runner deployment.

The tutorial's `local` location is a private fixture location with allowed hosts `fixture` and `localhost`. `eu-public` and `checkout-private` above are illustrative names, so register them before using them in a monitor.

## Discover visible locations

```sh
curl --fail -H "Authorization: Bearer $HEIMDALL_TOKEN" http://localhost:8080/v1/locations
```

The API returns locations visible through your granted monitor scopes. A disabled or undeclared location cannot be admitted. If an enabled location has no healthy workers, its outcome remains pending until execution times out; an enabled catalog entry is not a live capacity guarantee.

Next: [deployment gates](08-deployment-gates.md).
