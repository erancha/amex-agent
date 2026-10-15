package com.erancha.amexagent.statements;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.card.infra.PdfStatementParserTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
 * Verifies the statements page and its API over real HTTP with auth on: both need no token, an
 * upload lands in the configured folder under the download name of its content's month whatever
 * it was called and is listed, a path in the upload's name reaches no other folder, a listed file
 * can be deleted, and each refusal (not a PDF, month already loaded, oversized, deleting an
 * unlisted file) is a problem document naming the cause.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "APP_A2A_TOKEN=" + StatementsEndpointTest.TOKEN})
@ActiveProfiles(E2E_PROFILE)
class StatementsEndpointTest {
  static final String TOKEN = "page-token";
  private static Path statements;

  @DynamicPropertySource
  static void statementsDir(DynamicPropertyRegistry registry) throws IOException {
    statements = Files.createTempDirectory("statements");
    registry.add("APP_STATEMENTS_DIR", statements::toString);
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

  private ResponseEntity<String> list() {
    return client().get().uri("/api/statements").retrieve()
        .onStatus(status -> true, (req, res) -> {})
        .toEntity(String.class);
  }

  private ResponseEntity<String> upload(String fileName, byte[] content) {
    MultipartBodyBuilder parts = new MultipartBodyBuilder();
    parts.part("file", content).filename(fileName).contentType(MediaType.APPLICATION_PDF);
    return client().post().uri("/api/statements").contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts.build()).retrieve().onStatus(status -> true, (req, res) -> {})
        .toEntity(String.class);
  }

  private ResponseEntity<String> delete(String fileName) {
    return client().delete().uri("/api/statements/{fileName}", fileName).retrieve()
        .onStatus(status -> true, (req, res) -> {}).toEntity(String.class);
  }

  private static byte[] statement() throws IOException {
    return Files.readAllBytes(PdfStatementParserTest.FIXTURE);
  }

  @Test
  void page_isPublic() {
    ResponseEntity<String> page = client().get().uri("/statements.html").retrieve()
        .toEntity(String.class);
    assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(page.getBody()).contains("/api/statements");
  }

  @Test
  void upload_statement_landsInTheFolderAndIsListed() throws IOException {
    assertThat(list().getBody()).isEqualTo("[]");
    ResponseEntity<String> created = upload("my statement.pdf", statement());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(created.getBody()).contains("\"fileName\":\"2026-10.pdf\"");
    assertThat(Files.readAllBytes(statements.resolve("2026-10.pdf"))).isEqualTo(statement());
    assertThat(list().getBody()).contains("\"fileName\":\"2026-10.pdf\"")
        .contains("\"sizeBytes\":" + statement().length);
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
    assertThat(statements.toFile().list()).containsExactly("2026-10.pdf");
    assertThat(statements.getParent().resolve("x.pdf")).doesNotExist();
  }

  @Test
  void upload_notAPdf_isRefused() {
    ResponseEntity<String> refused =
        upload("2026-10.pdf", "<html>".getBytes(StandardCharsets.UTF_8));
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(refused.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(refused.getBody()).contains("2026-10.pdf is not a PDF file");
    assertThat(statements).isEmptyDirectory();
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
    Files.copy(PdfStatementParserTest.FIXTURE, statements.resolve("2026-09.pdf"));
    assertThat(delete("2026-09.pdf").getStatusCode().value()).isEqualTo(204);
    assertThat(statements).isEmptyDirectory();
    assertThat(list().getBody()).isEqualTo("[]");
  }

  @Test
  void delete_unlistedFile_isRefused() {
    ResponseEntity<String> refused = delete("2026-10.pdf");
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(refused.getBody()).contains("2026-10.pdf is not a loaded statement");
  }
}
