package com.erancha.amexagent.card.infra;

import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;

/**
 * What one model call costs, and the model_call log line that records it: the Java twin of
 * model_costs.py in the Python Docker repos, with the same prices and the same line, which
 * scripts/lib/docker_helper.py, behind docker-helper.sh --costs, sums.
 *
 * Core responsibilities:
 * - PRICE_PER_MILLION: the listed prices of every model the Docker repos run
 * - requirePrice: refuses a provider:model whose prices are not listed, before it is called
 * - billed: the tokens of one call at each rate, from Spring AI's usage
 * - log: one JSON line per answered call on the model_call logger
 */
final class ModelCosts {
  static final String LOGGER_NAME = "model_call";
  private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

  // Dollars per million input, output, cache-read and cache-write tokens, keyed by the model id of
  // a provider:model setting: PRICE_PER_MILLION of model_costs.py, which a price change updates
  // along with this table. Claude Haiku 5.5's prices hold for prompts up to 100K tokens, and the
  // GPT-6 models' for prompts up to 272K.
  private static final Map<String, double[]> PRICE_PER_MILLION = Map.of(
      "claude-opus-5", new double[] {5.0, 25.0, 0.5, 6.25},
      "claude-sonnet-4-6", new double[] {3.0, 15.0, 0.3, 3.75},
      "claude-haiku-5-5", new double[] {0.10, 0.50, 0.01, 0.125},
      "claude-haiku-4-5-20251001", new double[] {1.0, 5.0, 0.1, 1.25},
      "gpt-6.2-astra", new double[] {8.0, 40.0, 0.4, 0.0},
      "gpt-5-mini", new double[] {0.25, 2.0, 0.025, 0.0},
      "gpt-5-nano", new double[] {0.05, 0.40, 0.005, 0.0});

  /**
   * Billed tokens of one call; reasoning counts as output. input leaves out the tokens read from
   * or written to the prompt cache, which are billed at their own rates.
   */
  record Billed(long input, long output, long cacheRead, long cacheWrite) {}

  private ModelCosts() {}

  private static String modelId(String spec) {
    return spec.substring(spec.indexOf(':') + 1);
  }

  static void requirePrice(String spec) {
    if (!PRICE_PER_MILLION.containsKey(modelId(spec))) {
      throw new IllegalStateException("'" + spec + "' has no listed price; add its prices per "
          + "million tokens to PRICE_PER_MILLION in ModelCosts.java and model_costs.py");
    }
  }

  /**
   * Spring AI reports Anthropic's prompt tokens without the cache, and OpenAI's with the cached
   * prefix inside them; a cache count the provider leaves out is none.
   */
  static Billed billed(String provider, Usage usage) {
    Integer prompt = usage.getPromptTokens();
    Integer completion = usage.getCompletionTokens();
    if (prompt == null || completion == null) {
      throw new IllegalStateException("the " + provider + " reply carries no token usage");
    }
    Long read = usage.getCacheReadInputTokens();
    Long written = usage.getCacheWriteInputTokens();
    long cacheRead = read == null ? 0 : read;
    long cacheWrite = written == null ? 0 : written;
    long input = provider.equals("openai") ? prompt - cacheRead : prompt;
    return new Billed(input, completion, cacheRead, cacheWrite);
  }

  static double cost(String spec, Billed billed) {
    double[] price = PRICE_PER_MILLION.get(modelId(spec));
    return (billed.input() * price[0] + billed.output() * price[1]
        + billed.cacheRead() * price[2] + billed.cacheWrite() * price[3]) / 1_000_000;
  }

  /**
   * The line model_costs.py logs, field for field. The kind is a constant of the caller and the
   * spec a listed provider:model, so neither holds a character JSON would escape.
   */
  static String line(String kind, String spec, Billed billed, double seconds) {
    return String.format(Locale.ROOT, "{\"event\": \"model_call\", \"kind\": \"%s\", "
        + "\"model\": \"%s\", \"input_tokens\": %d, \"output_tokens\": %d, "
        + "\"cache_read_tokens\": %d, \"cache_write_tokens\": %d, \"seconds\": %.2f, "
        + "\"cost\": %s}", kind, spec, billed.input(), billed.output(), billed.cacheRead(),
        billed.cacheWrite(), seconds, cost(spec, billed));
  }

  /** kind names what the call was for, as the --costs table groups it. */
  static void log(String kind, String spec, Billed billed, double seconds) {
    LOG.info(line(kind, spec, billed, seconds));
  }
}
