package com.erancha.amexagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Boots amex-agent: Spring context, A2A transport, statement folder, categorizer. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AmexAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AmexAgentApplication.class, args);
  }
}
