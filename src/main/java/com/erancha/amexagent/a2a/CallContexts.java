package com.erancha.amexagent.a2a;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;

/**
 * Builds the SDK's per-request context from a servlet request: every header (looked up without
 * regard to case, as HTTP defines them), the caller the secret filter admitted, the tenant taken
 * from the request path, the JSON-RPC transport marker, the requested extensions and the
 * requested protocol version. The SDK user stays unauthenticated: the executor resolves the
 * person from the forwarded token itself and binds a waiting task to that person.
 */
public final class CallContexts {
  /** State key of the admitted Caller; absent when authentication is off. */
  public static final String CALLER_KEY = "caller";
  /** State key of the owner under which this call takes and releases task leases. */
  public static final String LEASE_OWNER_KEY = "lease-owner";

  private CallContexts() {}

  static ServerCallContext from(HttpServletRequest request) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.put(name, request.getHeader(name));
    }
    Map<String, Object> state = new HashMap<>();
    state.put(JSONRPCContextKeys.HEADERS_KEY, headers);
    Object caller = request.getAttribute(CallerSecretFilter.CALLER);
    if (caller != null) {
      state.put(CALLER_KEY, caller);
    }
    state.put(JSONRPCContextKeys.TENANT_KEY, tenant(request.getRequestURI()));
    state.put(ServerCallContext.TRANSPORT_KEY, TransportProtocol.JSONRPC);
    Set<String> extensions = A2AExtensions.getRequestedExtensions(
        Collections.list(request.getHeaders(A2AHeaders.A2A_EXTENSIONS)));
    return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, extensions,
        request.getHeader(A2AHeaders.A2A_VERSION));
  }

  /** The request path without its leading and trailing slash; the root path is the empty tenant. */
  static String tenant(String path) {
    String tenant = path.startsWith("/") ? path.substring(1) : path;
    return tenant.endsWith("/") ? tenant.substring(0, tenant.length() - 1) : tenant;
  }
}
