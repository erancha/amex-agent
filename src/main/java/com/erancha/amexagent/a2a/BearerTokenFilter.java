package com.erancha.amexagent.a2a;

import com.erancha.amexagent.statements.StatementsEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires "Authorization: Bearer <token>" on every A2A request. The public card, the health probe
 * and the statements page with its API are open: the token is shared between the two agents, and a
 * person loading a statement never needs it. The comparison is constant-time so response timing
 * leaks nothing about the token.
 */
public class BearerTokenFilter extends OncePerRequestFilter {
  private static final String PREFIX = "Bearer ";
  private final byte[] expected;

  public BearerTokenFilter(String token) {
    this.expected = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/.well-known/") || path.equals("/actuator/health")
        || path.equals(StatementsEndpoint.PAGE) || path.equals(StatementsEndpoint.API)
        || path.startsWith(StatementsEndpoint.API + "/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(PREFIX) && MessageDigest.isEqual(expected,
        header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "Bearer");
  }
}
