package dev.heimdall.control;

import dev.heimdall.contracts.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, Environment env) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health", "/v1/integrations/github/webhook")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    if (Arrays.asList(env.getActiveProfiles()).contains("local")) {
      @SuppressWarnings("unchecked")
      var tokens =
          (Map<String, String>)
              (Map<?, ?>)
                  Json.read(env.getRequiredProperty("heimdall.local-auth-tokens"), Map.class);
      if (tokens.isEmpty()) throw new IllegalArgumentException("Explicit local tokens required");
      http.addFilterBefore(
          new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
              String header = req.getHeader("Authorization");
              if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7), subject = null;
                for (var entry : tokens.entrySet())
                  if (java.security.MessageDigest.isEqual(
                      entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    subject = entry.getValue();
                if (subject != null)
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          new UsernamePasswordAuthenticationToken(subject, null, List.of()));
              }
              chain.doFilter(req, res);
            }
          },
          UsernamePasswordAuthenticationFilter.class);
    } else {
      var application =
          decoder(
              env.getRequiredProperty("heimdall.oidc-jwks"),
              env.getRequiredProperty("heimdall.oidc-issuer"),
              env.getProperty("heimdall.oidc-audience", "heimdall"));
      var github =
          decoder(
              "https://token.actions.githubusercontent.com/.well-known/jwks",
              "https://token.actions.githubusercontent.com",
              env.getProperty("heimdall.oidc-audience", "heimdall"));
      JwtDecoder decoder =
          token -> {
            try {
              String issuer = com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet().getIssuer();
              return "https://token.actions.githubusercontent.com".equals(issuer)
                  ? github.decode(token)
                  : application.decode(token);
            } catch (java.text.ParseException e) {
              throw new JwtException("Malformed identity token");
            }
          };
      http.oauth2ResourceServer(c -> c.jwt(j -> j.decoder(decoder)));
    }
    http.exceptionHandling(
        c ->
            c.authenticationEntryPoint((req, res, e) -> res.sendError(401))
                .accessDeniedHandler((req, res, e) -> res.sendError(403)));
    return http.build();
  }

  private static JwtDecoder decoder(String jwks, String issuer, String audience) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    OAuth2TokenValidator<Jwt> aud =
        jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid audience", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
    return decoder;
  }
}
