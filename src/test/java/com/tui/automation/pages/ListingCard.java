package com.tui.automation.pages;

/** Text content of one listing card, as shown on screen and as expected in test data. */
public record ListingCard(String name, String destination, String rating, String boardType, String price) {
}
