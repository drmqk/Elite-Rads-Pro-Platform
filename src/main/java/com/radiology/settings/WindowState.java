package com.radiology.settings;

import java.util.List;
import java.util.Properties;
import javafx.geometry.Rectangle2D;

public record WindowState(double x, double y, double width, double height, boolean maximized) {
    private static final double DEFAULT_WIDTH = 1200;
    private static final double DEFAULT_HEIGHT = 800;
    private static final double MIN_WIDTH = 640;
    private static final double MIN_HEIGHT = 480;

    public static WindowState defaults() {
        return new WindowState(-1, -1, DEFAULT_WIDTH, DEFAULT_HEIGHT, true);
    }

    public static WindowState from(Properties properties) {
        WindowState defaults = defaults();
        double x = readNumber(properties, "windowX", defaults.x);
        double y = readNumber(properties, "windowY", defaults.y);
        double width = readNumber(properties, "windowWidth", defaults.width);
        double height = readNumber(properties, "windowHeight", defaults.height);
        boolean maximized = Boolean.parseBoolean(properties.getProperty("windowMaximized",
                Boolean.toString(defaults.maximized)));
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return defaults;
        }
        return new WindowState(x, y, width, height, maximized);
    }

    public WindowState fitToScreens(List<Rectangle2D> screens) {
        if (screens.isEmpty()) {
            return this;
        }
        for (Rectangle2D screen : screens) {
            if (contains(screen, x, y, width, height)) {
                return this;
            }
        }

        Rectangle2D target = screens.get(0);
        for (Rectangle2D screen : screens) {
            if (intersects(screen, x, y, width, height)) {
                target = screen;
                break;
            }
        }

        double fittedWidth = Math.min(Math.max(MIN_WIDTH, width), target.getWidth());
        double fittedHeight = Math.min(Math.max(MIN_HEIGHT, height), target.getHeight());
        double fittedX;
        double fittedY;
        if (intersects(target, x, y, width, height)) {
            fittedX = clamp(x, target.getMinX(), target.getMaxX() - fittedWidth);
            fittedY = clamp(y, target.getMinY(), target.getMaxY() - fittedHeight);
        } else {
            fittedX = target.getMinX() + (target.getWidth() - fittedWidth) / 2;
            fittedY = target.getMinY() + (target.getHeight() - fittedHeight) / 2;
        }
        return new WindowState(fittedX, fittedY, fittedWidth, fittedHeight, maximized);
    }

    public WindowState withMaximized(boolean value) {
        return new WindowState(x, y, width, height, value);
    }

    public void writeTo(Properties properties) {
        properties.setProperty("windowX", Double.toString(x));
        properties.setProperty("windowY", Double.toString(y));
        properties.setProperty("windowWidth", Double.toString(width));
        properties.setProperty("windowHeight", Double.toString(height));
        properties.setProperty("windowMaximized", Boolean.toString(maximized));
    }

    private static double readNumber(Properties properties, String key, double fallback) {
        try {
            double value = Double.parseDouble(properties.getProperty(key));
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    private static boolean contains(Rectangle2D bounds, double x, double y, double width, double height) {
        return x >= bounds.getMinX() && y >= bounds.getMinY()
                && x + width <= bounds.getMaxX() && y + height <= bounds.getMaxY();
    }

    private static boolean intersects(Rectangle2D bounds, double x, double y, double width, double height) {
        return x < bounds.getMaxX() && x + width > bounds.getMinX()
                && y < bounds.getMaxY() && y + height > bounds.getMinY();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
