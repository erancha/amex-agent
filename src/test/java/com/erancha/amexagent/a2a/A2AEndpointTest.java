package com.erancha.amexagent.a2a;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Verifies the JSON-RPC edge over real HTTP: the card, SendStreamingMessage as SSE (including
 * one that ends in input-required), a single-JSON GetTask result, and the error mapping
 * for malformed, unknown and non-JSON requests. The agent executor is a stub that echoes or
 * waits for input; auth is off so the transport is tested alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "APP_STATEMENTS_DIR=src/test/resources/statements",
    "APP_A2A_AUTH_REQUIRED=false"})
@ActiveProfiles(E2E_PROFILE)
@Import(A2AEndpointTest.EchoExecutor.class)
public class A2AEndpointTest {
  /** User text that makes the echo executor stop in input-required instead of completing. */
  static final String WAIT = "wait";

  @TestConfiguration
  public static class EchoExecutor {
    @Bean
    @Primary
    AgentExecutor echo() {
      return (context, emitter) -> {
        emitter.startWork();
        if (WAIT.equals(context.getUserInput())) {
          emitter.requiresInput();
          return;
        }
        emitter.addArtifact(List.of(new TextPart("echo:" + context.getUserInput())));
        emitter.complete();
      };
    }
  }

  @Autowired RestClient.Builder builder;
  @Autowired TaskLease lease;
  @LocalServerPort int port;

  /** A read timeout turns a stream the server never closes into a test failure, not a hang. */
  private RestClient client() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(10));
    return builder.clone().baseUrl("http://localhost:" + port).requestFactory(factory).build();
  }

  /** Posts a JSON-RPC body with the protocol version header and returns the raw response. */
  private String post(String body) {
    return client().post().uri("/").contentType(MediaType.APPLICATION_JSON)
        .header("A2A-Version", "1.0").body(body).retrieve().body(String.class);
  }

  @Test
  void get_agentCard_isPublicJson() {
    var response = client().get().uri("/.well-known/agent-card.json").retrieve()
        .toEntity(String.class);
    assertThat(response.getHeaders().getCacheControl()).contains("max-age=3600");
    assertThat(response.getBody()).contains("\"monthly-transactions\"").contains("\"JSONRPC\"");
  }

  /**
   * Covers the single-JSON success path with GetTask. The task is created by streaming because a
   * blocking SendMessage can time out inside the SDK.
   */
  @Test
  void post_getTask_afterStreamedTask_returnsCompletedTaskAsJson() {
    String stream = post("""
        {"jsonrpc":"2.0","id":"1","method":"SendStreamingMessage","params":{"message":{
        "messageId":"m1","role":"ROLE_USER","parts":[{"text":"hello"}]}}}""");
    Matcher taskId = Pattern.compile("\"taskId\":\"([^\"]+)\"").matcher(stream);
    assertThat(taskId.find()).isTrue();

    String out = post("""
        {"jsonrpc":"2.0","id":"4","method":"GetTask","params":{"id":"%s"}}"""
        .formatted(taskId.group(1)));
    assertThat(out).contains("\"result\"").contains("TASK_STATE_COMPLETED")
        .contains("echo:hello");
  }

  @Test
  void post_sendStreamingMessage_returnsSseFrames() {
    String body = """
        {"jsonrpc":"2.0","id":"2","method":"SendStreamingMessage","params":{"message":{
        "messageId":"m2","role":"ROLE_USER","parts":[{"text":"stream"}]}}}""";
    var response = client().post().uri("/").contentType(MediaType.APPLICATION_JSON)
        .header("A2A-Version", "1.0").body(body).retrieve().toEntity(String.class);
    assertThat(response.getHeaders().getContentType().toString())
        .startsWith("text/event-stream");
    assertThat(response.getBody()).contains("data: ").contains("TASK_STATE_WORKING")
        .contains("echo:stream").contains("TASK_STATE_COMPLETED");
  }

  @Test
  void post_sendStreamingMessage_closesStreamWhenTaskWaitsForInput() {
    String body = """
        {"jsonrpc":"2.0","id":"3","method":"SendStreamingMessage","params":{"message":{
        "messageId":"m3","role":"ROLE_USER","parts":[{"text":"%s"}]}}}""".formatted(WAIT);
    String out = post(body);
    List<String> frames = out.lines().filter(line -> line.startsWith("data: ")).toList();
    assertThat(frames).isNotEmpty();
    assertThat(frames.getLast()).contains("TASK_STATE_INPUT_REQUIRED");
  }

  @Test
  void post_continueWhileAnotherCallHoldsTheTasksLease_isRefusedAsAlreadyRunning() {
    String paused = post("""
        {"jsonrpc":"2.0","id":"5","method":"SendStreamingMessage","params":{"message":{
        "messageId":"m5","role":"ROLE_USER","parts":[{"text":"%s"}]}}}""".formatted(WAIT));
    Matcher taskId = Pattern.compile("\"taskId\":\"([^\"]+)\"").matcher(paused);
    assertThat(taskId.find()).isTrue();
    lease.acquire(taskId.group(1), "another replica/call");

    String out = post("""
        {"jsonrpc":"2.0","id":"6","method":"SendStreamingMessage","params":{"message":{
        "messageId":"m6","role":"ROLE_USER","taskId":"%s","parts":[{"text":"go on"}]}}}"""
        .formatted(taskId.group(1)));

    assertThat(out).contains("\"error\"").contains("is already running");
    assertThat(lease.ownerOf(taskId.group(1))).isEqualTo("another replica/call");
  }

  @Test
  void post_nonJsonContentType_returnsJsonRpcContentTypeNotSupported() {
    String body = """
        {"jsonrpc":"2.0","id":1,"method":"GetTask","params":{"id":"x"}}""";
    String out = client().post().uri("/").contentType(MediaType.TEXT_PLAIN)
        .header("A2A-Version", "1.0").body(body).retrieve().body(String.class);
    JsonElement error = JsonParser.parseString(out).getAsJsonObject().get("error");
    assertThat(error.isJsonObject()).isTrue();
    assertThat(error.getAsJsonObject().get("code").getAsInt()).isEqualTo(-32005);
  }

  @Test
  void post_unknownMethod_returnsJsonRpcMethodNotFound() {
    String out = client().post().uri("/").contentType(MediaType.APPLICATION_JSON)
        .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}").retrieve()
        .body(String.class);
    assertThat(out).contains("\"error\"").contains("-32601").containsPattern("\"id\":\\s*1\\b");
  }

  @Test
  void post_malformedJson_returnsJsonRpcParseError() {
    String out = client().post().uri("/").contentType(MediaType.APPLICATION_JSON)
        .body("{not json").retrieve().body(String.class);
    assertThat(out).contains("\"error\"").contains("-32700");
  }
}
