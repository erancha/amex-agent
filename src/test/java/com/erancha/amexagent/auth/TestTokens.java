package com.erancha.amexagent.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Signs ID tokens with a key pair generated once per test JVM and publishes the public half as a
 * PEM file, so tests verify real signatures without Google. register() hands a Spring test the
 * whole auth setup: test issuer, the page's client id, one registered caller and the key file.
 */
public final class TestTokens {
  public static final String ISSUER = "test-issuer";
  public static final String PAGE_CLIENT_ID = "page-client";
  public static final String CALLER_NAME = "test-caller";
  public static final String CALLER_SECRET = "caller-s3cret";
  public static final String CALLER_CLIENT_ID = "caller-client";
  public static final String SUB = "1000";
  private static final KeyPair KEYS = generate();
  private static final Path PUBLIC_KEY_FILE = writePublicKey();

  private TestTokens() {}

  /** A token for the page's client, as the statements page sends it. */
  public static String pageToken(String sub) {
    return sign(sub, PAGE_CLIENT_ID, ISSUER, Instant.now().plusSeconds(600));
  }

  /** A token for the registered caller's client, as a caller forwards it. */
  public static String callerToken(String sub) {
    return sign(sub, CALLER_CLIENT_ID, ISSUER, Instant.now().plusSeconds(600));
  }

  public static String sign(String sub, String audience, String issuer, Instant expiry) {
    return sign(new JWTClaimsSet.Builder().subject(sub).audience(audience).issuer(issuer)
        .expirationTime(Date.from(expiry)).issueTime(new Date())
        .claim("email", sub + "@example.com").claim("name", "Person " + sub).build());
  }

  public static String sign(JWTClaimsSet claims) {
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
        claims);
    try {
      jwt.sign(new RSASSASigner(KEYS.getPrivate()));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  public static Path publicKeyFile() {
    return PUBLIC_KEY_FILE;
  }

  public static GoogleTokenVerifier verifier() {
    return GoogleTokenVerifier.fromPublicKeyPem(PUBLIC_KEY_FILE, ISSUER);
  }

  /** The properties a booted agent needs to accept this class's tokens with auth on. */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("app.auth.client-id", () -> PAGE_CLIENT_ID);
    registry.add("app.auth.issuer", () -> ISSUER);
    registry.add("app.auth.public-key-file", () -> PUBLIC_KEY_FILE.toString());
    registry.add("app.a2a.allowed-callers[0].name", () -> CALLER_NAME);
    registry.add("app.a2a.allowed-callers[0].shared-secret", () -> CALLER_SECRET);
    registry.add("app.a2a.allowed-callers[0].client-id", () -> CALLER_CLIENT_ID);
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path writePublicKey() {
    String pem = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
    try {
      Path file = Files.createTempFile("test-auth-", ".pem");
      Files.writeString(file, pem);
      file.toFile().deleteOnExit();
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
