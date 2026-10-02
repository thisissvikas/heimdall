# Gate an application deployment

A gate runs an approved suite against an application deployment and stops promotion unless every required outcome and cleanup passes. The tested application commit is separate from the Git revision of Heimdall's monitor configuration.

## Use the CLI in any CI system

Provide a short-lived API bearer token through a protected environment variable:

```sh
cli/build/install/cli/bin/cli run \
  --url https://heimdall.example.com \
  --reference payments/checkout/deployment \
  --environment staging \
  --locations eu-public,checkout-private \
  --token-env HEIMDALL_TOKEN \
  --idempotency-key application-release-2026-10-02-attempt-1 \
  --wait --timeout-seconds 1800 \
  --json reports/heimdall.json --junit reports/heimdall.xml
```

The URL and locations are placeholders for your deployment. The checked-in fixture only registers `local`. Build the CLI with `./gradlew :cli:installDist`; its installed launcher requires Java 25.

| Exit code | Meaning |
|---|---|
| `0` with `--wait` | All outcomes passed, cleanup passed and final results were published |
| `0` without `--wait` | Submission was admitted; execution can still fail |
| `1` | A completed run failed the gate |
| `2` | Request, authentication, transport, CLI or wait-timeout error |

Use `--wait` for promotion gates. A timeout is a blocking result; it must not become a successful promotion because history is missing. A gate timeout does not cancel the admitted run automatically. Retain the run ID and inspect or cancel it explicitly.

The CLI can run multiple comma-separated monitor/suite references. It currently sends the monitor parameter defaults and no deployment metadata; use the REST API or GitHub action when you need those fields.

## Use GitHub OIDC

The action lives at `integrations/github-action`. It exchanges the GitHub workflow's OIDC identity for authorization at Heimdall, submits the approved suite, waits for results, and writes `heimdall-results.json` and `heimdall-results.xml`.

An operator first approves an exact central trust rule:

```yaml
apiVersion: synthetics.heimdall.dev/v1alpha1
kind: CiTrust
metadata:
  name: checkout-staging
spec:
  repository: your-org/checkout
  workflow: your-org/checkout/.github/workflows/deploy.yaml@refs/heads/main
  ref: refs/heads/main
  environment: staging
  subject: ci:checkout-staging
  suites: [payments/checkout/deployment]
```

Add a central Runner role for `ci:checkout-staging` with the required environment, locations and secret prefixes. The OIDC audience is `heimdall`. The exact repository, workflow reference, branch and environment must match verified token claims. A workflow cannot inherit another workflow's suite grants just because both map to the same subject.

Then add the action to the application's workflow after deploying to staging:

```yaml
permissions:
  contents: read
  id-token: write

jobs:
  synthetics:
    runs-on: ubuntu-latest
    environment: staging
    steps:
      # The staging deployment must already be complete.
      - uses: actions/checkout@v4
        with:
          repository: your-org/heimdall
          ref: APPROVED_HEIMDALL_COMMIT_SHA
          path: heimdall
      - uses: ./heimdall/integrations/github-action
        with:
          endpoint: https://heimdall.example.com
          reference: payments/checkout/deployment
          environment: staging
          locations: checkout-private
          timeout-seconds: '1800'
```

Replace the repository, approved commit SHA, endpoint, locations and trust rule with real values. A private Heimdall repository needs an appropriate read credential for checkout. Pin external actions to reviewed commit SHAs in your organization's production workflow.

The action records the application `GITHUB_SHA`, repository and optional `HEIMDALL_ARTIFACT_DIGEST` as deployment metadata. It uses the GitHub run ID and attempt as the submission idempotency key. Upload the JSON/JUnit files as CI artifacts using your usual workflow tooling; this action does not currently publish GitHub Checks itself.

Next: [results and troubleshooting](09-results-and-troubleshooting.md).
