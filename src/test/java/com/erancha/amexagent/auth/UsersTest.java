package com.erancha.amexagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Verifies which client id each kind of request is checked against, and the auth-off person. */
class UsersTest {
  private final Users users = new Users(TestTokens.verifier(), TestTokens.PAGE_CLIENT_ID, true);
  private final Caller caller = new Caller(TestTokens.CALLER_NAME, TestTokens.CALLER_SECRET,
      TestTokens.CALLER_CLIENT_ID);

  @Test
  void onPage_tokenForThePageClient_isThePerson() {
    assertThat(users.onPage("Bearer " + TestTokens.pageToken("42")).sub()).isEqualTo("42");
  }

  @Test
  void onPage_tokenForwardedToACaller_isRefused() {
    assertThatThrownBy(() -> users.onPage("Bearer " + TestTokens.callerToken("42")))
        .isInstanceOf(InvalidTokenException.class).hasMessageContaining("not issued");
  }

  @Test
  void onBehalfOf_tokenForTheCallersClient_isThePerson() {
    assertThat(users.onBehalfOf(caller, "Bearer " + TestTokens.callerToken("42")).sub())
        .isEqualTo("42");
  }

  @Test
  void onBehalfOf_pageTokenOrNoHeader_isRefused() {
    assertThatThrownBy(() -> users.onBehalfOf(caller, "Bearer " + TestTokens.pageToken("42")))
        .isInstanceOf(InvalidTokenException.class);
    assertThatThrownBy(() -> users.onBehalfOf(caller, null))
        .isInstanceOf(InvalidTokenException.class).hasMessageContaining("no Google ID token");
    assertThatThrownBy(() -> users.onBehalfOf(caller, "Basic abc"))
        .isInstanceOf(InvalidTokenException.class).hasMessageContaining("no Google ID token");
  }

  @Test
  void authOff_everyoneIsAnonymousAndNoClientIsPublished() {
    Users off = new Users(TestTokens.verifier(), null, false);
    assertThat(off.onPage(null)).isEqualTo(VerifiedUser.ANONYMOUS);
    assertThat(off.onBehalfOf(caller, null)).isEqualTo(VerifiedUser.ANONYMOUS);
    assertThat(off.pageClientId()).isNull();
  }

  @Test
  void authOn_withoutAClientId_failsNamingTheVariable() {
    assertThatThrownBy(() -> new Users(TestTokens.verifier(), " ", true))
        .hasMessageContaining("APP_AUTH_CLIENT_ID");
  }
}
