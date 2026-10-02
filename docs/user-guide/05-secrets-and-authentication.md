# Secrets and authentication

Keep credentials in Vault or an equivalent provider. YAML names the secret; the runner retrieves its value while executing in the selected location. Git ownership and runtime secret permission are separate controls.

## Follow the checkout example

Read `config/teams/payments/apps/checkout/authentication/orders-oauth.yaml`:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: Authentication
metadata:
  name: orders-oauth
spec:
  type: oauth2
  tokenUrl: '${env.baseUrl}/oauth/token'
  clientIdRef: secret/data/payments/orders#clientId
  clientSecretRef: secret/data/payments/orders#clientSecret
  scope: orders:read orders:write
  maxRefresh: 1
```

The monitor uses `authRef: orders-oauth`. The runner requests a client-credentials token and adds `Authorization: Bearer ...` to its HTTP requests. A target 401 can refresh the token once and retry that request; a second 401 reaches the final assertions and fails the status assertion. The initial 401 remains an attempt marked as recovered, so successful recovery is visible.

The create step also shows a direct secret-backed header:

```yaml
request:
  headers:
    X-Probe-Key: '${secrets.secret/data/payments/orders#probeKey}'
```

`secret/data/payments/orders#clientSecret` means: call Vault's `secret/data/payments/orders` API path and select `clientSecret` from the KV v2 response. Vault's CLI writes the same data with `vault kv put secret/payments/orders ...`; the `/data/` segment belongs to the KV v2 API path. Use the example's exact references until your operator provisions your own paths.

## Provision a secret in the local tutorial

```sh
docker compose -f deploy/local/compose.yaml exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=fixture-vault-root vault vault kv put secret/payments/orders clientId=fixture-client clientSecret=fixture-client-secret probeKey=fixture-probe-key
```

Run the checkout suite after seeding. The fixture accepts its disposable bearer token and probe key. Remove or change the secret field to see a secret-resolution failure, then reseed it before continuing. Vault dev mode loses these values when its container restarts.

## Grant access independently

The central checkout Runner role includes:

```yaml
subjects: [dev-runner, scheduler]
role: Runner
scopes: [payments/checkout]
environments: [staging]
locations: [local]
secretPrefixes: [secret/data/payments]
production: false
artifacts: false
```

The runner checks the subject, monitor scope, environment, location, production grant and secret prefix before resolving each reference. The Vault workload identity also needs a matching Vault read policy. Satisfying one layer does not bypass the other.

In real environments, an operator supplies the runner's `VAULT_ADDRESS`, workload `VAULT_TOKEN` and encryption `STATE_KEY` through deployment secrets. Team authors only add references. Rotate the Vault values without committing a credential change to Git. The current adapter receives its workload token from the environment; renewing that token is an operator responsibility.

## Other supported profiles

Put these `spec` objects in application-owned Authentication resources, then select the resource with the monitor's `authRef`.

| Type | Fields | Behavior |
|---|---|---|
| `basic` | `usernameRef`, `passwordRef` | HTTP Basic Authorization header |
| `bearer` | `tokenRef` | Bearer Authorization header |
| `apiKey` | `tokenRef`, optional `header` | Header API key, default `X-API-Key` |
| `oauth2` | `tokenUrl`, `clientIdRef`, `clientSecretRef`, optional `scope`, `maxRefresh` | Client-credentials token and bounded 401 recovery |

Example API key:

```yaml
spec:
  type: apiKey
  header: X-API-Key
  tokenRef: secret/data/payments/orders#probeKey
```

Target mTLS profiles are not implemented yet. Do not disable TLS certificate verification to work around a certificate problem.

## What you can inspect

Normal results show status codes, timings and safe assertion outcomes. Request credentials and raw responses are omitted. Variables, response state and OAuth tokens are encrypted in PostgreSQL; diagnostic objects are encrypted before object-storage upload. Artifacts require a separate runtime grant. A post-response script can see response data and declared variables, so its published assertion messages are replaced with safe numbered labels.

Use runtime parameters for ordinary test inputs, and secret references for sensitive values. Runtime parameters are recorded as part of the admitted request and workflow input.

Next: [advanced checks](06-advanced-checks.md).
