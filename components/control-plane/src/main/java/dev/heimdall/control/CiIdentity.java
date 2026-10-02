package dev.heimdall.control;

import dev.heimdall.contracts.Definitions.CiTrust;
import dev.heimdall.providers.Providers.Identity;
import java.util.*;

/** Maps verified GitHub claims to exactly one centrally approved trust rule. */
public final class CiIdentity {
  public static final String ISSUER = "https://token.actions.githubusercontent.com";

  private CiIdentity() {}

  static CiTrust trust(Map<String, Object> claims, List<CiTrust> rules) {
    return rules.stream()
        .filter(
            t ->
                t.repository().equals(claims.get("repository"))
                    && t.workflow().equals(claims.get("job_workflow_ref"))
                    && t.ref().equals(claims.get("ref"))
                    && Objects.equals(t.environment(), claims.get("environment")))
        .findFirst()
        .orElseThrow(() -> new SecurityException("GitHub workflow is not trusted"));
  }

  public static Identity resolve(Map<String, Object> claims, List<CiTrust> rules) {
    return new Identity(trust(claims, rules).subject(), claims);
  }

  public static void requireReferences(
      Identity identity, List<String> references, List<CiTrust> rules) {
    if (ISSUER.equals(Objects.toString(identity.claims().get("iss"), ""))
        && !trust(identity.claims(), rules).suites().containsAll(references))
      throw new SecurityException("CI reference not trusted");
  }
}
