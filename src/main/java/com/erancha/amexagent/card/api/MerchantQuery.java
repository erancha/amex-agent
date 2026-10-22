package com.erancha.amexagent.card.api;

import org.jspecify.annotations.Nullable;

/**
 * What the categorizer asks the classifier about one merchant: its raw statement string and the
 * issuer's own sector label for the business, null where the statement prints none.
 */
public record MerchantQuery(String rawMerchant, @Nullable String sector) {}
