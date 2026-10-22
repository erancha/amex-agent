package com.erancha.amexagent.card;

import com.erancha.amexagent.a2a.A2AProperties;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.api.StatementSource;
import com.erancha.amexagent.card.application.MerchantCategorizer;
import com.erancha.amexagent.card.application.TransactionsAgentExecutor;
import com.erancha.amexagent.card.application.TransactionsService;
import com.erancha.amexagent.card.infra.PdfStatementLayout;
import com.erancha.amexagent.card.infra.PdfStatementParser;
import com.erancha.amexagent.card.infra.StatementFolders;
import com.erancha.amexagent.statements.StatementsEndpoint;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Assembles the card pipeline: the per-person statement folders with their PDF parser, the
 * categorizer, the service, and the A2A executor. The folders are both the pipeline's statement
 * source and the store the statements page loads into.
 */
@Configuration
public class CardConfiguration {
  @Bean
  public StatementFolders statementFolders(A2AProperties props) {
    return new StatementFolders(props.statements().dir(),
        new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL));
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
  public AgentExecutor transactionsAgentExecutor(TransactionsService service,
      UserResolver users) {
    return new TransactionsAgentExecutor(service, StatementsEndpoint.PAGE, users);
  }
}
