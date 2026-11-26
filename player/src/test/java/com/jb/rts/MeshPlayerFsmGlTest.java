package com.jb.rts;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.jb.rts.player.PlayerAnimationFsm;
import com.jb.rts.player.ui.MeshPlayerControls;
import com.jb.rts.player.ui.UiLayout;
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
 * End-to-end guard of the <b>FSM visualiser</b> inside the real {@link MeshPlayer}: the machine is
 * rebuilt per loaded file, FSM mode drives {@code currentAnim}/{@code blendTarget}/{@code blendWeight},
 * the graph draws, a state transition crossfades, and everything is disposed with the screen.
 *
 * <p>This is the only test that exercises the GL path (a {@code ShapeRenderer} pass that has to be
 * begun and ended in the right order next to the sprite batch), which unit tests cannot reach.</p>
 *
 * <p><b>Opt-in</b>, because it opens a hidden window: {@code ./gradlew :player:test
 * -Panimesh.gl=true}.</p>
 */
@EnabledIfSystemProperty(named = "animesh.gl", matches = "true")
class MeshPlayerFsmGlTest {

    /** The shipped dino export, relative to the {@code player} module. */
    private static final String DINO_EXPORT = "../test-assets/meshes/dino_longneck-export.json";

    @Test
    void fsmModeDrivesTheRendererAndTheGraphSurvivesAFullFrameLoop() {
        assumeTrue(Files.isRegularFile(Path.of(DINO_EXPORT)),
            "asset not available: " + DINO_EXPORT);

        final Throwable[] failure = new Throwable[1];

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("FSM player test");
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

        if (failure[0] != null) throw new AssertionError("FSM GL run failed", failure[0]);
    }

    /** Runs on the GL thread, against the real screen. */
    private static void exercise(MeshPlayer player) throws Exception {
        PlayerAnimationFsm fsm = (PlayerAnimationFsm) invoke(get(player, "fsmController"),
            "fsm", new Class<?>[]{});
        assertNotNull(fsm, "the machine must be built for the loaded export");
        assertTrue(fsm.stateCount() > 1, "the dino export has several clips");

        // A few plain frames first (FSM mode off): the original rendering path is untouched.
        render(player, 3);

        // Turn FSM mode on through the same path the F hotkey uses.
        MeshPlayerControls controls = (MeshPlayerControls) get(player, "controls");
        controls.setFsmMode(true);
        invoke(player, "setFsmMode", new Class<?>[]{boolean.class}, true);
        assertTrue((Boolean) get(player, "fsmMode"), "FSM mode is on");

        // Rendering in FSM mode: graph + world + HUD must not throw.
        render(player, 3);
        assertEquals(fsm.currentIndex(), (int) (Integer) get(player, "currentAnim"),
            "the machine's state is what the renderer draws");
        assertEquals(0, (int) (Integer) get(player, "currentAnim"),
            "a load starts on the file's first animation");
        assertEquals(0, fsm.currentIndex(),
            "the machine starts on the clip the player draws, not on its inferred idle state");

        // A transition into another clip must produce a crossfade the screen's own FSM step advances.
        // The frames are stepped with an explicit delta here: the hidden window's
        // Gdx.graphics.getDeltaTime() is 0 inside create(), which would freeze the fade.
        int target = otherStateIndex(fsm);
        assertTrue(fsm.fadeSeconds() > 0f, "the crossfade must default to a visible length");
        assertTrue(fsm.requestState(target), "another looping state is always reachable");
        invoke(player, "updateFsm", new Class<?>[]{float.class}, 1f / 60f);
        assertEquals(target, (int) (Integer) get(player, "currentAnim"));
        assertEquals(fsm.blendTarget(), (int) (Integer) get(player, "blendTarget"),
            "the outgoing clip is handed to the blend batch");
        assertTrue((Float) get(player, "blendWeight") > 0f, "the blend weight advanced");
        assertEquals(fsm.localTime(), (Float) get(player, "time"),
            "the clip clock follows the machine");

        // Let the fade finish (the real clock, however slow), then confirm the fields fall back to
        // the plain clip.
        render(player, 30);
        if (fsm.isCrossfading()) {
            invoke(player, "updateFsm", new Class<?>[]{float.class}, 1f);
        }
        render(player, 1);
        assertFalse(fsm.isCrossfading(), "the fade finishes and the machine settles on one clip");
        assertEquals(-1, (int) (Integer) get(player, "blendTarget"));

        // Regression: Prev/Next moved currentAnim directly, so the graph kept showing the old state.
        // Manual paging is not a transition (it always works), but the machine has to follow it.
        int drawn = (int) (Integer) get(player, "currentAnim");
        invoke(player, "nextAnim", new Class<?>[]{});
        assertEquals((drawn + 1) % fsm.stateCount(), (int) (Integer) get(player, "currentAnim"),
            "Next paged the animation");
        assertEquals((int) (Integer) get(player, "currentAnim"), fsm.currentIndex(),
            "the graph highlights the clip Prev/Next selected");
        render(player, 1);
        assertEquals(fsm.currentIndex(), (int) (Integer) get(player, "currentAnim"),
            "and it keeps drawing it");
        invoke(player, "prevAnim", new Class<?>[]{});
        assertEquals(drawn, (int) (Integer) get(player, "currentAnim"), "Prev paged back");
        assertEquals(drawn, fsm.currentIndex(), "the machine followed back");

        // The graph is laid out for every state.
        Object graph = invoke(get(player, "fsmController"), "graph", new Class<?>[]{});
        assertNotNull(graph, "the graph overlay exists");
        assertEquals(fsm.stateCount(), (int) (Integer) invoke(graph, "nodeCount", new Class<?>[]{}));

        // Regression: clicking a node did not transition the meshes. The click reaches the screen as
        // a touchDown event (window coordinates) and must hit the node that is drawn there and start
        // the transition - for every node of the graph, not just the one under the cursor.
        Object input = invoke(player, "afterStage", new Class<?>[]{});
        Object controller = get(player, "fsmController");
        for (int node = 0; node < fsm.stateCount(); node++) {
            // Every click starts from the reset state, so each one is a real transition.
            fsm.reset();
            render(player, 1);
            if (node == fsm.currentIndex()) continue;
            float nodeX = (Float) invoke(graph, "nodeX", new Class<?>[]{int.class}, node);
            float nodeY = (Float) invoke(graph, "nodeY", new Class<?>[]{int.class}, node);
            // The four points a user aims at: the node's centre in window coordinates (top-left
            // origin, exactly what touchDown receives) and a few pixels to each side.
            float[][] aims = {
                {nodeX, nodeY},
                {nodeX + 6f, nodeY + 6f},
                {nodeX - 6f, nodeY - 6f},
            };
            for (float[] aim : aims) {
                float screenX = aim[0];
                float screenY = Gdx.graphics.getHeight() - aim[1];
                boolean consumed = (Boolean) invoke(input, "touchDown",
                    new Class<?>[]{int.class, int.class, int.class, int.class},
                    Math.round(screenX), Math.round(screenY), 0, Input.Buttons.LEFT);
                assertTrue(consumed, "a click at (" + screenX + ',' + screenY + ") on node " + node
                    + " must be consumed (hitTest="
                    + invoke(graph, "hitTest", new Class<?>[]{float.class, float.class}, screenX, aim[1])
                    + ", fsmMode=" + get(player, "fsmMode") + ", window=" + Gdx.graphics.getWidth()
                    + 'x' + Gdx.graphics.getHeight() + ", backBuffer="
                    + Gdx.graphics.getBackBufferWidth() + 'x' + Gdx.graphics.getBackBufferHeight() + ')');
                assertEquals(node, fsm.currentIndex(), "the click on node " + node + " ("
                    + fsm.state(node).name() + ") starts that state, it was "
                    + fsm.state(fsm.currentIndex()).name() + " and allowed="
                    + fsm.isTransitionAllowed(node));
                // Back to a known state for the next aim point of the same node.
                fsm.reset();
            }
            render(player, 1);
        }

        // The graph must be wholly clickable, not only where hover happens to find it: whichever
        // projection the drag test above used, the drawn body is FSM_NODE_RADIUS wide. A NaN centre
        // (an unplaced layout) or a zero radius would make every click miss and look like "nothing
        // happens", so the hit radius is pinned to the drawn radius here.
        assertEquals(UiLayout.FSM_NODE_RADIUS,
            (float) (Float) invoke(graph, "nodeRadius", new Class<?>[]{}), 0.001f,
            "the hit radius is the drawn node radius");
        assertTrue(Math.abs((Float) invoke(graph, "nodeX", new Class<?>[]{int.class}, 0)) < 1e4f,
            "node centres are real pixel positions, not NaN");

        // A click that misses every node must fall through, so the screen never swallows the empty
        // area of the window (the listener must not consume what it did not use).
        assertEquals(-1, (int) (Integer) invoke(graph, "hitTest",
            new Class<?>[]{float.class, float.class}, 4f, 4f),
            "the corner of the window is free of nodes (guarded by FsmGraphLayoutTest)");
        int before = fsm.currentIndex();
        assertFalse((Boolean) invoke(input, "touchDown",
            new Class<?>[]{int.class, int.class, int.class, int.class},
            4, Gdx.graphics.getHeight() - 4, 0, Input.Buttons.LEFT),
            "a click beside the graph is not consumed");
        assertEquals(before, fsm.currentIndex(), "a click beside the graph starts nothing");
        // Regression: a shape pass that is begun but never ended makes every following frame throw
        // "Call end() before beginning a new shape batch." (ShapeRenderer.begin checks shapeType).
        Object shapes = get(get(player, "fsmController"), "shapes");
        assertFalse((Boolean) invoke(shapes, "isDrawing", new Class<?>[]{}),
            "the graph must leave the shape renderer outside a begin()/end() pair");

        // A resize moves the window's pixel projection: the graph has to keep drawing.
        player.resize(1024, 640);
        render(player, 3);
        assertFalse((Boolean) invoke(shapes, "isDrawing", new Class<?>[]{}),
            "the projection rebuild between frames must not leak a batch");

        // Back to manual mode: the original fields stay valid and nothing throws.
        invoke(player, "setFsmMode", new Class<?>[]{boolean.class}, false);
        render(player, 2);
    }

    /** Renders {@code frameCount} frames through the screen's own lifecycle. */
    private static void render(MeshPlayer player, int frameCount) {
        for (int i = 0; i < frameCount; i++) player.render();
    }

    /**
     * Regression: a click on a graph node did nothing because the <b>stage</b> reported presses over
     * empty space as handled. {@code Stage.touchDown} fires such a press at its root when no actor is
     * under the cursor, and the root is a {@link com.badlogic.gdx.scenes.scene2d.Group}, whose
     * default touchability is {@code enabled} — so the press was marked handled and the graph's
     * listener (registered <em>after</em> the stage) never saw it.
     *
     * <p>The graph sits on empty screen space by design, so this routes the press through the
     * <b>real</b> input chain: a click on a node must arrive, a click on the panel must not.</p>
     */
    @Test
    void aPressOnEmptyStageSpaceReachesTheGraphInsteadOfBeingSwallowedByTheStage() {
        assumeTrue(Files.isRegularFile(Path.of(DINO_EXPORT)),
            "asset not available: " + DINO_EXPORT);
        final Throwable[] failure = new Throwable[1];

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("FSM input routing test");
        config.setWindowedMode(1280, 720);
        config.setInitialVisible(false);
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        config.useVsync(false);
        config.setForegroundFPS(0);

        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                MeshPlayer player = new MeshPlayer(DINO_EXPORT, 8, 500f);
                try {
                    player.create();
                    verifyRouting(player);
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

        if (failure[0] != null) throw new AssertionError("FSM input routing run failed", failure[0]);
    }

    /** Runs on the GL thread: the real input chain must deliver node clicks to the graph. */
    private static void verifyRouting(MeshPlayer player) throws Exception {
        // The defect itself: the stage root claims presses over empty space unless it is childrenOnly.
        Actor root = (Actor) invoke(fieldUp(player, "stage"), "getRoot", new Class<?>[]{});
        assertEquals(Touchable.childrenOnly, root.getTouchable(),
            "the stage root must be childrenOnly, or every click on empty space is swallowed");

        PlayerAnimationFsm fsm = (PlayerAnimationFsm) invoke(get(player, "fsmController"),
            "fsm", new Class<?>[]{});
        ((MeshPlayerControls) get(player, "controls")).setFsmMode(true);
        invoke(player, "setFsmMode", new Class<?>[]{boolean.class}, true);
        render(player, 2);

        // The chain the player really installed: [wheel router, stage, graph/keys]. Calling the
        // installed processor directly is what a real press goes through.
        Object chain = Gdx.input.getInputProcessor();
        assertNotNull(chain, "the player must have installed its input chain");

        Object graph = invoke(get(player, "fsmController"), "graph", new Class<?>[]{});
        // reset() keeps the current state, so the legal target is picked after it.
        fsm.reset();
        render(player, 1);
        int target = otherStateIndex(fsm);
        assertTrue(fsm.isTransitionAllowed(target),
            "the test needs a target the rules allow (current=" + fsm.currentIndex() + ")");
        float nodeX = (Float) invoke(graph, "nodeX", new Class<?>[]{int.class}, target);
        float nodeY = (Float) invoke(graph, "nodeY", new Class<?>[]{int.class}, target);
        // click() takes the window (top-left origin) position, exactly as the real press passes it:
        // its own y from the event (Gdx.graphics.getHeight() - glY was already applied by the caller).
        String[] reason = new String[1];
        boolean accepted = (Boolean) invoke(get(player, "fsmController"), "click",
            new Class<?>[]{float.class, float.class, String[].class}, nodeX, nodeY, reason);
        assertTrue(accepted, "the controller must consume the node click: " + reason[0]);
        assertEquals(target, fsm.currentIndex(),
            "the click must start the clicked state (reason=" + reason[0]
                + ", allowed=" + fsm.isTransitionAllowed(target)
                + ", rejected=" + fsm.lastRejectedIndex() + ")");

        // The second half of the fix: an "empty" spot (no widget there) must NOT be reported as
        // handled, or it would swallow the click before the graph. A stage that swallowed presses
        // at its root returned true here — this is the exact regression.
        int before = fsm.currentIndex();
        float emptyX = Gdx.graphics.getWidth() - UiLayout.FSM_GRAPH_MARGIN_X - 4f;
        float emptyY = UiLayout.FSM_GRAPH_MARGIN_Y - 4f;
        assertFalse(touchDown(chain, emptyX, emptyY),
            "a press on empty space may not be reported as handled (the stage root must not swallow it)");
        assertEquals(before, fsm.currentIndex(), "and it changed nothing");
    }

    /** One left press through {@code processor}, given a GL bottom-left position. */
    private static boolean touchDown(Object processor, float glX, float glY) throws Exception {
        return (Boolean) invoke(processor, "touchDown",
            new Class<?>[]{int.class, int.class, int.class, int.class},
            Math.round(glX), Math.round(glY), 0, Input.Buttons.LEFT);
    }

    /**
     * Index of the first state that is not the current one <b>and</b> that the current state's rule
     * allows right now, so a plain {@code requestState} really starts the transition.
     */
    private static int otherStateIndex(PlayerAnimationFsm fsm) {
        for (int i = 0; i < fsm.stateCount(); i++) {
            if (i != fsm.currentIndex() && fsm.isTransitionAllowed(i)) return i;
        }
        throw new IllegalStateException("no reachable state in a machine of "
            + fsm.stateCount() + " states");
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Like {@link #get}, but walks the superclasses — {@code stage} lives on {@code PlayerScreen}. */
    private static Object fieldUp(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " in " + target.getClass());
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new AssertionError(method + " threw", e.getCause());
        }
    }
}