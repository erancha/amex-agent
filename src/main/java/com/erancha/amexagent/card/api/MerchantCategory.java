package com.erancha.amexagent.card.api;

/** A classifier's answer for one raw merchant string, before taxonomy and range validation. */
public record MerchantCategory(String rawMerchant, String merchant, String category,
    double confidence) {}
