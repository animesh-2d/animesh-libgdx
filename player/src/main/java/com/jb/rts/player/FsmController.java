package com.jb.rts.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Disposable;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.player.ui.FsmControlsSection;
import com.jb.rts.player.ui.FsmGraphOverlay;

import java.util.List;

/**
 * Owns the mesh player's <b>FSM visualiser</b>: the {@link PlayerAnimationFsm}, the
 * {@link FsmGraphOverlay} and the {@link ShapeRenderer} it draws with.
 *
 * <p>The screen keeps its own rendering fields ({@code currentAnim}, {@code blendTarget},
 * {@code blendWeight}, {@code time}); this class only <em>writes</em> them through a {@link Target},
 * so the VAT pipeline never learns about state machines. Everything it needs from the screen is in
 * that one interface.</p>
 *
 * <p>Usage from the screen: {@link #rebuild} after every load, {@link #update} before drawing,
 * {@link #draw} after the HUD, {@link #click} from the input processor, and {@link #dispose} in
 * {@code onDispose()}.</p>
 */
public final class FsmController implements Disposable {

    /** What the visualiser needs from the screen. */
    public interface Target {
        /**
         * Applies one frame of machine state to the renderer fields. Called after every transition,
         * every frame in FSM mode, and when the graph is clicked.
         *
         * @param currentIndex index of the clip being faded in
         * @param blendTarget  index of the clip being faded out, or {@code -1}
         * @param blendWeight  crossfade progress in {@code 0..1}
         * @param localTime    playback time of the incoming clip
         */
        void applyFsmState(int currentIndex, int blendTarget, float blendWeight, float localTime);

        /** The manual blend batch must be dropped (the machine owns the fields from now on). */
        void onBlendInvalidated();

        /** True while the cursor is over a widget, so graph clicks belong to the UI. */
        boolean isPointerOverUi();
    }

    private final Target target;
    private FsmGraphOverlay graph;
    private ShapeRenderer shapes;

    /** The machine of the loaded file, or {@code null} when nothing is loaded. */
    private PlayerAnimationFsm fsm;
    /** True while the machine drives the renderer fields. */
    private boolean enabled;

    /** Reused cursor position, so a hit test never allocates. */
    private final Vector2 pointer = new Vector2();
    /** Pixel projection for the shape passes; the graph is drawn in screen pixels. */
    private final Matrix4 screenMatrix = new Matrix4();
    /** Screen size the projection was last built for, so a resize can be noticed cheaply. */
    private float projectedW = -1f;
    private float projectedH = -1f;
    /** Screen size the graph was last anchored for (see {@link #applyScreenProjection}). */
    private float placedW = -1f;
    private float placedH = -1f;
    /**
     * Monotonic counter of presses this controller consumed. The screen compares it across a frame
     * to tell "the stage swallowed the click" apart from "the click reached this controller" in the
     * click trace; it is never reset. TODO: remove with the trace.
     */
    private int pressCount;

    /**
     * @param target the screen to drive
     * @param white  the skin's white region for the tooltip panel (may be {@code null})
     */
    public FsmController(Target target, TextureRegion white) {
        if (target == null) throw new IllegalArgumentException("target cannot be null.");
        this.target = target;
        this.shapes = new ShapeRenderer();
        this.graph = new FsmGraphOverlay(white);
        this.graph.layout(0);
    }

    /** True while the machine drives the animation (the panel's <b>FSM Mode</b>). */
    public boolean isEnabled() {
        return enabled;
    }

    /** The machine of the loaded file, or {@code null}. */
    public PlayerAnimationFsm fsm() {
        return fsm;
    }

    /**
     * The graph overlay, or {@code null} after {@link #dispose()}.
     *
     * <p>Exposed for the screen's own diagnostics (and the GL test), which check that the graph was
     * laid out for the loaded state count.</p>
     */
    public FsmGraphOverlay graph() {
        return graph;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Rebuilds the machine for a newly loaded file.
     *
     * <p>Call it on success <b>and</b> on failure, so the machine never refers to clips of a previous
     * file. The graph is re-laid for the new state count and the panel's trigger buttons are rebuilt
     * from {@code names}.</p>
     *
     * @param names  the loaded animation names, in animation order (empty clears the machine)
     * @param groups the VAT batch per animation; an entry may be {@code null}
     * @param panel  the panel block to rebuild ({@code null} before the UI exists)
     */
    public void rebuild(List<String> names, List<VatInstancedMesh> groups, FsmControlsSection panel) {
        if (names == null || names.isEmpty()) {
            fsm = null;
        } else {
            fsm = PlayerAnimationFsm.fromGroups(names, groups);
            if (panel != null) panel.setStates(names);
        }
        if (graph != null) graph.layout(names == null ? 0 : names.size());
        if (enabled && fsm != null) applyToTarget();
    }

    // ------------------------------------------------------------------ per-frame

    /**
     * Advances the machine for one frame and pushes its state into the renderer fields.
     *
     * @param delta frame time in seconds (already clamped by the screen)
     * @return {@code true} when the machine owns the animation this frame
     */
    public boolean update(float delta) {
        if (fsm == null || !enabled) return false;
        fsm.update(delta);
        applyToTarget();
        return true;
    }

    /**
     * Draws the graph in screen space — above the world and the HUD, below the Scene2D stage.
     *
     * @param batch the screen's batch for the labels (may be {@code null} to skip the text)
     * @param font  the font the HUD uses
     * @param viewH current screen height in pixels
     */
    public void draw(Batch batch, BitmapFont font, float viewH) {
        if (graph == null || shapes == null || graph.isEmpty()) return;
        // The graph geometry is in screen pixels (GL bottom-left origin), so the shape renderer needs
        // the same pixel projection the HUD batch uses — its default one goes stale after a resize.
        applyScreenProjection(Gdx.graphics.getWidth(), viewH);
        graph.hover(hoveredNode(viewH));
        graph.draw(shapes, batch, font, fsm);
    }

    /**
     * Points the shape renderer at a pixel-space projection and keeps the graph anchored to the
     * bottom-left corner, rebuilding both only when the screen size changed (the matrix object
     * itself is reused, so this allocates nothing per frame).
     */
    private void applyScreenProjection(float width, float height) {
        if (width != projectedW || height != projectedH) {
            projectedW = width;
            projectedH = height;
            screenMatrix.setToOrtho2D(0f, 0f, Math.max(1f, width), Math.max(1f, height));
            shapes.setProjectionMatrix(screenMatrix);
        }
        // The graph keeps FSM_GRAPH_MARGIN_X/Y free to the left and bottom on every window size, so
        // a resize must re-place the ring rather than leave it at its first position.
        if (graph != null && (width != placedW || height != placedH)) {
            placedW = width;
            placedH = height;
            graph.place(width, height);
        }
    }

    // ------------------------------------------------------------------ interaction

    /**
     * Turns the visualiser on or off.
     *
     * <p>Turning it <b>on</b> makes the machine own the fields immediately. Turning it <b>off</b>
     * keeps the clip that is on screen and drops the crossfade, so the manual Prev/Next and blend
     * widgets behave exactly as before the feature existed.</p>
     *
     * @param on {@code true} to let the machine drive the animation
     */
    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (on && fsm != null) {
            target.onBlendInvalidated();
            applyToTarget();
        } else if (fsm != null) {
            fsm.commit();
            target.applyFsmState(Math.max(0, fsm.currentIndex()), -1, 0f, fsm.localTime());
        }
    }

    /**
     * Handles a click on the graph.
     *
     * @param screenX cursor x in pixels (GL bottom-left origin, i.e. window x)
     * @param screenY cursor y in pixels (GL bottom-left origin, i.e. {@code viewH - windowY})
     * @return {@code true} when the click hit a node and was consumed
     */
    public boolean click(float screenX, float screenY) {
        return click(screenX, screenY, null);
    }

    /**
     * Handles a click on the graph and reports <em>why</em> it was not consumed.
     *
     * <p>The hit test runs <b>before</b> the pointer-over-UI veto, so a click that misses every node
     * beside a widget is reported as a miss (reason {@code "hit no node"}) instead of being
     * explained by the widget. Without that order, a systematically wrong coordinate could never be
     * told apart from a legitimately ignored click. Nothing is requested while the pointer is over a
     * widget — the widget owns the click, exactly as before.</p>
     *
     * @param screenX cursor x in pixels (GL bottom-left origin, i.e. window x)
     * @param screenY cursor y in pixels (GL bottom-left origin, i.e. {@code viewH - windowY})
     * @param reason  receives a diagnostic when the click is not consumed; may be {@code null}
     * @return {@code true} when the click hit a node and was consumed
     */
    public boolean click(float screenX, float screenY, String[] reason) {
        pressCount++;
        if (fsm == null) {
            if (reason != null) reason[0] = "no machine (nothing loaded)";
            return false;
        }
        int node = graph == null || graph.isEmpty() ? -1 : graph.hitTest(screenX, screenY);
        if (node < 0) {
            if (reason != null) reason[0] = "hit no node" + geometryFor(screenX, screenY);
            return false;
        }
        if (target.isPointerOverUi()) {
            if (reason != null) reason[0] = "node " + node + " hit, but the pointer is over a widget";
            return false;
        }
        boolean started = requestState(node);
        if (reason != null) {
            if (!started) {
                reason[0] = "node " + node + " requested, but the rules blocked it";
            } else if (node == currentIndex()) {
                reason[0] = "node " + node + " requested, but it is already the active state "
                    + "(no-op, use Reset to replay it)";
            } else {
                reason[0] = "node " + node + " requested";
            }
        }
        return true;
    }

    /**
     * Temporary diagnostic: every node's centre and the clicked point, so a coordinate mismatch is
     * visible in the log. TODO: remove with the click trace.
     */
    private String geometryFor(float screenX, float screenY) {
        if (graph == null || graph.isEmpty()) return " (graph empty)";
        StringBuilder sb = new StringBuilder(" (clicked ").append(screenX).append(',')
            .append(screenY).append("; nodes");
        for (int i = 0; i < graph.nodeCount(); i++) {
            sb.append(' ').append(i).append('=').append(Math.round(graph.nodeX(i))).append('/')
                .append(Math.round(graph.nodeY(i)));
        }
        return sb.append(" r=").append(Math.round(graph.nodeRadius())).append(')').toString();
    }

    /** Presses consumed so far (see {@link #pressCount}). TODO: remove with the trace. */
    public int pressCount() {
        return pressCount;
    }

    /** The current state index, or {@code -1} when nothing is loaded. */
    private int currentIndex() {
        return fsm != null ? fsm.currentIndex() : -1;
    }

    /**
     * Lets the machine follow a clip the <b>player</b> selected manually (Prev/Next, hotkeys, the
     * panel's animation list).
     *
     * <p>Those paths move {@code currentAnim} directly and must keep working regardless of the
     * rules, so this is a hard cut rather than a {@link #requestState} — see
     * {@link PlayerAnimationFsm#follow(int)}. It also keeps the graph honest: without it the
     * highlight stayed on the clip the machine last saw, and clicking the node of the clip that was
     * really on screen did nothing.</p>
     *
     * @param index index into the loaded animation list
     * @return {@code true} when the machine followed
     */
    public boolean follow(int index) {
        if (fsm == null || !fsm.follow(index)) return false;
        applyToTarget();
        return true;
    }

    /**
     * Requests a state transition (panel buttons, hotkeys, graph clicks).
     *
     * @param index index into the loaded animation list
     * @return {@code true} when the transition started (a blocked target flashes red instead)
     */
    public boolean requestState(int index) {
        if (fsm == null) return false;
        boolean started = fsm.requestState(index);
        applyToTarget();
        return started;
    }

    /** Runs {@code action} on the machine and pushes the result out (panel transport buttons). */
    public void withFsm(java.util.function.Consumer<PlayerAnimationFsm> action) {
        if (fsm == null) return;
        action.accept(fsm);
        applyToTarget();
    }

    /** The node under the cursor, or {@code -1} (also {@code -1} over a widget). */
    private int hoveredNode(float viewH) {
        if (graph == null || graph.isEmpty() || target.isPointerOverUi()) return -1;
        pointer.set(Gdx.input.getX(), viewH - Gdx.input.getY());
        return graph.hitTest(pointer.x, pointer.y);
    }



    /** Pushes the machine's state into the renderer fields. */
    private void applyToTarget() {
        if (fsm == null) return;
        target.applyFsmState(Math.max(0, fsm.currentIndex()), fsm.blendTarget(),
            fsm.blendWeight(), fsm.localTime());
    }

    /** Releases the shape renderer; the screen calls this from {@code onDispose()}. */
    @Override
    public void dispose() {
        if (shapes != null) {
            shapes.dispose();
            shapes = null;
        }
        graph = null;
        fsm = null;
    }
}
