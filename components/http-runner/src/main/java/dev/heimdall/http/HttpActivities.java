package dev.heimdall.http;

import static dev.heimdall.contracts.Definitions.*;

import dev.heimdall.assertions.*;
import dev.heimdall.assertions.Checks.Response;
import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.providers.Rbac;
import dev.heimdall.scripts.ScriptSandbox;
import dev.heimdall.workflow.RunnerActivities;
import io.temporal.activity.Activity;
import io.temporal.client.ActivityCompletionException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

public final class HttpActivities implements RunnerActivities {
  public record StateData(Map<String, Object> vars, Response response, String token) {
    public StateData {
      vars = map(vars);
    }
  }

  public record Checkpoint(StateData state, StepOutcome outcome, List<Attempt> attempts) {}

  private final State state;
  private final Results results;
  private final Secrets secrets;
  private final ScriptSandbox sandbox;
  private final dev.heimdall.results.DiagnosticArtifacts artifacts;

  public HttpActivities(State state, Results results, Secrets secrets, ScriptSandbox sandbox) {
    this(state, results, secrets, sandbox, null);
  }

  public HttpActivities(
      State state,
      Results results,
      Secrets secrets,
      ScriptSandbox sandbox,
      dev.heimdall.results.DiagnosticArtifacts artifacts) {
    this.state = state;
    this.results = results;
    this.secrets = secrets;
    this.sandbox = sandbox;
    this.artifacts = artifacts;
  }

  private String stateKey(RunInput run, String branch) {
    return "%s/state/%s".formatted(run.key(), branch);
  }

  private StateData load(RunInput run, String branch) {
    var stored = state.load(stateKey(run, branch), StateData.class);
    return stored == null ? new StateData(run.parameters(), null, null) : stored;
  }

  @Override
  public StepOutcome execute(StepCommand command) {
    String key =
        "%s/request/%s:%s".formatted(command.run().key(), command.instanceId(), command.attempt());
    var checkpoint = state.load(key, Checkpoint.class);
    if (checkpoint != null) {
      state.save(stateKey(command.run(), command.branch()), checkpoint.state());
      recordAttempts(checkpoint.attempts());
      return checkpoint.outcome();
    }
    boolean http = Set.of("http", "poll").contains(command.step().type());
    if (http && !state.claim(key)) {
      // A previous worker claimed the request but never recorded its outcome.
      return new StepOutcome(
          false, true, true, null, "Ambiguous target outcome; request was not resubmitted");
    }
    StateData current = load(command.run(), command.branch());
    var vars = new LinkedHashMap<>(current.vars());
    var attempts = new ArrayList<Attempt>();
    StepOutcome outcome;
    try {
      var context = context(command, vars, current.response());
      if (http) {
        var auth =
            command.run().monitor().authRef() == null
                ? null
                : command
                    .run()
                    .snapshot()
                    .authentication()
                    .get(
                        "%s/%s"
                            .formatted(
                                appScope(command.run().monitorRef()),
                                command.run().monitor().authRef()));
        String token = current.token();
        if (auth != null && auth.type().equals("oauth2") && token == null)
          token = refresh(command.run(), auth);
        int refresh = 0;
        Response response;
        while (true) {
          Instant start = Instant.now();
          response = request(command, context, auth, token);
          Response observed = response;
          boolean recover =
              response.status() == 401
                  && auth != null
                  && auth.type().equals("oauth2")
                  && refresh < auth.maxRefresh();
          List<AssertionResult> checks =
              recover
                  ? List.of()
                  : command.step().assertions().stream()
                      .map(a -> Checks.evaluate(a, observed))
                      .toList();
          attempts.add(
              new Attempt(
                  "%s:%s".formatted(key, refresh),
                  command.run().id(),
                  command.run().monitorRef(),
                  command.run().location(),
                  command.step().id(),
                  (command.attempt() - 1) * (auth == null ? 1 : auth.maxRefresh() + 1)
                      + refresh
                      + 1,
                  start,
                  response.status(),
                  "target",
                  checks.stream().allMatch(AssertionResult::passed) && !recover,
                  response.timing(),
                  checks,
                  null,
                  recover));
          if (!recover) break;
          token = refresh(command.run(), auth);
          refresh++;
        }
        for (var e : command.step().extract().entrySet()) {
          Object value = Checks.extract(response, e.getValue().source(), e.getValue().path());
          if (value == Checks.MISSING || value == null)
            throw new IllegalArgumentException("Extraction failed");
          vars.put(e.getKey(), value);
        }
        if (command.step().postResponse() != null) {
          var spec = command.step().postResponse();
          String source =
              command
                  .run()
                  .snapshot()
                  .scripts()
                  .get("%s/%s".formatted(appScope(command.run().monitorRef()), spec.script()));
          long start = System.nanoTime();
          var output =
              sandbox.evaluate(
                  source, response.status(), response.headers(), response.text(), vars);
          if (!spec.outputs().containsAll(output.outputs().keySet()))
            throw new IllegalArgumentException("Undeclared script output");
          vars.putAll(output.outputs());
          var last = attempts.removeLast();
          var checks = new ArrayList<>(last.assertions());
          for (int i = 0; i < output.assertions().size(); i++) {
            checks.add(
                new AssertionResult(
                    "Post-response assertion %d".formatted(i + 1),
                    output.assertions().get(i).passed()));
          }
          var timing = new LinkedHashMap<>(last.timing());
          timing.put("script", (System.nanoTime() - start) / 1_000_000);
          attempts.add(
              new Attempt(
                  last.id(),
                  last.runId(),
                  last.monitorRef(),
                  last.location(),
                  last.stepId(),
                  last.attempt(),
                  last.startedAt(),
                  last.statusCode(),
                  last.classification(),
                  checks.stream().allMatch(AssertionResult::passed),
                  timing,
                  checks,
                  null,
                  last.recovered()));
        }
        current = new StateData(vars, response, token);
        boolean success = attempts.getLast().success();
        boolean complete = true, terminal = false;
        if (command.step().type().equals("poll")) {
          var c = context(command, vars, response);
          terminal =
              command.step().failWhen() != null && Expressions.test(command.step().failWhen(), c);
          complete = Expressions.test(command.step().until(), c);
        } else if (!success)
          terminal =
              command.step().retry() == null
                  || !command.step().retry().statuses().contains(response.status());
        outcome =
            new StepOutcome(
                success,
                complete,
                terminal,
                null,
                success && !terminal
                    ? null
                    : terminal
                        ? "Target assertion or terminal condition failed"
                        : "Target assertion failed");
      } else {
        outcome =
            switch (command.step().type()) {
              case "set" -> {
                command
                    .step()
                    .values()
                    .forEach((k, v) -> vars.put(k, Templates.structured(v, context)));
                yield StepOutcome.ok();
              }
              case "condition" ->
                  new StepOutcome(
                      true,
                      true,
                      false,
                      Expressions.test(command.step().expression(), context)
                          ? command.step().onTrue()
                          : command.step().onFalse(),
                      null);
              case "assert" -> {
                boolean ok = Expressions.test(command.step().expression(), context);
                yield new StepOutcome(ok, true, !ok, null, ok ? null : "Workflow assertion failed");
              }
              default -> throw new IllegalArgumentException("Unsupported runner primitive");
            };
        current = new StateData(vars, current.response(), current.token());
      }
    } catch (ActivityCompletionException e) {
      throw e;
    } catch (Exception e) {
      String classification = e instanceof java.io.IOException ? "target" : "platform";
      attempts.add(
          new Attempt(
              "%s:error".formatted(key),
              command.run().id(),
              command.run().monitorRef(),
              command.run().location(),
              command.step().id(),
              command.attempt(),
              Instant.now(),
              null,
              classification,
              false,
              Map.of(),
              List.of(),
              classification.equals("target")
                  ? "Network, DNS, TLS or request timeout failure"
                  : "Execution, secret or script failure",
              false));
      outcome =
          new StepOutcome(
              false,
              true,
              true,
              null,
              classification.equals("target")
                  ? "Network request failed; outcome may be ambiguous"
                  : "Runner execution failed");
    }
    var record = new Checkpoint(current, outcome, attempts);
    state.save(key, record); // Record the observation before any result publication.
    state.save(stateKey(command.run(), command.branch()), current);
    recordAttempts(attempts);
    return outcome;
  }

  private void recordAttempts(List<Attempt> attempts) {
    for (var attempt : attempts) {
      results.attempt(attempt);
      if (artifacts != null) artifacts.record(attempt);
    }
  }

  private Map<String, Object> context(
      StepCommand command, Map<String, Object> vars, Response response) {
    var env =
        new LinkedHashMap<>(
            command
                .run()
                .snapshot()
                .environments()
                .get(
                    "%s/%s"
                        .formatted(
                            appScope(command.run().monitorRef()), command.run().environment()))
                .values());
    if (command.run().monitor().defaults() != null)
      env.putAll(command.run().monitor().defaults().values());
    var context = new LinkedHashMap<String, Object>();
    context.put("env", env);
    context.put("vars", vars);
    context.put("run", Map.of("id", command.run().id(), "location", command.run().location()));
    context.put("step", Map.of("instanceId", command.instanceId(), "id", command.step().id()));
    context.put("response", response == null ? Map.of() : response.context());
    // Secret references are explicit and centrally authorized; resolve only referenced entries.
    var refs = new LinkedHashMap<String, Object>();
    var matcher =
        java.util.regex.Pattern.compile("\\$\\{secrets\\.([^}]+)}")
            .matcher(Json.write(command.step()));
    while (matcher.find()) refs.put(matcher.group(1), resolve(command.run(), matcher.group(1)));
    context.put("secrets", refs);
    return context;
  }

  private String resolve(RunInput run, String reference) {
    boolean allowed =
        run.snapshot().roles().stream()
            .anyMatch(
                r ->
                    r.subjects().contains(run.subject())
                        && !r.role().equals("Viewer")
                        && r.scopes().stream().anyMatch(s -> Rbac.matches(s, run.monitorRef()))
                        && r.environments().contains(run.environment())
                        && r.locations().contains(run.location())
                        && (!run.snapshot()
                                .environments()
                                .get(
                                    "%s/%s"
                                        .formatted(appScope(run.monitorRef()), run.environment()))
                                .production()
                            || r.production())
                        && r.secretPrefixes().stream()
                            .anyMatch(
                                p ->
                                    reference.equals(p)
                                        || reference.startsWith("%s/".formatted(p))));
    if (!allowed) throw new SecurityException("Secret access denied");
    return secrets.resolve(reference);
  }

  private Response request(
      StepCommand c, Map<String, Object> context, Authentication auth, String token)
      throws Exception {
    var spec = c.step().request();
    var url = HttpUrl.get(Templates.render(spec.url(), context, true)).newBuilder();
    spec.query()
        .forEach(
            (k, v) -> url.addQueryParameter(k, String.valueOf(Templates.structured(v, context))));
    var builder = new okhttp3.Request.Builder().url(url.build());
    spec.headers().forEach((k, v) -> builder.header(k, Templates.render(v, context, false)));
    if (auth != null)
      switch (auth.type()) {
        case "basic" ->
            builder.header(
                "Authorization",
                Credentials.basic(
                    resolve(c.run(), auth.usernameRef()), resolve(c.run(), auth.passwordRef())));
        case "bearer" ->
            builder.header(
                "Authorization", "Bearer %s".formatted(resolve(c.run(), auth.tokenRef())));
        case "apiKey" ->
            builder.header(
                auth.header() == null ? "X-API-Key" : auth.header(),
                resolve(c.run(), auth.tokenRef()));
        case "oauth2" -> builder.header("Authorization", "Bearer %s".formatted(token));
      }
    String method = spec.method() == null ? "GET" : spec.method();
    RequestBody body = null;
    if (spec.json() != null)
      body =
          RequestBody.create(
              Json.write(Templates.structured(spec.json(), context)),
              MediaType.get("application/json"));
    else if (spec.text() != null)
      body =
          RequestBody.create(
              Templates.render(spec.text(), context, false), MediaType.get("text/plain"));
    else if (!spec.form().isEmpty()) {
      var f = new FormBody.Builder();
      spec.form().forEach((k, v) -> f.add(k, Templates.render(v, context, false)));
      body = f.build();
    } else if (Set.of("POST", "PUT", "PATCH").contains(method))
      body = RequestBody.create(new byte[0], null);
    builder.method(method, body);
    long timeout = spec.timeout() == null ? 30000 : duration(spec.timeout()).toMillis();
    return send(
        builder.build(),
        c.run().snapshot().locations().get(c.run().location()),
        timeout,
        c.run().snapshot().platform().maxResponseBytes());
  }

  private String refresh(RunInput run, Authentication auth) throws Exception {
    var environment =
        new LinkedHashMap<>(
            run.snapshot()
                .environments()
                .get("%s/%s".formatted(appScope(run.monitorRef()), run.environment()))
                .values());
    if (run.monitor().defaults() != null) environment.putAll(run.monitor().defaults().values());
    String tokenUrl = Templates.render(auth.tokenUrl(), Map.of("env", environment), true);
    var form =
        new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", resolve(run, auth.clientIdRef()))
            .add("client_secret", resolve(run, auth.clientSecretRef()));
    if (auth.scope() != null) form.add("scope", auth.scope());
    var r =
        send(
            new okhttp3.Request.Builder().url(tokenUrl).post(form.build()).build(),
            run.snapshot().locations().get(run.location()),
            10000,
            65536);
    if (r.status() != 200
        || !(r.body() instanceof Map<?, ?> m)
        || !(m.get("access_token") instanceof String token)
        || token.isBlank()) throw new IllegalArgumentException("Token refresh failed");
    return token;
  }

  private Response send(okhttp3.Request request, Location location, long timeout, int maxBytes)
      throws Exception {
    if (!request.url().scheme().equals("http") && !request.url().scheme().equals("https")
        || !request.url().username().isEmpty()
        || !request.url().password().isEmpty()) throw new SecurityException("Invalid target URL");
    var timings = new Timings();
    var client =
        new OkHttpClient.Builder()
            .dns(new NetworkPolicy(location))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .eventListener(timings)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build();
    var future = new CompletableFuture<Response>();
    var call = client.newCall(request);
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call c, java.io.IOException e) {
            future.completeExceptionally(e);
          }

          @Override
          public void onResponse(Call c, okhttp3.Response response) {
            try (response) {
              if (response.isRedirect())
                throw new java.io.IOException("Redirect rejected; use the final approved URL");
              byte[] bytes =
                  response.body() == null
                      ? new byte[0]
                      : response.body().byteStream().readNBytes(maxBytes + 1);
              if (bytes.length > maxBytes)
                throw new java.io.IOException("Response size limit exceeded");
              String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
              Object parsed = null;
              try {
                parsed = Json.MAPPER.readValue(text, Object.class);
              } catch (Exception ignored) {
              }
              var headers = new LinkedHashMap<String, String>();
              for (var h : response.headers().names())
                headers.put(h.toLowerCase(Locale.ROOT), response.header(h));
              future.complete(
                  new Response(response.code(), headers, text, parsed, timings.result()));
            } catch (Exception e) {
              future.completeExceptionally(e);
            }
          }
        });
    try {
      while (true) {
        try {
          return future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          heartbeat();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof Exception cause) throw cause;
          throw e;
        }
      }
    } finally {
      if (!future.isDone()) call.cancel();
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  private static void heartbeat() {
    try {
      Activity.getExecutionContext().heartbeat(null);
    } catch (IllegalStateException ignored) {
      /* direct fixture invocation */
    }
  }

  @Override
  public void fork(RunInput run, String parent, String branch) {
    state.save(stateKey(run, branch), load(run, parent));
  }

  @Override
  public void merge(RunInput run, String parent, String branch, List<String> outputs) {
    var base = load(run, parent);
    var child = load(run, branch);
    var vars = new LinkedHashMap<>(base.vars());
    for (var key : outputs) {
      if (!child.vars().containsKey(key))
        throw new IllegalArgumentException("Missing branch output");
      vars.put(key, child.vars().get(key));
    }
    state.save(stateKey(run, parent), new StateData(vars, base.response(), base.token()));
  }
}
