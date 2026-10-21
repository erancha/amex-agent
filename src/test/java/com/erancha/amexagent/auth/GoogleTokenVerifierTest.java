package com.erancha.amexagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the four checks every token goes through (signature, issuer, expiry, audience) and that
 * each refusal names a cause a person can act on.
 */
class GoogleTokenVerifierTest {
  private final GoogleTokenVerifier verifier = TestTokens.verifier();

  @Test
  void verify_validToken_returnsSubEmailAndName() {
    VerifiedUser user = verifier.verify(TestTokens.pageToken("42"), TestTokens.PAGE_CLIENT_ID);
    assertThat(user).isEqualTo(new VerifiedUser("42", "42@example.com", "Person 42"));
  }

  @Test
  void verify_tokenWithoutEmailOrName_leavesThemNull() {
    String token = TestTokens.sign(new JWTClaimsSet.Builder().subject("7")
        .audience(TestTokens.PAGE_CLIENT_ID).issuer(TestTokens.ISSUER)
        .expirationTime(Date.from(Instant.now().plusSeconds(60))).build());
    assertThat(verifier.verify(token, TestTokens.PAGE_CLIENT_ID))
        .isEqualTo(new VerifiedUser("7", null, null));
  }

  @Test
  void verify_expired_saysExpired() {
    String token = TestTokens.sign("42", TestTokens.PAGE_CLIENT_ID, TestTokens.ISSUER,
        Instant.now().minusSeconds(120));
    assertThatThrownBy(() -> verifier.verify(token, TestTokens.PAGE_CLIENT_ID))
        .isInstanceOf(InvalidTokenException.class).hasMessageContaining("expired");
  }

  @Test
  void verify_otherAudience_saysNotIssuedForThisClient() {
    assertThatThrownBy(() -> verifier.verify(TestTokens.callerToken("42"),
        TestTokens.PAGE_CLIENT_ID))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("not issued for this client");
  }

  @Test
  void verify_otherIssuer_namesBothIssuers() {
    String token = TestTokens.sign("42", TestTokens.PAGE_CLIENT_ID, "https://evil.example",
        Instant.now().plusSeconds(60));
    assertThatThrownBy(() -> verifier.verify(token, TestTokens.PAGE_CLIENT_ID))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("https://evil.example").hasMessageContaining(TestTokens.ISSUER);
  }

  @Test
  void verify_tamperedSignature_isRefused() {
    String token = TestTokens.pageToken("42");
    String tampered = token.substring(0, token.length() - 2) + "AA";
    assertThatThrownBy(() -> verifier.verify(tampered, TestTokens.PAGE_CLIENT_ID))
        .isInstanceOf(InvalidTokenException.class).hasMessageContaining("signed");
  }

  @Test
  void verify_unsignedOrHmacToken_isRefused() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("42")
        .audience(TestTokens.PAGE_CLIENT_ID).issuer(TestTokens.ISSUER)
        .expirationTime(Date.from(Instant.now().plusSeconds(60))).build();
    String plain = new PlainJWT(claims).serialize();
    SignedJWT hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    hmac.sign(new MACSigner(new byte[32]));
    for (String token : new String[] {plain, hmac.serialize(), "not-a-token", ""}) {
      assertThatThrownBy(() -> verifier.verify(token, TestTokens.PAGE_CLIENT_ID))
          .as(token).isInstanceOf(InvalidTokenException.class);
    }
  }

  @Test
  void verify_googleIssuer_acceptsBothSpellings(@TempDir Path dir) throws Exception {
    Path pem = dir.resolve("google.pem");
    Files.copy(TestTokens.publicKeyFile(), pem);
    GoogleTokenVerifier google =
        GoogleTokenVerifier.fromPublicKeyPem(pem, GoogleTokenVerifier.GOOGLE_ISSUER);
    for (String issuer : new String[] {"https://accounts.google.com", "accounts.google.com"}) {
      String token = TestTokens.sign("42", "c", issuer, Instant.now().plusSeconds(60));
      assertThat(google.verify(token, "c").sub()).isEqualTo("42");
    }
  }

  @Test
  void fromPublicKeyPem_notAKey_failsNamingTheFile(@TempDir Path dir) throws Exception {
    Path pem = dir.resolve("bad.pem");
    Files.writeString(pem, "hello");
    assertThatThrownBy(() -> GoogleTokenVerifier.fromPublicKeyPem(pem, TestTokens.ISSUER))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad.pem");
  }
}
