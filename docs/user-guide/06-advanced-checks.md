# Scripts, branching and parallel steps

Use declarative assertions for simple comparisons. Use a post-response script when a condition needs JavaScript collection logic or calculated outputs. Use CEL for workflow decisions.

## Verify every object in a collection

Create `apps/checkout/scripts/validate-order-items.js`:

```js
export default function ({ response, vars, assert }) {
  const items = response.json().items;
  assert(Array.isArray(items) && items.length > 0, 'Items must be nonempty');
  assert(items.every(item => item.requiredField != null), 'Every item needs requiredField');
  return { itemCount: items.length };
}
```

Reference it on the HTTP step:

```yaml
postResponse:
  script: scripts/validate-order-items.js
  outputs: [itemCount]
```

The runner makes `itemCount` available as `${vars.itemCount}` and in CEL expressions. Every returned output name must be declared in `outputs`; undeclared output fails the step. Scripts stay within their application's `scripts/` directory and are pinned with the run's approved bundle.

The script receives `response.status`, `response.headers`, `response.text()`, `response.json()`, `vars` and `assert(condition, message)`. Return a plain JSON-compatible object synchronously. Promises, filesystem access, module imports, environment access, network calls, eval and generated code are unsupported. The runtime enforces execution, input, output, assertion-count and memory limits.

Local tests run the same protocol in a restricted Node process. Production requires the separately deployed gVisor sandbox service with denied network egress. See the [operator guide](../operator-guide.md) for this boundary. A script failure is visible as a failed attempt with a safe error; raw script output is not copied into public diagnostic messages.

## Choose a branch with CEL

```yaml
- id: choose
  type: condition
  expression: response.body.state == 'READY'
  onTrue: verify
  onFalse: finish
- id: verify
  type: assert
  expression: vars.itemCount > 0
  next: finish
- id: finish
  type: end
```

The last HTTP response is available as `response.status`, `response.headers`, `response.body` and `response.text`. `condition` selects `onTrue` or `onFalse`; an omitted transition falls through to the next listed step. `next` changes the next step unconditionally. All transition targets must exist in the same step list.

Expressions must return booleans. Missing data or a non-boolean result causes an execution failure. Use a status/body assertion or an extraction before relying on a response field.

## Join parallel work

```yaml
- id: read-in-parallel
  type: parallel
  branches:
    - id: left
      steps:
        - id: read-left
          type: http
          request: {url: '${env.baseUrl}/left'}
          assertions: [{source: status, operator: equals, value: 200}]
          extract:
            leftId: {source: body, path: $.id}
      outputs: [leftId]
    - id: right
      steps:
        - id: read-right
          type: http
          request: {url: '${env.baseUrl}/right'}
          assertions: [{source: status, operator: equals, value: 200}]
          extract:
            rightId: {source: body, path: $.id}
      outputs: [rightId]
- id: verify-join
  type: assert
  expression: vars.leftId != vars.rightId
```

Each branch starts with a copy of its parent's state. Changes remain isolated until every branch succeeds. Only declared branch outputs merge back into the parent; output names must not conflict between branches. Fanout is bounded to eight branches. Branch failure fails the parallel step and the normal run cleanup still applies.

`/left` and `/right` illustrate your own API endpoints; the checkout fixture does not implement them. Adapt the URLs and extraction paths before running this example.

## Bound a loop

A transition back to an earlier step needs `maxVisits` on the loop entry. Every monitor also needs an overall `timeout` and `maxExecutions`. Prefer the `poll` primitive for readiness checks; use a loop only when the repeated journey contains several distinct steps. Validation rejects unbounded backward transitions.

Next: [schedules and locations](07-schedules-and-locations.md).
