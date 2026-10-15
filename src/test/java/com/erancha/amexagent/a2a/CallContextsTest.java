package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Verifies the per-request context carries headers, version, tenant and transport. */
class CallContextsTest {
  @Test
  void from_request_populatesSdkState() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
    request.addHeader("A2A-Version", "1.0");
    request.addHeader("A2A-Extensions", "urn:a, urn:b");
    request.addHeader("Authorization", "Bearer x");
    ServerCallContext ctx = CallContexts.from(request);
    assertThat(ctx.getUser()).isSameAs(UnauthenticatedUser.INSTANCE);
    assertThat(ctx.getRequestedProtocolVersion()).isEqualTo("1.0");
    assertThat(ctx.getRequestedExtensions()).containsExactlyInAnyOrder("urn:a", "urn:b");
    assertThat(ctx.getState())
        .containsEntry(ServerCallContext.TRANSPORT_KEY, TransportProtocol.JSONRPC)
        .containsEntry(JSONRPCContextKeys.TENANT_KEY, "");
    @SuppressWarnings("unchecked")
    Map<String, String> headers =
        (Map<String, String>) ctx.getState().get(JSONRPCContextKeys.HEADERS_KEY);
    assertThat(headers).containsEntry("Authorization", "Bearer x");
  }

  @Test
  void tenant_fromPath_trimsSlashes() {
    assertThat(CallContexts.tenant("/")).isEmpty();
    assertThat(CallContexts.tenant("/acme/")).isEqualTo("acme");
  }
}
