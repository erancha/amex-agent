package com.erancha.amexagent.a2a;

import java.time.Duration;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the public Agent Card, validated by the SDK on first read. It is unauthenticated and
 * publicly cacheable for an hour so discovery never needs the bearer token.
 */
@RestController
public class AgentCardEndpoint {
  private static final Duration CARD_MAX_AGE = Duration.ofHours(1);
  private final JSONRPCHandler handler;

  public AgentCardEndpoint(JSONRPCHandler handler) {
    this.handler = handler;
  }

  @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> card() throws JsonProcessingException {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(CARD_MAX_AGE).cachePublic())
        .body(JsonUtil.toJson(handler.getAgentCard()));
  }
}
