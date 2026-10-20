package com.tui.automation.stepdefinitions;

import com.tui.automation.pages.ListingCard;
import com.tui.automation.pages.ListingsPage;
import com.tui.automation.pages.ListingsPage.Tab;
import com.tui.automation.utilities.ListingsData;
import com.tui.automation.utilities.TestDataReader;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Locale;

import static org.testng.Assert.assertEquals;

public class ListingsSteps {

    private final ListingsData data = TestDataReader.read("listings.json", ListingsData.class);

    @When("I select the {string} tab")
    public void iSelectTheTab(String tabName) {
        new ListingsPage().selectTab(toTab(tabName));
    }

    @Then("only the {string} tab is selected")
    public void onlyTheTabIsSelected(String tabName) {
        assertEquals(new ListingsPage().selectedTabs(), List.of(toTab(tabName)), "Selected tabs");
    }

    @Then("the list shows exactly the expected {string} results")
    public void theListShowsExactlyTheExpectedResults(String tabName) {
        List<String> expected = data.namesByTab().get(tabName);
        if (expected == null) {
            throw new IllegalArgumentException("No expected names for tab '" + tabName + "' in listings.json");
        }
        // TestNG reports either the size difference or the first differing card, which pinpoints a mismatch.
        assertEquals(new ListingsPage().allCardNames(), expected, "Card names on the " + tabName + " tab, in display order");
    }

    @Then("the first hotel cards show the expected details")
    public void theFirstHotelCardsShowTheExpectedDetails() {
        ListingsPage listingsPage = new ListingsPage();
        List<ListingCard> expected = data.hotelCards();
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(listingsPage.card(i), expected.get(i), "Hotel card " + i);
        }
    }

    private static Tab toTab(String tabName) {
        return Tab.valueOf(tabName.toUpperCase(Locale.ROOT));
    }
}
