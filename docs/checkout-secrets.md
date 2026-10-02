# Using secrets in the checkout monitor

The value of `X-Probe-Key` comes from **a secret stored in Vault**. An operator creates that secret first. When Heimdall runs the monitor, the HTTP runner reads the value from Vault and inserts it into the outgoing request. Adding the reference to YAML does not create or populate the secret.

## What does this reference mean?

The [order-processing monitor](../config/teams/payments/apps/checkout/services/orders-api/monitors/order-processing.yaml) contains:

```yaml
headers:
  X-Probe-Key: '${secrets.secret/data/payments/orders#probeKey}'
```

Read it as: **“Fetch the `probeKey` field from the `payments/orders` secret in Vault's `secret` KV v2 engine.”**

| Part | Meaning |
|---|---|
| `${secrets.…}` | Heimdall's syntax for substituting a secret value into a request. |
| `secret` | The name of the secrets engine mounted in Vault. |
| `data/` | The KV v2 API route for reading secret data. |
| `payments/orders` | The name/path of the secret record inside that engine. |
| `#probeKey` | The field to read from the record. |

The path belongs to Vault. It is not a folder in this repository or a file on the runner. In the Vault UI, open the `secret/` engine, then the `payments/orders` record and its `probeKey` field.

There are two spellings of the path, depending on how you access it:

| Where you use it | Path |
|---|---|
| Vault CLI or UI | Mount `secret`, record `payments/orders` (combined: `secret/payments/orders`). |
| Heimdall YAML reference | `secret/data/payments/orders#probeKey` |
| Vault HTTP API | `/v1/secret/data/payments/orders` |

The Vault CLI adds the KV v2 `data/` segment automatically. Heimdall's reference must include it because the provider uses the HTTP API directly. See Vault's [CLI/API path comparison](https://developer.hashicorp.com/vault/docs/secrets/kv#version-comparison).

## How the value reaches the request

1. Git supplies the monitor definition containing the reference.
2. The HTTP runner at the selected location finds `${secrets.secret/data/payments/orders#probeKey}` in the step.
3. It checks whether the run's subject is allowed to read that secret prefix for this monitor, environment, and location.
4. It sends `GET <VAULT_ADDRESS>/v1/secret/data/payments/orders`, authenticating with `X-Vault-Token: <VAULT_TOKEN>`.
5. It reads `data.data.probeKey` from the KV v2 response and substitutes the value into the header before sending the API request.

For example, if the stored value is the disposable string `fixture-probe-key`, the target API receives:

```http
X-Probe-Key: fixture-probe-key
```

The HTTP runner needs network access to Vault. The monitor's `env.baseUrl` points to the target API; it does not select the Vault server. The runner's `VAULT_ADDRESS` selects that server.

## Create the example secrets locally

This walkthrough sets up the secrets used by the checkout monitor. It assumes the [Vault executable is installed](https://developer.hashicorp.com/vault/install). Running the monitor also requires the HTTP runner and its normal backing services. This checkout does not include `deploy/local/compose.yaml`, so these commands use a standalone Vault dev server.

### 1. Start a disposable Vault server

In one terminal, run:

```sh
vault server -dev \
  -dev-listen-address=127.0.0.1:8200 \
  -dev-root-token-id=fixture-vault-root
```

Leave it running. [Vault dev mode](https://developer.hashicorp.com/vault/docs/concepts/dev-server) creates a KV v2 engine at `secret/` automatically and stores data in memory. These credentials are for local development only; restarting the server removes the records you create.

### 2. Write the record and its three fields

In a second terminal:

```sh
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=fixture-vault-root

vault kv put -mount=secret payments/orders \
  clientId=fixture-client \
  clientSecret=fixture-client-secret \
  probeKey=fixture-probe-key
```

This creates **one record**, `payments/orders`, containing three string fields. Use `payments/orders` with `-mount=secret`; do not put `data/` or `#probeKey` in this CLI command. The strings above are disposable example values. Real credentials should be provisioned through your organization's secret-management process.

### 3. Verify the example field

```sh
vault kv get -mount=secret -field=probeKey payments/orders
```

For this disposable record, the output should be `fixture-probe-key`. This verifies the Vault address, token, path, and field used by your CLI. The runner still needs its own environment configured and the Heimdall permission described below.

### 4. Give the HTTP runner access to Vault

Set these variables in the environment of the **HTTP runner process** before starting it. For a runner launched from this terminal:

```sh
export VAULT_ADDRESS=http://127.0.0.1:8200
export VAULT_TOKEN=fixture-vault-root
```

| Variable | Used by | Purpose |
|---|---|---|
| `VAULT_ADDR` | Vault CLI | Where CLI commands read and write secrets. |
| `VAULT_ADDRESS` | Heimdall HTTP runner | Where the runner fetches referenced values. |
| `VAULT_TOKEN` | Both, in their respective process environments | Authenticates requests to Vault. |
| `STATE_KEY` | Heimdall HTTP runner | A separate base64-encoded 32-byte key for encrypting persisted run state. It is not the probe key or a Vault token. |

Exporting `VAULT_ADDR` alone does not configure Heimdall. The current Vault adapter reads `VAULT_ADDRESS` and `VAULT_TOKEN` directly; it does not perform a Vault login or read the CLI's saved token.

If the runner starts from an IDE, set the variables in its run configuration. If it runs in a container or Kubernetes, supply them to that workload. Use an address reachable from the runner: `127.0.0.1` in a container refers to that container, not the host's Vault server. Each regional runner needs access to the referenced records through its configured Vault server.

Provision `STATE_KEY` separately as part of runner setup and retain it across restarts so existing encrypted state remains readable. After Vault and the runner are configured, the existing YAML references can stay as written.

## How OAuth credentials use the same record

The monitor's `authRef: orders-oauth` selects [authentication/orders-oauth.yaml](../config/teams/payments/apps/checkout/authentication/orders-oauth.yaml), which contains:

```yaml
clientIdRef: secret/data/payments/orders#clientId
clientSecretRef: secret/data/payments/orders#clientSecret
```

These read the `clientId` and `clientSecret` fields from the same record you just created. Authentication fields ending in `Ref` take the reference directly; they do not need the `${secrets.…}` wrapper used in request templates.

The runner resolves both fields, sends them to `${env.baseUrl}/oauth/token` using the client-credentials grant, and adds the returned access token as `Authorization: Bearer …` on target requests. The `X-Probe-Key` header is resolved separately from the `probeKey` field. The profile allows one token refresh after a 401 before evaluating final assertions.

## Grant access in both Heimdall and Vault

Two permissions are required:

- **Heimdall permission:** a central role binding must allow the run's subject, scope, environment, location, secret prefix, and production access when applicable. The example [runners binding](../config/central/role-bindings/runners.yaml) grants `dev-runner` and `scheduler` access to `secretPrefixes: [secret/data/payments]` for checkout in `staging` at `local`. That prefix covers all three references above. A Viewer binding does not grant secret access; Git ownership alone does not grant it either.
- **Vault permission:** the token supplied to the runner must be allowed to read `secret/data/payments/orders`. A Heimdall role binding does not create a Vault policy or token.

For a real deployment, a Vault administrator can attach this read policy to the runner's Vault identity/token:

```hcl
path "secret/data/payments/orders" {
  capabilities = ["read"]
}
```

Use the workload's provisioned token in `VAULT_TOKEN` and its Vault endpoint in `VAULT_ADDRESS`. Keep real secret values and runner credentials out of team YAML. Public attempt results omit request credentials and response bodies; OAuth tokens, extracted variables, and responses are held in encrypted run state.

## Common points of confusion

| Situation | What to check |
|---|---|
| “Where is the secret file?” | Look in Vault: engine `secret/`, record `payments/orders`, field `probeKey`. YAML stores only the reference. |
| CLI access works, but the runner cannot fetch the secret | Check the runner's own `VAULT_ADDRESS` and `VAULT_TOKEN`, network access, and both permission layers. |
| The record exists, but resolution fails | Confirm KV v2 is mounted at `secret/`, the reference includes `data/`, and the named field exists and is a string. |
| Vault was restarted in dev mode | Run the `vault kv put` command again; dev-mode records are not persisted. |
| The attempt reports “Execution, secret or script failure” | The runner deliberately reports a generic error. Check the reference, field, endpoint, token, and permissions rather than expecting a secret value in logs. |
| Gradle tests pass without a Vault server | The [HTTP activity tests](../components/http-runner/src/test/java/dev/heimdall/http/HttpActivitiesIntegrationTest.java) and [API end-to-end test](../components/control-plane/src/test/java/dev/heimdall/control/ApiEndToEndTest.java) inject a `Secrets` implementation returning fixture strings. The deployed HTTP runner uses Vault instead. |

To follow the implementation, start with [HttpRunner.java](../components/http-runner/src/main/java/dev/heimdall/http/HttpRunner.java) for provider wiring, [HttpActivities.java](../components/http-runner/src/main/java/dev/heimdall/http/HttpActivities.java) for authorization and substitution, and [VaultSecrets.java](../libraries/provider-interfaces/src/main/java/dev/heimdall/providers/VaultSecrets.java) for the Vault HTTP request and field lookup.
