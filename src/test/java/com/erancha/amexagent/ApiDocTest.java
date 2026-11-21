package com.erancha.amexagent;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.auth.TestTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Verifies that docs/api.md names every HTTP route the agent declares, and no route it does not.
 *
 * The running context is the source of truth: each request mapping of the agent's own
 * controllers, read from the application's RequestMappingHandlerMapping, as its methods and path
 * patterns. Spring Boot's own handlers (the error controller, the actuator) belong to the
 * framework, not the product, and are not compared. The document names a route in backticks as
 * `METHOD /path`, the path exactly as the controller declares it. The context is built with the
 * annotations of AmexAgentApplicationTest, so the two share one cached context.
 */
@SpringBootTest(properties = {"APP_STATEMENTS_DIR=src/test/resources/statements"})
@ActiveProfiles(E2E_PROFILE)
class ApiDocTest {
  private static final Set<RequestMethod> HTTP_METHODS = Set.of(RequestMethod.GET,
      RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
  private static final Pattern DOC_ROUTE =
      Pattern.compile("`(GET|POST|PUT|PATCH|DELETE) (/[^`\\s]*)`");
  private static final Path API_DOC = Path.of("docs/api.md");
  private static final String APP_PACKAGE = AmexAgentApplication.class.getPackageName();

  @DynamicPropertySource
  static void auth(DynamicPropertyRegistry registry) {
    TestTokens.register(registry);
  }

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  void apiDocNamesEveryDeclaredRoute() throws IOException {
    Set<String> declared = declaredRoutes();
    Set<String> documented = documentedRoutes(Files.readString(API_DOC));
    List<String> problems = new ArrayList<>();
    for (String route : declared) {
      if (!documented.contains(route)) {
        problems.add("undocumented in " + API_DOC + ": `" + route + "`");
      }
    }
    for (String route : documented) {
      if (!declared.contains(route)) {
        problems.add(API_DOC + " names `" + route + "`, which the agent does not declare");
      }
    }
    assertThat(problems).as(String.join("\n", problems)).isEmpty();
  }

  private Set<String> declaredRoutes() {
    Set<String> routes = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
        : mappings.getHandlerMethods().entrySet()) {
      if (!entry.getValue().getBeanType().getPackageName().startsWith(APP_PACKAGE)) {
        continue;
      }
      RequestMappingInfo info = entry.getKey();
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      assertThat(methods).as("%s declares no HTTP method", entry.getValue()).isNotEmpty();
      for (RequestMethod method : methods) {
        if (HTTP_METHODS.contains(method)) {
          for (String path : info.getPatternValues()) {
            routes.add(method + " " + path);
          }
        }
      }
    }
    return routes;
  }

  private static Set<String> documentedRoutes(String doc) {
    Set<String> routes = new TreeSet<>();
    Matcher matcher = DOC_ROUTE.matcher(doc);
    while (matcher.find()) {
      routes.add(matcher.group(1) + " " + matcher.group(2));
    }
    return routes;
  }
}
