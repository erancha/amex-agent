package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies the token gate: 401 with a challenge unless the exact token is presented. */
class BearerTokenFilterTest {
  private final BearerTokenFilter filter = new BearerTokenFilter("s3cret");

  private MockHttpServletResponse call(String method, String path, String authorization)
      throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.setRequestURI(path);
    if (authorization != null) {
      req.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean reached = new AtomicBoolean();
    FilterChain chain = (r, s) -> reached.set(true);
    filter.doFilter(req, res, chain);
    res.setHeader("X-Reached", String.valueOf(reached.get()));
    return res;
  }

  @Test
  void post_withExactToken_passes() throws Exception {
    MockHttpServletResponse res = call("POST", "/", "Bearer s3cret");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getHeader("X-Reached")).isEqualTo("true");
  }

  @Test
  void post_withoutOrWrongToken_is401WithChallenge() throws Exception {
    String[] attempts = {null, "Bearer nope", "Basic abc", "Bearer s3cret2", "bearer s3cret"};
    for (String auth : attempts) {
      MockHttpServletResponse res = call("POST", "/", auth);
      assertThat(res.getStatus()).as(String.valueOf(auth)).isEqualTo(401);
      assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
      assertThat(res.getHeader("X-Reached")).isEqualTo("false");
    }
  }

  @Test
  void cardHealthAndStatementsPage_areOpen() throws Exception {
    assertThat(call("GET", "/.well-known/agent-card.json", null).getHeader("X-Reached"))
        .isEqualTo("true");
    assertThat(call("GET", "/actuator/health", null).getHeader("X-Reached")).isEqualTo("true");
    assertThat(call("GET", "/statements.html", null).getHeader("X-Reached")).isEqualTo("true");
    assertThat(call("GET", "/api/statements", null).getHeader("X-Reached")).isEqualTo("true");
    assertThat(call("POST", "/api/statements", null).getHeader("X-Reached")).isEqualTo("true");
    assertThat(call("DELETE", "/api/statements/2026-10.pdf", null).getHeader("X-Reached"))
        .isEqualTo("true");
  }
}
