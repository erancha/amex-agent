package com.erancha.amexagent.card.api;

import java.time.Instant;

/** One statement file in the folder the agent reads. */
public record StoredStatement(String fileName, long sizeBytes, Instant modified) {}
