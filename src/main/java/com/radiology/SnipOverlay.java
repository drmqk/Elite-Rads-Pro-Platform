package com.radiology;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class SnipOverlay {
    private static final Logger logger = LoggerFactory.getLogger(SnipOverlay.class);
    private final BiConsumer<Image, RuntimeException> onComplete;
    private double startX;
    private double startY;

    public SnipOverlay(BiConsumer<Image, RuntimeException> onComplete) {
        this.onComplete = onComplete;
    }

    public void show() {
        if (!Platform.isFxApplicationThread()) {
            onComplete.accept(null, new IllegalStateException("Screen capture must start on the JavaFX application thread."));
            return;
        }

        List<Screen> screens;
        Rectangle2D bounds;
        try {
            screens = Screen.getScreens();
            if (screens.isEmpty()) {
                complete(null, new IllegalStateException("No screens are available for capture."));
                return;
            }
            bounds = getScreenBounds(screens);
        } catch (RuntimeException ex) {
            complete(null, ex);
            return;
        }
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            complete(null, new IllegalStateException("The available screen bounds are invalid."));
            return;
        }
        Stage stage;
        try {
            stage = new Stage();
        } catch (RuntimeException ex) {
            complete(null, ex);
            return;
        }
        AtomicBoolean completed = new AtomicBoolean();
        try {
            Robot robot = new Robot();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());

            Pane root = new Pane();
            root.setStyle("-fx-background-color: rgba(0, 0, 0, 0.3);");

            Canvas canvas = new Canvas(bounds.getWidth(), bounds.getHeight());
            GraphicsContext gc = canvas.getGraphicsContext2D();
            root.getChildren().add(canvas);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            scene.setOnMousePressed(event -> {
                startX = event.getX();
                startY = event.getY();
            });
            scene.setOnMouseDragged(event -> {
                gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                gc.setStroke(Color.LIME);
                gc.setLineWidth(2);
                double x = Math.min(event.getX(), startX);
                double y = Math.min(event.getY(), startY);
                gc.strokeRect(x, y, Math.abs(event.getX() - startX), Math.abs(event.getY() - startY));
            });
            scene.setOnMouseReleased(event -> {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    double x = Math.min(event.getX(), startX);
                    double y = Math.min(event.getY(), startY);
                    double width = Math.abs(event.getX() - startX);
                    double height = Math.abs(event.getY() - startY);
                    stage.close();

                    if (width <= 5 || height <= 5) {
                        complete(null, null);
                        return;
                    }

                    Rectangle2D captureBounds = new Rectangle2D(
                            bounds.getMinX() + x, bounds.getMinY() + y, width, height);
                    PauseTransition captureDelay = new PauseTransition(Duration.millis(180));
                    captureDelay.setOnFinished(captureEvent -> {
                        WritableImage image;
                        try {
                            image = robot.getScreenCapture(null, captureBounds);
                        } catch (RuntimeException ex) {
                            complete(null, ex);
                            return;
                        }
                        if (image == null) complete(null, new IllegalStateException("The screen capture returned no image."));
                        else complete(image, null);
                    });
                    captureDelay.play();
                } catch (RuntimeException ex) {
                    complete(null, ex);
                }
            });
            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE && completed.compareAndSet(false, true)) {
                    try {
                        stage.close();
                        complete(null, null);
                    } catch (RuntimeException ex) {
                        complete(null, ex);
                    }
                }
            });
            stage.setOnHidden(event -> {
                if (completed.compareAndSet(false, true)) complete(null, null);
            });
            stage.setScene(scene);
            stage.show();
        } catch (RuntimeException ex) {
            if (completed.compareAndSet(false, true)) {
                try {
                    if (stage.isShowing()) stage.close();
                } catch (RuntimeException closeError) {
                    ex.addSuppressed(closeError);
                }
                complete(null, ex);
            }
        }
    }

    private Rectangle2D getScreenBounds(List<Screen> screens) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Screen screen : screens) {
            Rectangle2D screenBounds = screen.getBounds();
            minX = Math.min(minX, screenBounds.getMinX());
            minY = Math.min(minY, screenBounds.getMinY());
            maxX = Math.max(maxX, screenBounds.getMaxX());
            maxY = Math.max(maxY, screenBounds.getMaxY());
        }
        return new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
    }

    private void complete(Image image, RuntimeException error) {
        if (error != null) logger.error("Screen capture failed", error);
        onComplete.accept(image, error);
    }
}
