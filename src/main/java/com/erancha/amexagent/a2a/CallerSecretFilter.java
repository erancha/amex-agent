package com.erancha.amexagent.a2a;

import com.erancha.amexagent.auth.Caller;
import com.erancha.amexagent.auth.Callers;
import com.erancha.amexagent.statements.AuthConfigEndpoint;
import com.erancha.amexagent.statements.StatementsEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admits an A2A request only from a registered caller: the X-Caller-Secret header must equal one
 * entry's secret, and that entry travels on as a request attribute for the executor, which checks
 * the forwarded person's token against the entry's client id. The card, the health probe, the
 * statements page with its API and the auth config are open: people reach those with their own
 * Google token or with nothing, never with a caller's secret.
 */
public class CallerSecretFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Caller-Secret";
  /** Request attribute holding the matched Caller. */
  public static final String CALLER = CallerSecretFilter.class.getName() + ".caller";

  private final Callers callers;

  public CallerSecretFilter(Callers callers) {
    this.callers = callers;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/.well-known/") || path.equals("/actuator/health")
        || path.equals(StatementsEndpoint.PAGE) || path.equals(StatementsEndpoint.API)
        || path.startsWith(StatementsEndpoint.API + "/") || path.equals(AuthConfigEndpoint.PATH);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String presented = request.getHeader(HEADER);
    Optional<Caller> caller = presented == null ? Optional.empty() : callers.bySecret(presented);
    if (caller.isPresent()) {
      request.setAttribute(CALLER, caller.get());
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", HEADER);
  }
}
