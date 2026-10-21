package com.erancha.amexagent.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.Set;

/**
 * Verifies a Google ID token the way every service that trusts Google does: RS256 signature
 * against the issuer's published keys, the issuer, expiry, and the audience, which must be the
 * OAuth client the token was issued to. Google signs tokens with issuer "https://accounts.google.com"
 * or the bare "accounts.google.com"; both name the same issuer and both are accepted when the
 * configured issuer is Google's. Any other issuer is matched exactly, which is how tests run on a
 * key of their own.
 */
public class GoogleTokenVerifier {
  public static final String GOOGLE_ISSUER = "https://accounts.google.com";
  private static final String GOOGLE_BARE_ISSUER = "accounts.google.com";
  private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
  private static final String END = "-----END PUBLIC KEY-----";

  private final JWKSource<SecurityContext> keys;
  private final Set<String> issuers;

  public GoogleTokenVerifier(JWKSource<SecurityContext> keys, String issuer) {
    this.keys = keys;
    this.issuers = GOOGLE_ISSUER.equals(issuer)
        ? Set.of(GOOGLE_ISSUER, GOOGLE_BARE_ISSUER) : Set.of(issuer);
  }

  /** Keys fetched from a JWKS document and cached by the library, as Google's are. */
  public static GoogleTokenVerifier fromJwks(URL jwksUrl, String issuer) {
    return new GoogleTokenVerifier(JWKSourceBuilder.<SecurityContext>create(jwksUrl).build(),
        issuer);
  }

  /** One RSA public key from a PEM file, for tests that sign their own tokens. */
  public static GoogleTokenVerifier fromPublicKeyPem(Path pem, String issuer) {
    try {
      String body = Files.readString(pem).replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
      RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
      return new GoogleTokenVerifier(
          new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(key).build())), issuer);
    } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(pem + " is not a PEM RSA public key: " + e.getMessage(), e);
    }
  }

  /**
   * @param audience the OAuth client id the token must have been issued to
   * @throws InvalidTokenException with the reason a person can act on
   */
  public VerifiedUser verify(String token, String audience) {
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience,
        new JWTClaimsSet.Builder().build(), Set.of("sub", "exp", "iss")));
    JWTClaimsSet claims;
    try {
      claims = processor.process(token, null);
    } catch (BadJWTException e) {
      throw new InvalidTokenException(reason(e), e);
    } catch (BadJOSEException | JOSEException | ParseException e) {
      throw new InvalidTokenException("the token is not a validly signed Google ID token", e);
    }
    if (!issuers.contains(claims.getIssuer())) {
      throw new InvalidTokenException("the token was issued by " + claims.getIssuer()
          + ", not by " + issuers.iterator().next());
    }
    try {
      return new VerifiedUser(claims.getSubject(), claims.getStringClaim("email"),
          claims.getStringClaim("name"));
    } catch (ParseException e) {
      throw new InvalidTokenException("the token's email or name is not text", e);
    }
  }

  /** Nimbus reports claim failures with fixed messages; these are the two a person can fix. */
  private static String reason(BadJWTException e) {
    String message = String.valueOf(e.getMessage());
    if (message.startsWith("Expired")) {
      return "the token has expired";
    }
    if (message.contains("audience")) {
      return "the token was not issued for this client";
    }
    return message;
  }
}
