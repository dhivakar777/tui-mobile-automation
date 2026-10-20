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
            visible = afterScroll;
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

    private boolean isSelected(Tab tab) {
        return Boolean.parseBoolean(driver.findElement(byTag(tab.tag)).getAttribute("selected"));
    }

    /**
     * Card names on screen with their vertical position. The position matters for end-of-list detection:
     * a tall card's name can stay visible after a real scroll, so only "same cards at the same place" means the
     * list did not move.
     */
    private Map<Integer, VisibleName> visibleCardNames() {
        for (int attempt = 1; ; attempt++) {
            try {
                Map<Integer, VisibleName> visible = new TreeMap<>();
                for (WebElement name : driver.findElements(CARD_NAMES)) {
                    visible.put(cardIndex(name), new VisibleName(name.getText(), name.getRect().y));
                }
                return visible;
            } catch (StaleElementReferenceException e) {
                // The lazy list recomposed while it was being read; read the screen again.
                if (attempt == STALE_READ_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private record VisibleName(String text, int y) {
    }

    /**
     * Drags the list up by 60% of its height; the overlap between screens ensures no card name is
     * skipped (80% occasionally skipped one). A W3C touch drag is used instead of "mobile: scrollGesture",
     * which takes ~2.5 s per call on this Compose list and always reports that it cannot scroll further.
     * The pause before release stops the list from flinging.
     */
    private void scrollDown() {
        Rectangle list = waitForVisible(LIST).getRect();
        int x = list.x + list.width / 2;
        int startY = list.y + (int) (list.height * 0.8);
        int endY = list.y + (int) (list.height * 0.2);
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
