package com.jb.rts;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end guard of the mesh player's <b>drag-to-pan</b>: a left drag in the drawing area moves
 * the world camera, while the FSM graph and the stage widgets stay where they are — and a drag that
 * starts on a widget belongs to the UI, not to the world.
 *
 * <p>The pan itself is unit-tested ({@link com.jb.rts.player.ViewPan} — direction, per-axis ratio,
 * pointer ownership); this test protects the part only a real window can show: that the press in the
 * empty drawing area reaches the world through the real input chain (the stage root is
 * {@code childrenOnly}), that the camera really moves by the converted pixel delta, and that the
 * screen-space overlays do not follow it.</p>
 *
 * <p><b>Opt-in</b>, because it opens a hidden window: {@code ./gradlew :player:test
 * -Panimesh.gl=true}.</p>
 */
@EnabledIfSystemProperty(named = "animesh.gl", matches = "true")
class MeshPlayerPanGlTest {

    /** The shipped dino export, relative to the {@code player} module. */
    private static final String DINO_EXPORT = "../test-assets/meshes/dino_longneck-export.json";

    private static final float EPS = 1e-2f;

    @Test
    void draggingInTheDrawingAreaPansOnlyTheInstances() {
        assumeTrue(Files.isRegularFile(Path.of(DINO_EXPORT)),
            "asset not available: " + DINO_EXPORT);

        final Throwable[] failure = new Throwable[1];

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Pan test");
        config.setWindowedMode(1280, 720);
        config.setInitialVisible(false);
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        config.useVsync(false);
        config.setForegroundFPS(0);

        // Blocks until the adapter calls Gdx.app.exit().
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                MeshPlayer player = new MeshPlayer(DINO_EXPORT, 8, 500f);
                try {
                    player.create();
                    exercise(player);
                } catch (Throwable t) {
                    failure[0] = t;
                } finally {
                    try {
                        player.dispose();
                    } catch (Throwable t) {
                        if (failure[0] == null) failure[0] = t;
                    }
                    Gdx.app.exit();
                }
            }
        }, config);

        if (failure[0] != null) throw new AssertionError("pan GL run failed", failure[0]);
    }

    /** Runs on the GL thread, against the real screen and the real input chain. */
    private static void exercise(MeshPlayer player) throws Exception {
        // A couple of frames lay out the graph, size the camera and fill viewW/viewH.
        player.render();
        player.render();

        OrthographicCamera camera = (OrthographicCamera) get(player, "camera");
        assertNotNull(camera, "the loaded export must have a camera");
        Stage stage = (Stage) get(player, "stage");
        InputProcessor chain = Gdx.input.getInputProcessor();
        assertNotNull(chain, "the player must have installed its input chain");

        Object graph = invoke(get(player, "fsmController"), "graph", new Class<?>[]{});
        int nodeCount = (Integer) invoke(graph, "nodeCount", new Class<?>[]{});
        float[] nodeX = new float[nodeCount];
        float[] nodeY = new float[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodeX[i] = (Float) invoke(graph, "nodeX", new Class<?>[]{int.class}, i);
            nodeY[i] = (Float) invoke(graph, "nodeY", new Class<?>[]{int.class}, i);
        }

        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        // World units per pixel the camera uses: the viewport already contains the zoom.
        float worldPerPixelX = camera.viewportWidth / w;
        float worldPerPixelY = camera.viewportHeight / h;
        float startX = camera.position.x;
        float startY = camera.position.y;

        // A point right of the panel and above the nav bar, far from every graph node: plain grid.
        int pressX = Math.round(w * 0.75f);
        int pressY = Math.round(h * 0.35f);
        assertFalse(chain.touchDown(pressX, pressY, 0, Input.Buttons.LEFT),
            "a press in the drawing area is not consumed (only widgets and graph nodes are)");

        int dragX = pressX + 80;
        int dragY = pressY - 40;
        assertTrue(chain.touchDragged(dragX, dragY, 0),
            "the drag of the world is consumed by the pan listener");

        // The content follows the cursor: right/up on screen is left/down for the camera.
        assertEquals(startX - 80 * worldPerPixelX, camera.position.x, EPS,
            "the camera pans by the dragged pixels converted with the viewport ratio");
        assertEquals(startY + (dragY - pressY) * worldPerPixelY, camera.position.y, EPS);

        // The FSM graph is drawn in screen pixels: panning the world must not move a single node.
        for (int i = 0; i < nodeCount; i++) {
            assertEquals(nodeX[i], (Float) invoke(graph, "nodeX", new Class<?>[]{int.class}, i),
                EPS, "node " + i + " must keep its screen x while the world pans");
            assertEquals(nodeY[i], (Float) invoke(graph, "nodeY", new Class<?>[]{int.class}, i),
                EPS, "node " + i + " must keep its screen y while the world pans");
        }

        // The release ends the gesture: a drag without a press moves nothing.
        assertTrue(chain.touchUp(dragX, dragY, 0, Input.Buttons.LEFT),
            "the release ends the drag we own");
        float pannedX = camera.position.x;
        float pannedY = camera.position.y;
        assertFalse(chain.touchDragged(dragX + 50, dragY + 50, 0),
            "no panning without a press");
        assertEquals(pannedX, camera.position.x, EPS);
        assertEquals(pannedY, camera.position.y, EPS);

        // A press that starts on a widget belongs to the UI: the stage consumes it, so the world
        // never sees the gesture — dragging the panel must not pan the grid behind it.
        Vector2 onPanel = panelPoint(stage);
        assertTrue(chain.touchDown(Math.round(onPanel.x), Math.round(onPanel.y), 0,
                Input.Buttons.LEFT),
            "the stage must consume a press on the parameter panel");
        chain.touchDragged(Math.round(onPanel.x) + 40, Math.round(onPanel.y) + 40, 0);
        chain.touchUp(Math.round(onPanel.x) + 40, Math.round(onPanel.y) + 40, 0,
            Input.Buttons.LEFT);
        assertEquals(pannedX, camera.position.x, EPS,
            "dragging the panel must not pan the drawing area");
        assertEquals(pannedY, camera.position.y, EPS);
    }

    /**
     * Screen position (y down, as input events use) of a point inside the parameter panel's window,
     * computed from the real layout.
     */
    private static Vector2 panelPoint(Stage stage) {
        stage.act(0f);
        Window window = findWindow(stage.getRoot());
        if (window == null) throw new AssertionError("the panel must add its window to the stage");
        Vector2 point = new Vector2(window.getWidth() * 0.5f, window.getHeight() * 0.5f);
        window.localToStageCoordinates(point);
        stage.stageToScreenCoordinates(point);
        return point;
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

    /** Reads a private field of the player (or of one of its superclasses). */
    private static Object get(Object target, String name) throws Exception {
        return fieldUp(target, name).get(target);
    }

    /** Finds a private field in the class hierarchy — {@code stage} lives in {@code PlayerScreen}. */
    private static Field fieldUp(Object target, String name) throws NoSuchFieldException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Calls a private method by name. */
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = null;
        for (Class<?> c = target.getClass(); c != null && method == null; c = c.getSuperclass()) {
            try {
                method = c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        if (method == null) throw new NoSuchMethodException(name);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
}
