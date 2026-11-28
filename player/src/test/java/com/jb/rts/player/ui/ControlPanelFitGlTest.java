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
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the <b>panel height</b> contract of the player: a parameter form that is longer than the
 * window must stop at the stage's bottom edge and stay reachable by scrolling — it must never grow
 * past the screen (the bug: in the 1280×720 HD window the mesh player's panel was taller than the
 * window, so the colour table and the status line were simply cut off and nothing could scroll).
 *
 * <p>What is asserted, all on a real stage with the real skin:</p>
 * <ul>
 *   <li>an expanded, shrink-wrapped {@link ControlPanel} window fits into the HD stage, bottom edge
 *       included, and is measurably shorter than the height its form asks for;</li>
 *   <li>its {@link ScrollPane} still holds the whole form, i.e. it scrolls instead of hiding
 *       rows;</li>
 *   <li>after a resize to a stage with room to spare the panel is back to shrink-wrap (the clamp
 *       follows the stage and is not a fixed height).</li>
 * </ul>
 *
 * <p><b>Opt-in</b>, because it opens a (hidden) window: {@code ./gradlew :player:test
 * -Panimesh.gl=true}. Plain {@code ./gradlew build} skips it, like {@link WheelRoutingGlTest}.</p>
 */
@EnabledIfSystemProperty(named = "animesh.gl", matches = "true")
class ControlPanelFitGlTest {

    /** The player's window (see {@code HdWindow}): 1280 × 720. */
    private static final int HD_WIDTH = 1280;
    private static final int HD_HEIGHT = 720;

    /** A stage with more room than the test form needs, to check that the clamp releases. */
    private static final int TALL_HEIGHT = 2000;

    /** What the GL thread measured, so the assertions can run on the test thread. */
    private static final class Result {
        float hdStageHeight;
        float hdWindowHeight;
        float hdWindowPrefHeight;
        float hdWindowBottom;
        float formHeight;
        float scrollHeight;
        float tallStageHeight;
        float tallWindowHeight;
        float tallWindowPrefHeight;
        Throwable failure;
    }

    @Test
    void aLongFormStopsAtTheStageBottomAndScrolls() {
        Result result = new Result();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Control panel fit test");
        config.setWindowedMode(HD_WIDTH, HD_HEIGHT);
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

        float usable = result.hdStageHeight - 2f * UiLayout.PADDING;
        assertTrue(result.hdWindowHeight <= usable + 0.5f,
            "the panel must fit into the HD window, but it is " + result.hdWindowHeight
                + " px tall in a stage that offers " + usable + " px");
        assertTrue(result.hdWindowBottom >= UiLayout.PADDING - 0.5f,
            "the panel must not stick out of the bottom edge, but its bottom is at stage y "
                + result.hdWindowBottom);
        assertTrue(result.hdWindowHeight < result.hdWindowPrefHeight - 1f,
            "a form that is longer than the window must be clamped — otherwise its last rows"
                + " (the colour table, the status line) are cut off");
        assertTrue(result.formHeight > result.scrollHeight + 1f,
            "the clamped panel must keep the whole form reachable: the scroll pane has to scroll"
                + " (" + result.formHeight + " px form in a " + result.scrollHeight + " px viewport)");

        float tallUsable = result.tallStageHeight - 2f * UiLayout.PADDING;
        assertTrue(result.tallWindowHeight <= tallUsable + 0.5f,
            "a taller stage must not make the panel overflow either");
        assertTrue(result.tallWindowHeight > result.hdWindowHeight + 50f,
            "the clamp must follow the stage: a taller window shows more of the form");
        assertEquals(result.tallWindowPrefHeight, result.tallWindowHeight, 1f,
            "with room to spare the panel must shrink-wrap again instead of stretching");
    }

    /** Lays the real panel out on an HD stage, then on a taller one — on the GL thread. */
    private static void measure(Result result) {
        Stage stage = new Stage(new ScreenViewport());
        Skin skin = UiSkin.load();
        try {
            stage.getViewport().update(HD_WIDTH, HD_HEIGHT, true);
            ControlPanel panel = new LongForm(skin);
            stage.addActor(panel.getRoot());

            // Lays out the fill-parent tables and every widget, i.e. the real thing.
            stage.act(0f);
            stage.draw();

            Window window = findWindow(stage.getRoot());
            if (window == null) throw new AssertionError("the panel must add its window to the stage");
            ScrollPane scroll = findScrollPane(window);
            if (scroll == null) {
                throw new AssertionError("the panel must put its form into a scroll pane");
            }

            result.hdStageHeight = stage.getHeight();
            result.hdWindowHeight = window.getHeight();
            result.hdWindowPrefHeight = window.getPrefHeight();
            result.hdWindowBottom = bottomEdgeOf(window);
            result.formHeight = scroll.getWidget().getHeight();
            result.scrollHeight = scroll.getHeight();

            // The user drags the window edge: same panel, a stage with room to spare.
            stage.getViewport().update(HD_WIDTH, TALL_HEIGHT, true);
            stage.act(0f);
            stage.draw();
            result.tallStageHeight = stage.getHeight();
            result.tallWindowHeight = window.getHeight();
            result.tallWindowPrefHeight = window.getPrefHeight();
        } finally {
            stage.dispose();
            skin.dispose();
        }
    }

    /** y of {@code actor}'s bottom edge in stage pixels (GL origin: 0 is the stage's bottom). */
    private static float bottomEdgeOf(Actor actor) {
        Vector2 point = new Vector2(0f, 0f);
        actor.localToStageCoordinates(point);
        return point.y;
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

    /** Depth-first search for the form's {@link ScrollPane} inside the panel window. */
    private static ScrollPane findScrollPane(Actor actor) {
        if (actor instanceof ScrollPane) return (ScrollPane) actor;
        if (actor instanceof Group) {
            for (Actor child : ((Group) actor).getChildren()) {
                ScrollPane found = findScrollPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** A form with far more rows than an HD window can show — the mesh player's situation. */
    private static final class LongForm extends ControlPanel {
        LongForm(Skin skin) {
            super(skin, "Long form");
            for (int i = 0; i < 40; i++) {
                label("Row " + i + " of a parameter form that is taller than the window.");
            }
        }
    }
}
