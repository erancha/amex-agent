package com.erancha.amexagent.auth;

import java.net.URL;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How ID tokens are verified: the agent's own OAuth client (the audience of the statements
 * page's tokens), the issuer, and where its public keys come from, a JWKS document or, for tests
 * only, one PEM file that replaces it.
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
    @Nullable String clientId,
    @DefaultValue(GoogleTokenVerifier.GOOGLE_ISSUER) String issuer,
    @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") URL jwksUrl,
    @Nullable String publicKeyFile) {

  /** An unset APP_AUTH_PUBLIC_KEY_FILE reaches the binder as a blank string. */
  public GoogleTokenVerifier verifier() {
    return publicKeyFile == null || publicKeyFile.isBlank()
        ? GoogleTokenVerifier.fromJwks(jwksUrl, issuer)
        : GoogleTokenVerifier.fromPublicKeyPem(Path.of(publicKeyFile), issuer);
  }
}
