# Using Heimdall

Heimdall runs API checks that you define in Git. A check can be one request or a journey that creates data, waits for a background job, verifies its result, and cleans up. You trigger approved checks through the CLI, REST API, schedules, or a deployment pipeline.

Start here even if you have never used a synthetic monitoring tool. You need basic familiarity with an HTTP API and YAML. The local tutorial supplies a target API and disposable credentials, so you do not need a cloud account.

## Choose your starting point

| Goal | Read |
|---|---|
| Try the tool on your laptop | [First run](01-first-run.md) |
| Add a simple check for your own API | [Your first monitor](02-first-monitor.md) |
| Check response contents, headers, or timing | [Requests and assertions](03-requests-and-assertions.md) |
| Pass data between requests and wait for jobs | [Chaining and durable workflows](04-workflows.md) |
| Use credentials safely | [Secrets and authentication](05-secrets-and-authentication.md) |
| Add custom JavaScript, branches, and parallel work | [Advanced checks](06-advanced-checks.md) |
| Run repeatedly or in several networks | [Schedules and locations](07-schedules-and-locations.md) |
| Block an application deployment when checks fail | [Deployment gates](08-deployment-gates.md) |
| Find a run, interpret failure, or cancel it | [Results and troubleshooting](09-results-and-troubleshooting.md) |
| Operate the platform and manage permissions | [Operator guide](../operator-guide.md) |
| Look up an exact field or API request | [Configuration reference](../configuration-reference.md), [API reference](../api-reference.md) |

## Five terms you will see

**Monitor:** an approved API workflow. Its identity is `team/application/service/name`, for example `payments/checkout/orders-api/order-processing`.

**Suite:** a group of monitors, with permitted environments and locations. Its identity is `team/application/name`, for example `payments/checkout/deployment`.

**Environment:** values for a deployment, such as its API base URL. `staging` is a name in configuration; it is separate from the application Git commit being tested.

**Location:** the network where a runner executes HTTP requests. A private location can reach an internal API. Each selected location produces its own result.

**Run:** one admitted execution, with a unique ID and a pinned configuration revision. Retrying the same submission with the same idempotency key returns the same run. A run can contain several monitor/location outcomes.

## How a change becomes usable

1. Edit your team's YAML or application scripts.
2. Run `./gradlew validateConfig`.
3. Open a pull request and obtain the required code-owner review.
4. Merge into the protected configuration branch.
5. Let Heimdall reconcile the approved revision, normally within its 60-second polling interval.
6. Trigger the monitor or suite by its identity.

The local tutorial reads your checkout so you can experiment without opening a PR. Shared environments read the protected Git branch. There is no API that accepts arbitrary YAML or uploads persistent monitor configuration.

## What this implementation supports

API requests, declarative assertions, CEL expressions, variables and extraction, bounded retries, OAuth recovery, durable waits and polls, isolated post-response scripts, conditional and parallel steps, cleanup, interval schedules, scoped permissions, Vault references, encrypted state and diagnostics, persisted history, signed webhook notifications, and CLI/GitHub OIDC deployment gates are implemented.

Browser checks are outside this build. Cron schedules, mTLS target authentication, multipart uploads, automatic redirects, location groups, maintenance windows, GitHub Checks publishing, quorum/latency/missing-coverage alert policies, and production capacity/restore acceptance benchmarks remain planned. Unsupported configuration is rejected where validated; use the supported fields documented here. See [implementation status](../implementation-status.md) for the deployment and verification boundaries.
