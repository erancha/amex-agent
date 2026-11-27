package com.erancha.amexagent.statements;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.auth.TestTokens;
import com.erancha.amexagent.card.infra.PdfStatementParserTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * Verifies the statements page and its API over real HTTP with auth on: the page and the auth
 * config are open while the API needs the person's own Google token, an upload lands in that
 * person's folder under the download name of its content's month whatever it was called and is
 * listed to them alone, a path in the upload's name reaches no other folder, a listed file can be
 * deleted, and each refusal (not a PDF, month already loaded, oversized, deleting an unlisted
 * file) is a problem document naming the cause.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(E2E_PROFILE)
class StatementsEndpointTest {
  private static final String USER = "1000";
  private static final String OTHER_USER = "2000";
  private static Path statements;

  @DynamicPropertySource
  static void statementsDir(DynamicPropertyRegistry registry) throws IOException {
    statements = Files.createTempDirectory("statements");
    registry.add("APP_STATEMENTS_DIR", statements::toString);
    TestTokens.register(registry);
  }

  @Autowired RestClient.Builder builder;
  @LocalServerPort int port;
  @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize;

  @BeforeEach
  void emptyFolder() throws IOException {
    FileSystemUtils.deleteRecursively(statements);
    Files.createDirectory(statements);
  }

  private RestClient client() {
    return builder.clone().baseUrl("http://localhost:" + port).build();
  }

  private static String bearer(String sub) {
    return "Bearer " + TestTokens.pageToken(sub);
  }

  private ResponseEntity<String> list() {
    return list(bearer(USER));
  }

  private ResponseEntity<String> list(String authorization) {
    RestClient.RequestHeadersSpec<?> request = client().get().uri("/api/statements");
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    return request.retrieve().onStatus(status -> true, (req, res) -> {}).toEntity(String.class);
  }

  private ResponseEntity<String> upload(String fileName, byte[] content) {
    return upload(bearer(USER), fileName, content);
  }

  private ResponseEntity<String> upload(String authorization, String fileName, byte[] content) {
    MultipartBodyBuilder parts = new MultipartBodyBuilder();
    parts.part("file", content).filename(fileName).contentType(MediaType.APPLICATION_PDF);
    RestClient.RequestBodySpec request = client().post().uri("/api/statements")
        .contentType(MediaType.MULTIPART_FORM_DATA);
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    return request.body(parts.build()).retrieve().onStatus(status -> true, (req, res) -> {})
        .toEntity(String.class);
  }

  private ResponseEntity<String> delete(String fileName) {
    return client().delete().uri("/api/statements/{fileName}", fileName)
        .header("Authorization", bearer(USER)).retrieve()
        .onStatus(status -> true, (req, res) -> {}).toEntity(String.class);
  }

  private Path mine() {
    return statements.resolve(USER);
  }

  private static byte[] statement() throws IOException {
    return Files.readAllBytes(PdfStatementParserTest.FIXTURE);
  }

  @Test
  void page_andAuthConfig_arePublic() {
    ResponseEntity<String> page = client().get().uri("/statements.html").retrieve()
        .toEntity(String.class);
    assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(page.getBody()).contains("/api/statements").contains("/api/auth/config");
    ResponseEntity<String> config = client().get().uri("/api/auth/config").retrieve()
        .toEntity(String.class);
    assertThat(config.getBody()).isEqualTo("{\"clientId\":\"" + TestTokens.PAGE_CLIENT_ID + "\"}");
  }

  @Test
  void page_explainsWhySigningInWithGoogleIsSafe() {
    String page = client().get().uri("/statements.html").retrieve().body(String.class);
    assertThat(page).contains("aria-expanded=\"false\"")
        .contains(">Why sign in with Google?</button>")
        .contains("aria-label=\"Close the explanation\"")
        // Opening scrolls down to the explanation's last line.
        .contains("getElementById(\"why-text\").scrollIntoView("
            + "{ behavior: \"smooth\", block: \"end\" })")
        // Opening it folds the pitch's details back.
        .contains("if (open) showDetails(false);");
    for (String lead : new String[] {"safer and easier",
        "Google approves the sign-in.", "You stay in control:",
        "No new password to remember,", "Google's protections work for you:"}) {
      assertThat(page).contains("<strong>" + lead + "</strong>");
    }
    Matcher link = Pattern
        .compile("<a href=\"https://support\\.google\\.com/accounts/answer/12921417\"[^>]*>")
        .matcher(page);
    assertThat(link.find()).isTrue();
    assertThat(link.group()).contains("target=\"_blank\"").contains("rel=\"noreferrer\"")
        .contains("aria-label=\"Google's full explanation (opens in a new tab)\"");
  }

  @Test
  void page_signedOut_isTheSharedLandingCard() {
    String page = normalized(client().get().uri("/statements.html").retrieve().body(String.class));
    assertThat(page).contains("<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">")
        .contains("<link rel=\"stylesheet\" href=\"landing.css\">")
        .contains("<main class=\"landing\" id=\"signin\" hidden>")
        .contains("<h1 class=\"app-heading\"><img class=\"app-icon\" src=\"favicon.svg\" alt=\"\">"
            + " amex-agent</h1>")
        .contains("<p class=\"app-tagline landing-flash\">").doesNotContain("app-version")
        .contains("<ol class=\"landing-steps\">")
        .contains("<ul class=\"landing-details\" id=\"details\" hidden>")
        .contains("<button type=\"button\" class=\"landing-more landing-flash\" id=\"more\""
            + " aria-expanded=\"false\">More</button>")
        .contains("<button type=\"button\" class=\"sign-in-button\" tabindex=\"-1\">"
            + "<span class=\"sign-in-mark\" aria-hidden=\"true\">G</span>"
            + "Sign in with Google</button>")
        .contains("<div id=\"google-button\"></div>");
    assertThat(page.split("<p class=\"landing-pitch\">", -1).length - 1).isEqualTo(1);
    assertThat(page).contains("<strong>Charges are categorized on request</strong>");
  }

  @Test
  void page_landingLinks_inviteOnWhatsAppAndOpenTheSource() {
    String page = normalized(client().get().uri("/statements.html").retrieve().body(String.class));
    Matcher footer =
        Pattern.compile("<footer class=\"landing-links\">(.*?)</footer>").matcher(page);
    assertThat(footer.find()).isTrue();
    Matcher anchors = Pattern.compile("<a [^>]*>").matcher(footer.group(1));
    List<String> links = new ArrayList<>();
    while (anchors.find()) {
      links.add(anchors.group());
    }
    assertThat(links).as("no demo link: amex-agent has no demo").hasSize(2);
    assertThat(links.get(0)).contains("href=\"https://wa.me/?text=");
    assertThat(links.get(1)).contains("href=\"https://github.com/erancha/amex-agent\"");
    assertThat(links).allSatisfy(link -> assertThat(link).contains("target=\"_blank\"")
        .contains("rel=\"noreferrer\""));
    assertThat(footer.group(1))
        .contains("<span class=\"link-mark\" aria-hidden=\"true\">\uD83D\uDCAC</span>");
  }

  @Test
  void landingStylesAndIcon_areServedOpen() throws IOException {
    ResponseEntity<byte[]> css = client().get().uri("/landing.css").retrieve()
        .toEntity(byte[].class);
    assertThat(css.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/css")))
        .isTrue();
    assertThat(css.getBody())
        .isEqualTo(Files.readAllBytes(Path.of("src/main/resources/static/landing.css")));
    ResponseEntity<String> icon = client().get().uri("/favicon.svg").retrieve()
        .toEntity(String.class);
    assertThat(icon.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/svg+xml"));
    assertThat(icon.getBody()).startsWith("<svg");
    // A browser checks each file again on every load, so an edited icon or style shows at once.
    ResponseEntity<String> page = client().get().uri(StatementsEndpoint.PAGE).retrieve()
        .toEntity(String.class);
    for (ResponseEntity<?> file : List.of(page, css, icon)) {
      assertThat(file.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }
  }

  private static String normalized(String page) {
    return String.join(" ", page.trim().split("\\s+"));
  }

  @Test
  void api_withoutOrWithAnothersToken_is401NamingSignIn() throws IOException {
    for (String authorization : new String[] {null, "Basic abc",
        "Bearer " + TestTokens.callerToken(USER), "Bearer nope"}) {
      ResponseEntity<String> refused = list(authorization);
      assertThat(refused.getStatusCode().value()).as(String.valueOf(authorization)).isEqualTo(401);
      assertThat(refused.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
      assertThat(refused.getHeaders().getContentType())
          .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(refused.getBody()).contains("Sign in with Google");
    }
    assertThat(upload(null, "2026-10.pdf", statement()).getStatusCode().value()).isEqualTo(401);
    assertThat(statements).isEmptyDirectory();
  }

  @Test
  void upload_statement_landsInThePersonsFolderAndIsListedToThemAlone() throws IOException {
    assertThat(list().getBody()).isEqualTo("[]");
    ResponseEntity<String> created = upload("my statement.pdf", statement());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(created.getBody()).contains("\"fileName\":\"2026-10.pdf\"");
    assertThat(Files.readAllBytes(mine().resolve("2026-10.pdf"))).isEqualTo(statement());
    assertThat(list().getBody()).contains("\"fileName\":\"2026-10.pdf\"")
        .contains("\"sizeBytes\":" + statement().length);
    assertThat(list(bearer(OTHER_USER)).getBody()).as("another person's list").isEqualTo("[]");
  }

  @Test
  void upload_sameMonthTwice_isRefusedNamingTheFile() throws IOException {
    upload("2026-10.pdf", statement());
    ResponseEntity<String> again = upload("2026-10.pdf", statement());
    assertThat(again.getStatusCode().value()).isEqualTo(400);
    assertThat(again.getBody()).contains("2026-10.pdf already covers 2026-10");
  }

  @Test
  void upload_pathTraversalName_landsInTheFolderOnly() throws IOException {
    ResponseEntity<String> created = upload("../../etc/x.pdf", statement());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(mine().toFile().list()).containsExactly("2026-10.pdf");
    assertThat(statements.toFile().list()).containsExactly(USER);
    assertThat(statements.resolve("x.pdf")).doesNotExist();
  }

  @Test
  void upload_notAPdf_isRefused() {
    ResponseEntity<String> refused =
        upload("2026-10.pdf", "<html>".getBytes(StandardCharsets.UTF_8));
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(refused.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(refused.getBody()).contains("2026-10.pdf is not a PDF file");
    assertThat(statements).as("a refused upload creates no folder").isEmptyDirectory();
  }

  @Test
  void upload_overTheSizeLimit_is413() {
    byte[] content = new byte[(int) maxFileSize.toBytes() + 1024];
    Arrays.fill(content, (byte) ' ');
    System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 5);
    ResponseEntity<String> refused = upload("2026-10.pdf", content);
    assertThat(refused.getStatusCode().value()).isEqualTo(413);
    assertThat(refused.getBody()).contains("upload limit");
    assertThat(statements).isEmptyDirectory();
  }

  @Test
  void delete_listedStatement_isRemovedFromTheFolder() throws IOException {
    Files.createDirectories(mine());
    Files.copy(PdfStatementParserTest.FIXTURE, mine().resolve("2026-09.pdf"));
    assertThat(delete("2026-09.pdf").getStatusCode().value()).isEqualTo(204);
    assertThat(mine()).isEmptyDirectory();
    assertThat(list().getBody()).isEqualTo("[]");
  }

  @Test
  void delete_unlistedFile_isRefused() {
    ResponseEntity<String> refused = delete("2026-10.pdf");
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(refused.getBody()).contains("2026-10.pdf is not a loaded statement");
  }
}
