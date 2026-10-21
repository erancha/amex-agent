package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.auth.Caller;
import com.erancha.amexagent.auth.Callers;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the caller gate: 401 with a challenge unless X-Caller-Secret names a registered caller,
 * who then rides on the request for the executor.
 */
class CallerSecretFilterTest {
  private static final Caller HFA = new Caller("home-finance-agent", "s3cret", "client-1");
  private final CallerSecretFilter filter = new CallerSecretFilter(new Callers(List.of(HFA)));

  private record Outcome(MockHttpServletResponse response, boolean reached, Object caller) {}

  private Outcome call(String method, String path, String secret, String authorization)
      throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.setRequestURI(path);
    if (secret != null) {
      req.addHeader(CallerSecretFilter.HEADER, secret);
    }
    if (authorization != null) {
      req.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicReference<Object> caller = new AtomicReference<>();
    AtomicReference<Boolean> reached = new AtomicReference<>(false);
    FilterChain chain = (r, s) -> {
      reached.set(true);
      caller.set(r.getAttribute(CallerSecretFilter.CALLER));
    };
    filter.doFilter(req, res, chain);
    return new Outcome(res, reached.get(), caller.get());
  }

  @Test
  void post_withARegisteredSecret_passesWithTheCallerAttached() throws Exception {
    Outcome out = call("POST", "/", "s3cret", "Bearer some-id-token");
    assertThat(out.response().getStatus()).isEqualTo(200);
    assertThat(out.reached()).isTrue();
    assertThat(out.caller()).isEqualTo(HFA);
  }

  @Test
  void post_withoutOrWrongSecret_is401WithChallenge() throws Exception {
    String[][] attempts = {{null, null}, {"nope", null}, {"s3cret2", null}, {"S3cret", null},
        // Only X-Caller-Secret carries a secret; a secret sent as the bearer token is a token.
        {null, "Bearer s3cret"}};
    for (String[] attempt : attempts) {
      Outcome out = call("POST", "/", attempt[0], attempt[1]);
      assertThat(out.response().getStatus()).as(String.join("|", attempt)).isEqualTo(401);
      assertThat(out.response().getHeader("WWW-Authenticate")).isEqualTo("X-Caller-Secret");
      assertThat(out.reached()).isFalse();
    }
  }

  @Test
  void cardHealthStatementsAndAuthConfig_areOpen() throws Exception {
    for (String[] open : new String[][] {{"GET", "/.well-known/agent-card.json"},
        {"GET", "/actuator/health"}, {"GET", "/statements.html"}, {"GET", "/api/statements"},
        {"POST", "/api/statements"}, {"DELETE", "/api/statements/2026-10.pdf"},
        {"GET", "/api/auth/config"}}) {
      assertThat(call(open[0], open[1], null, null).reached()).as(open[1]).isTrue();
    }
  }
}
