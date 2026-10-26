package com.tui.automation.pages;

import io.appium.java_client.AppiumBy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Pause;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ListingsPage extends BasePage {

    private static final Logger LOG = LogManager.getLogger(ListingsPage.class);

    public enum Tab {
        ALL("top_app_bar_all_tab"),
        HOTELS("top_app_bar_hotels_tab"),
        HOLIDAYS("top_app_bar_holidays_tab");

        private final String tag;

        Tab(String tag) {
            this.tag = tag;
        }
    }

    private static final By SCREEN_ROOT = byTag("search_result_screen_root");
    private static final By LIST = byTag("search_result_content_screen_root");
    private static final By CARD_NAMES = AppiumBy.androidUIAutomator(
            "new UiSelector().resourceIdMatches(\"content_card_hotel_name_[0-9]+\")");
    private static final String CARD_TAG_PREFIX = "content_card_";
    private static final int MAX_SCROLLS = 60;
    private static final int STALE_READ_ATTEMPTS = 3;
    private static final int MAX_RECOVERY_DRAGS = 3;
    private static final Duration SCROLL_DURATION = Duration.ofMillis(400);
    private static final Duration SCROLL_SETTLE = Duration.ofMillis(150);

    public boolean isDisplayed() {
        return isVisible(SCREEN_ROOT);
    }

    public void selectTab(Tab tab) {
        waitForVisible(byTag(tab.tag)).click();
        wait.until(driver -> isSelected(tab));
    }

    public List<Tab> selectedTabs() {
        return Arrays.stream(Tab.values()).filter(this::isSelected).toList();
    }

    /** Scrolls through the whole list and returns every card name in display order. */
    public List<String> allCardNames() {
        Map<Integer, String> namesByIndex = new TreeMap<>();
        Map<Integer, VisibleName> visible = visibleCardNames();
        for (int scrolls = 0; scrolls < MAX_SCROLLS; scrolls++) {
            visible.forEach((index, name) -> namesByIndex.put(index, name.text()));
            scrollDown();
            Map<Integer, VisibleName> afterScroll = visibleCardNames();
            LOG.debug("Visible cards before scroll {} after scroll {}", visible, afterScroll);
            if (afterScroll.equals(visible)) {
                return new ArrayList<>(namesByIndex.values());
            }
            visible = recoverSkippedCards(namesByIndex, afterScroll);
        }
        throw new IllegalStateException("List did not reach its end within " + MAX_SCROLLS + " scrolls");
    }

    /** Scrolls until the card at {@code index} is fully on screen (its price is the last field), then reads it. */
    public ListingCard card(int index) {
        for (int scrolls = 0; scrolls < MAX_SCROLLS; scrolls++) {
            List<WebElement> price = driver.findElements(cardField("price_button", index));
            if (!price.isEmpty()) {
                return new ListingCard(
                        cardText("hotel_name", index),
                        cardText("destination", index),
                        cardText("rating", index),
                        cardText("board_type", index),
                        price.get(0).findElement(AppiumBy.className("android.widget.TextView")).getText());
            }
            Map<Integer, VisibleName> beforeScroll = visibleCardNames();
            scrollDown();
            if (visibleCardNames().equals(beforeScroll)) {
                break;
            }
        }
        throw new IllegalStateException("Listing card " + index + " was not found after scrolling the list");
    }

    /**
     * Under heavy load a single drag can carry a card past the screen between two reads. If the cards now on screen
     * start more than one index after the last card collected, drags back a short way until the missed cards have
     * been read. Returns what is on screen afterwards.
     */
    private Map<Integer, VisibleName> recoverSkippedCards(Map<Integer, String> collected, Map<Integer, VisibleName> onScreen) {
        for (int attempt = 0; attempt < MAX_RECOVERY_DRAGS && skipsCards(collected, onScreen); attempt++) {
            LOG.debug("Cards skipped between {} and {}; scrolling back", lastIndex(collected), firstIndex(onScreen));
            scroll(0.35, 0.65);
            onScreen = visibleCardNames();
            onScreen.forEach((index, name) -> collected.put(index, name.text()));
        }
        return onScreen;
    }

    private static boolean skipsCards(Map<Integer, String> collected, Map<Integer, VisibleName> onScreen) {
        return !collected.isEmpty() && !onScreen.isEmpty() && firstIndex(onScreen) > lastIndex(collected) + 1;
    }

    private static int lastIndex(Map<Integer, String> collected) {
        return Collections.max(collected.keySet());
    }

    private static int firstIndex(Map<Integer, VisibleName> onScreen) {
        return Collections.min(onScreen.keySet());
    }

    private boolean isSelected(Tab tab) {
        return Boolean.parseBoolean(driver.findElement(byTag(tab.tag)).getAttribute("selected"));
    }

    /**
     * Card names on screen with their vertical position. The position matters for end-of-list detection:
     * a tall card's name can stay visible after a real scroll, so only "same cards at the same place" means the
     * list did not move. Reads that the lazy list invalidates part-way through are repeated.
     */
    private Map<Integer, VisibleName> visibleCardNames() {
        for (int attempt = 1; ; attempt++) {
            try {
                Map<Integer, VisibleName> visible = new TreeMap<>();
                for (WebElement name : driver.findElements(CARD_NAMES)) {
                    int index = cardIndex(name);
                    VisibleName read = new VisibleName(name.getText(), name.getRect().y);
                    // The id and the text are separate requests; if the lazy list reused this node for another card
                    // in between, the text belongs to a different card. Treat that like a stale read.
                    if (cardIndex(name) != index) {
                        throw new StaleElementReferenceException("Card node was reused while it was being read");
                    }
                    visible.put(index, read);
                }
                return visible;
            } catch (StaleElementReferenceException e) {
                // The lazy list recomposed or reused a node while it was being read; read the screen again.
                LOG.debug("Re-reading visible cards (attempt {}): {}", attempt, e.getMessage().lines().findFirst().orElse(""));
                if (attempt == STALE_READ_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private record VisibleName(String text, int y) {
    }

    /**
     * Drags the list up by 60% of its height; the overlap between screens keeps card names from being skipped.
     */
    private void scrollDown() {
        scroll(0.8, 0.2);
    }

    /**
     * Drags a finger from one fraction of the list's height to another: upwards scrolls the list down, downwards
     * scrolls it back. The pause before release stops the list from flinging.
     */
    private void scroll(double fromFraction, double toFraction) {
        Rectangle list = waitForVisible(LIST).getRect();
        int x = list.x + list.width / 2;
        int startY = list.y + (int) (list.height * fromFraction);
        int endY = list.y + (int) (list.height * toFraction);
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence drag = new Sequence(finger, 0)
                .addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, startY))
                .addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
                .addAction(finger.createPointerMove(SCROLL_DURATION, PointerInput.Origin.viewport(), x, endY))
                .addAction(new Pause(finger, SCROLL_SETTLE))
                .addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(drag));
    }

    private static By cardField(String field, int index) {
        return byTag(CARD_TAG_PREFIX + field + "_" + index);
    }

    private String cardText(String field, int index) {
        return driver.findElement(cardField(field, index)).getText();
    }

    private static int cardIndex(WebElement cardName) {
        String resourceId = cardName.getAttribute("resource-id");
        return Integer.parseInt(resourceId.substring(resourceId.lastIndexOf('_') + 1));
    }
}
