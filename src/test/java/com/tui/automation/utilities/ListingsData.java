package com.tui.automation.utilities;

import com.tui.automation.pages.ListingCard;

import java.util.List;
import java.util.Map;

/**
 * Shape of testdata/listings.json. Values were extracted from the APK's bundled
 * assets/hotel-results.json and assets/holiday-results.json.
 */
public record ListingsData(Map<String, List<String>> namesByTab, List<ListingCard> hotelCards) {
}
