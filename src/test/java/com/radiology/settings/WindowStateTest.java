package com.radiology.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

class WindowStateTest {
    @Test
    void roundTripsWindowBoundsAndMaximizedState() {
        WindowState expected = new WindowState(120, 80, 1280, 900, false);
        Properties properties = new Properties();

        expected.writeTo(properties);

        assertEquals(expected, WindowState.from(properties));
    }

    @Test
    void centersWindowOnAvailableScreenWhenSavedScreenIsDisconnected() {
        WindowState saved = new WindowState(5000, 5000, 1280, 900, false);
        Rectangle2D screen = new Rectangle2D(0, 0, 1920, 1080);

        WindowState restored = saved.fitToScreens(List.of(screen));

        assertEquals(320, restored.x());
        assertEquals(90, restored.y());
        assertFalse(restored.maximized());
    }

    @Test
    void preservesWindowBoundsWhenTheyStillFitAnAvailableScreen() {
        WindowState saved = new WindowState(-1920, 40, 1200, 800, true);
        Rectangle2D screen = new Rectangle2D(-1920, 0, 1920, 1080);

        assertEquals(saved, saved.fitToScreens(List.of(screen)));
        assertTrue(saved.maximized());
    }

    @Test
    void defaultsToMaximizedForMissingOrInvalidWindowSettings() {
        Properties properties = new Properties();
        properties.setProperty("windowWidth", "not-a-number");
        properties.setProperty("windowHeight", "100");

        assertEquals(WindowState.defaults(), WindowState.from(properties));
    }
}
