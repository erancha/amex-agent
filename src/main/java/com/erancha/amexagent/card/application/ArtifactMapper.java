package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shapes transactions into the data parts docs/a2a-contract.md specifies, 25 rows per part. */
public final class ArtifactMapper {
  public static final int BATCH_SIZE = 25;

  private ArtifactMapper() {}

  public static List<Map<String, Object>> batches(Statement s) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<Transaction> rows = s.transactions();
    int from = 0;
    do {
      List<Map<String, Object>> chunk = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()))
          .stream().map(ArtifactMapper::transaction).toList();
      Map<String, Object> part = new LinkedHashMap<>();
      part.put("month", s.billingMonth().toString());
      part.put("currency", s.currency());
      part.put("transactions", chunk);
      out.add(part);
      from += BATCH_SIZE;
    } while (from < rows.size());
    return out;
  }

  public static Map<String, Object> transaction(Transaction t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", t.id());
    m.put("transactionDate", t.transactionDate().toString());
    m.put("chargeDate", t.chargeDate().toString());
    m.put("merchant", t.merchant());
    m.put("rawMerchant", t.rawMerchant());
    m.put("amount", t.amount().doubleValue());
    m.put("originalAmount", t.originalAmount() == null ? null : t.originalAmount().doubleValue());
    m.put("originalCurrency", t.originalCurrency());
    m.put("standingOrder", t.standingOrder());
    m.put("abroad", t.abroad());
    m.put("installment", t.installment() == null ? null
        : Map.of("number", t.installment().number(), "total", t.installment().total()));
    m.put("category", t.category().wireValue());
    m.put("confidence", t.confidence().wireValue());
    return m;
  }
}
