package com.erancha.amexagent.card.api;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The taxonomy published in the skill description; wire values are the lower-case names. Each
 * category names what the money buys, never how it is paid, and carries the guide the model
 * classifies by.
 */
public enum Category {
  GROCERIES("supermarkets, grocery stores, bakeries, butchers, greengrocers"),
  DINING("restaurants, cafes, bars, takeaway, food delivery"),
  TRANSPORT("fuel, public transport, taxis, tolls, parking, car repairs, car licensing, car insurance"),
  UTILITIES("electricity, water, gas, phone, internet, water-filter rental"),
  HOUSING("rent, property tax (arnona), building committee, home and appliance insurance, appliances fixed"
      + " to the building such as air conditioners and water heaters, home repairs, hardware stores"),
  HEALTH("clinics, doctors, dentists, pharmacies, gyms and fitness clubs, health insurance"),
  INSURANCE("only a policy that protects no other category's purpose, such as life insurance, or one whose"
      + " purpose the statement does not tell; car, health, home and appliance policies go to"
      + " transport, health and housing"),
  EDUCATION("schools, kindergartens, childcare, courses, children's activities"),
  SHOPPING("clothing, electronics, furniture, online marketplaces, appliances that move with you"),
  ENTERTAINMENT("streaming and TV, cinema, shows, games, hobbies, leisure clubs"),
  TRAVEL("flights, hotels, car rental abroad, travel agencies"),
  GIFTS("gift cards, flowers, presents, donations"),
  OTHER("bank and card fees, money transfers, anything that fits no other category"),
  UNCATEGORIZED("the business, or what it sells, cannot be told from the statement text");

  private final String guide;

  Category(String guide) {
    this.guide = guide;
  }

  /** The kinds of merchants this category holds, as told to the model. */
  public String guide() {
    return guide;
  }

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static List<String> wireValues() {
    return Arrays.stream(values()).map(Category::wireValue).toList();
  }

  /**
   * Maps a model-produced label to a category; an omitted label (null) or anything outside the
   * taxonomy is UNCATEGORIZED.
   */
  public static Category fromModelValue(String value) {
    if (value == null) {
      return UNCATEGORIZED;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(c -> c.name().equals(normalized))
        .findFirst()
        .orElse(UNCATEGORIZED);
  }
}
