package com.erancha.amexagent.card;

import com.erancha.amexagent.a2a.A2AProperties;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.api.StatementSource;
import com.erancha.amexagent.card.application.MerchantCategorizer;
import com.erancha.amexagent.card.application.TransactionsAgentExecutor;
import com.erancha.amexagent.card.application.TransactionsService;
import com.erancha.amexagent.card.infra.ExcelStatementParser;
import com.erancha.amexagent.card.infra.StatementFolder;
import com.erancha.amexagent.card.infra.ExcelStatementLayout;
import com.erancha.amexagent.card.infra.PdfStatementLayout;
import com.erancha.amexagent.card.infra.PdfStatementParser;
import com.erancha.amexagent.statements.StatementsEndpoint;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Assembles the card pipeline: the statement folder with its PDF and Excel parsers, the
 * categorizer, the service, and the A2A executor. The folder is both the pipeline's statement
 * source and the store the statements page loads into.
 */
@Configuration
public class CardConfiguration {
  @Bean
  public StatementFolder statementFolder(A2AProperties props) {
    return new StatementFolder(props.statements().dir(), Map.of(
        ".pdf", new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL),
        ".xlsx", new ExcelStatementParser(ExcelStatementLayout.AMEX_ISRAEL)));
  }

  @Bean
  public MerchantCategorizer merchantCategorizer(MerchantClassifier classifier,
      A2AProperties props) {
    A2AProperties.Categorizer c = props.categorizer();
    return new MerchantCategorizer(classifier, c.highConfidence(), c.mediumConfidence(),
        c.batchSize());
  }

  @Bean
  public TransactionsService transactionsService(StatementSource source,
      MerchantCategorizer categorizer) {
    return new TransactionsService(source, categorizer);
  }

  @Bean
  @Profile("!tck")
  public AgentExecutor transactionsAgentExecutor(TransactionsService service) {
    return new TransactionsAgentExecutor(service, StatementsEndpoint.PAGE);
  }
}
