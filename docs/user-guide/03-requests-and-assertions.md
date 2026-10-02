# Requests and assertions

An `http` step sends one request, evaluates its assertions, extracts declared values, and optionally runs a post-response script. Assertions are explicit: a monitor without a status assertion does not automatically reject an HTTP 500.

## Send JSON, query parameters and headers

```yaml
- id: create
  type: http
  request:
    method: POST
    url: '${env.baseUrl}/jobs'
    timeout: 10s
    headers:
      Idempotency-Key: '${run.id}-${step.instanceId}'
      X-Correlation-ID: '${run.id}'
    query:
      priority: normal
    json:
      operation: '${vars.operation}'
      source: heimdall
  assertions:
    - {source: status, operator: equals, value: 202}
```

`method` defaults to GET. Supported methods are GET, HEAD, POST, PUT, PATCH, DELETE and OPTIONS. Choose one of `json`, `text` or `form` for the request body. `json` sets `application/json`; `text` defaults to `text/plain`; `form` sends URL-encoded form fields. A header can override the media type when needed.

```yaml
request:
  method: POST
  url: '${env.baseUrl}/search'
  form:
    query: '${vars.searchTerm}'
    limit: '10'
```

URL variables are percent-encoded so extracted IDs cannot alter the request path or query. The approved `env.baseUrl` is used as a complete base URL. Structured JSON substitution preserves values such as numbers, booleans, arrays and objects when the whole string is one variable.

The runner rejects redirects; use the final approved URL. It disables hidden connection retries. Any retry must be declared explicitly in the workflow.

## Check response data

```yaml
assertions:
  - {source: status, operator: equals, value: 200}
  - {source: header, path: content-type, operator: contains, value: application/json}
  - {source: body, path: $.id, operator: notNull}
  - {source: body, path: $.total, operator: greaterOrEqual, value: 1}
  - {source: text, operator: contains, value: READY}
  - {source: latency, operator: lessThan, value: 2000}
```

`body` uses JSONPath. `header` names are case-insensitive. `latency` is total request time in milliseconds. `certificateExpiry` is remaining TLS certificate lifetime in days, when TLS supplies a certificate. `cookie` selects a named cookie from the response's Set-Cookie header.

Operators are `equals`, `notEquals`, `greaterThan`, `greaterOrEqual`, `lessThan`, `lessOrEqual`, `matches`, `contains`, `exists`, `notNull`, `isNull`, `allNotNull` and `schema`. Numeric equality handles equivalent numeric representations. `matches` searches with RE2 regular expressions.

The distinction between absence and null matters:

| Operator | Meaning |
|---|---|
| `exists` | The JSONPath resolves, including an explicit JSON null |
| `notNull` | The value exists and is not null |
| `isNull` | The value exists and is explicitly null |
| `allNotNull` | A nonempty selected collection contains no null values |

For a collection whose property must be present on **every** object, use the JavaScript example in [advanced checks](06-advanced-checks.md). Some JSONPath wildcard queries omit missing properties, so `allNotNull` alone cannot prove that every original object had the field.

## Validate a schema

```yaml
- source: body
  path: $
  operator: schema
  value:
    type: object
    required: [id, state]
    properties:
      id: {type: string, minLength: 1}
      state: {type: string, enum: [PROCESSING, READY, FAILED]}
```

Schemas use JSON Schema 2020-12. References within the same schema are allowed; remote `$ref` URLs are rejected. This keeps validation self-contained.

## Fail with a useful, safe message

```yaml
- source: body
  path: $.state
  operator: equals
  value: READY
  message: Order processing should finish successfully
```

Use static messages that explain the expected behavior. Public results do not include actual response values. Avoid putting credentials or personal information in assertion labels or monitor names.

Next: [chaining and durable workflows](04-workflows.md).
