package dev.heimdall.providers;

import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.providers.Providers.*;

public final class Rbac implements Authorization {
  @Override
  public void require(
      Identity identity,
      String action,
      String scope,
      String environment,
      String location,
      Snapshot snapshot) {
    for (var r : snapshot.roles()) {
      if (!r.subjects().contains(identity.subject())) continue;
      if (action.equals("admin") && !r.role().equals("PlatformAdministrator")) continue;
      if ((action.equals("run") || action.equals("cancel")) && r.role().equals("Viewer")) continue;
      if (action.equals("artifacts") && !r.artifacts()) continue;
      if (!r.scopes().stream().anyMatch(s -> matches(s, scope))) continue;
      if (environment != null && !r.environments().contains(environment)) continue;
      if (location != null && !r.locations().contains(location)) continue;
      if (environment != null) {
        var env =
            snapshot
                .environments()
                .get("%s/%s/%s".formatted(scope.split("/")[0], scope.split("/")[1], environment));
        if (env != null
            && env.production()
            && (action.equals("run") || action.equals("cancel"))
            && !r.production()) continue;
      }
      return;
    }
    throw new SecurityException("Permission denied for %s".formatted(action));
  }

  public static boolean matches(String grant, String scope) {
    return grant.equals("*") || scope.equals(grant) || scope.startsWith("%s/".formatted(grant));
  }
}
