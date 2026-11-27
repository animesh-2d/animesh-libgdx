package com.jb.rts.player.ui;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the <b>mouse-wheel routing</b> contract of the player screens: the drawing area must not
 * be covered by a UI actor, and the parameter panel may only hold the stage's scroll focus while
 * the cursor is over it.
 *
 * <p>The bug this protects against: Scene2D sends {@code scrolled} to the stage's scroll focus, a
 * {@link com.badlogic.gdx.scenes.scene2d.ui.ScrollPane} takes that focus on the first click and
 * libGDX never releases it — so the panel swallowed the wheel over the whole window and the mesh
 * player's zoom was dead in the drawing area while the panel was expanded. Two things make the
 * routing work, and both are asserted here:</p>
 *
 * <ul>
 *   <li>the fill-parent layout tables of {@link NavBar} and {@link ControlPanel} only take input
 *       where their widgets are ({@code Touchable.childrenOnly}) — {@code stage.hit(…)} therefore
 *       returns {@code null} in the drawing area;</li>
 *   <li>{@link ControlPanel} gives its scroll pane the stage's scroll focus on hover and drops it
 *       again on exit, so the wheel belongs to the world as soon as the cursor leaves the panel.</li>
 * </ul>
 *
 * <p><b>Opt-in</b>, because it opens a (hidden) window: {@code ./gradlew :player:test
 * -Panimesh.gl=true}. Plain {@code ./gradlew build} skips it, like {@code VatPaletteGlTest}.</p>
 */
@EnabledIfSystemProperty(named = "animesh.gl", matches = "true")
class WheelRoutingGlTest {

    /** What the GL thread measured, so the assertions can run on the test thread. */
    private static final class Result {
        Actor overDrawingArea;
        Actor overPanel;
        Actor overNavButton;
        Actor scrollFocusOnPanel;
        Actor scrollFocusAfterLeaving;
        Throwable failure;
    }

    @Test
    void drawingAreaBelongsToTheWorldAndThePanelOnlyTakesTheWheelWhileHovered() {
        Result result = new Result();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Wheel routing test");
        config.setWindowedMode(1280, 720);
        config.setInitialVisible(false);   // offscreen: no window flashes up during a build
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        config.useVsync(false);
        config.setForegroundFPS(0);

        // Blocks until the adapter calls Gdx.app.exit().
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    measure(result);
                } catch (Throwable t) {
                    result.failure = t;
                } finally {
                    Gdx.app.exit();
                }
            }
        }, config);

        if (result.failure != null) throw new AssertionError("GL run failed", result.failure);
        assertNull(result.overDrawingArea,
            "the drawing area must stay free of UI actors, so the wheel there zooms the world");
        assertNotNull(result.overPanel,
            "the panel window must be hit, so the wheel there scrolls the form");
        assertNotNull(result.overNavButton, "a navigation-bar button must be hit");
        assertNotNull(result.scrollFocusOnPanel,
            "hovering the panel must give its scroll pane the stage's scroll focus");
        assertNull(result.scrollFocusAfterLeaving,
            "leaving the panel must release the scroll focus, or the zoom stays dead in the world");
    }

    /** Builds the real chrome (skin, nav bar, panel) on a stage and probes it — on the GL thread. */
    private static void measure(Result result) {
        Stage stage = new Stage(new ScreenViewport());
        Skin skin = UiSkin.load();
        try {
            NavBar navBar = new NavBar(skin).onClose(() -> { });
            navBar.attachTo(stage);
            ControlPanel panel = new ControlPanel(skin, "Test panel") { };
            stage.addActor(panel.getRoot());

            // Lays out the fill-parent tables and every widget, so hit() sees the real bounds.
            stage.act(0f);
            stage.draw();

            Vector2 point = new Vector2();

            // Between the panel (top-left) and the nav bar (bottom-right): plain drawing area.
            result.overDrawingArea = stage.hit(stage.getWidth() * 0.75f,
                stage.getHeight() * 0.5f, true);

            Window window = findWindow(stage.getRoot());
            if (window == null) throw new AssertionError("the panel must add its window to the stage");
            window.localToStageCoordinates(point.set(window.getWidth() * 0.5f,
                window.getHeight() * 0.5f));
            result.overPanel = stage.hit(point.x, point.y, true);

            TextButton close = navBar.closeButton();
            close.localToStageCoordinates(point.set(close.getWidth() * 0.5f,
                close.getHeight() * 0.5f));
            Actor overButton = stage.hit(point.x, point.y, true);
            if (overButton == null
                || (overButton != close && !overButton.isDescendantOf(close))) {
                throw new AssertionError("the close button must be hit at its centre");
            }
            result.overNavButton = overButton;

            // Hover the panel: the control panel must hand its scroll pane the scroll focus …
            hover(stage, window);
            result.scrollFocusOnPanel = stage.getScrollFocus();

            // … and drop it again as soon as the cursor is back in the drawing area.
            stage.stageToScreenCoordinates(point.set(stage.getWidth() * 0.75f,
                stage.getHeight() * 0.5f));
            stage.mouseMoved((int) point.x, (int) point.y);
            stage.act(0f);
            result.scrollFocusAfterLeaving = stage.getScrollFocus();
        } finally {
            stage.dispose();
            skin.dispose();
        }
    }

    /** Moves the stage's mouse position to the centre of {@code actor} and lets the enter fire. */
    private static void hover(Stage stage, Actor actor) {
        Vector2 point = new Vector2(actor.getWidth() * 0.5f, actor.getHeight() * 0.5f);
        actor.localToStageCoordinates(point);
        stage.stageToScreenCoordinates(point);
        stage.mouseMoved((int) point.x, (int) point.y);
        stage.act(0f);
    }

    /** Depth-first search for the first {@link Window} — the parameter panel's window. */
    private static Window findWindow(Actor actor) {
        if (actor instanceof Window) return (Window) actor;
        if (actor instanceof Group) {
            for (Actor child : ((Group) actor).getChildren()) {
                Window found = findWindow(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
