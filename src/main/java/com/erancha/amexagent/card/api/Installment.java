package com.erancha.amexagent.card.api;

/** "תשלום N מתוך M" from a statement row's installment detail. */
public record Installment(int number, int total) {}
