package com.jb.rts.player.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.jb.rts.player.DynamicAnimState;
import com.jb.rts.player.PlayerAnimationFsm;

/**
 * The interactive <b>state graph overlay</b> of the mesh player: one node per animation of the
 * loaded file, arranged on a circle, with an arrow for every transition and a tooltip that names the
 * rule of the hovered node.
 *
 * <p>Colours follow the machine's rules and live state: <b>green</b> = the active state and every
 * target its rule allows, <b>red</b> = a target the rule refuses, <b>amber</b> = fading out (its
 * arrow carries a progress dot while the crossfade runs). Because the player never blocks a
 * transition, the colours are the only place where the rules show — the user sees what the machine
 * <em>would</em> allow while being free to try everything.</p>
 *
 * <p>The class owns no GPU resource: the {@link ShapeRenderer} is created and disposed by the
 * screen, the {@link BitmapFont} and {@link Batch} are the ones the HUD already draws with. Nothing
 * is allocated per frame — every scratch array is a field. All geometry lives in
 * {@link FsmGraphLayout} (GL-free, unit-tested) and all constants in {@link UiLayout}.</p>
 */
public final class FsmGraphOverlay {

    /** Colour of a node's label. */
    private static final Color LABEL_COLOR = new Color(0.90f, 0.93f, 0.98f, 1f);
    /** Colour of the label of an illegal target (matches the red border). */
    private static final Color LABEL_ILLEGAL_COLOR = new Color(0.95f, 0.62f, 0.62f, 1f);
    /** Circle smoothness: enough for a 26 px radius without a visible polygon edge. */
    private static final int CIRCLE_SEGMENTS = 24;

    private final FsmGraphLayout layout;
    private final FsmGraphLayout.Arrow arrow;

    /** Scratch: the segment of the arrow being drawn (two points, four floats). */
    private final float[] segment = new float[FsmGraphLayout.XY_PER_ARROW];
    /** Scratch: the point of the crossfade progress dot. */
    private final float[] point = new float[FsmGraphLayout.XY_PER_NODE];
    /** Scratch: the three vertices of one arrow head. */
    private final float[] head = new float[6];
    /** Reusable "is this target allowed" row, filled once per frame. */
    private boolean[] allowed = new boolean[0];
    /** Measures labels and the tooltip. */
    private final GlyphLayout glyphs = new GlyphLayout();

    /**
     * The skin's white 1×1 region, used to paint the tooltip panel. May be {@code null} (the
     * tooltip then shows only its text).
     */
    private final TextureRegion whiteRegion;

    private int hovered = -1;

    /**
     * Builds the overlay.
     *
     * @param whiteRegion the skin's white region for the tooltip panel (may be {@code null})
     */
    public FsmGraphOverlay(TextureRegion whiteRegion) {
        this.whiteRegion = whiteRegion;
        this.layout = new FsmGraphLayout(
            UiLayout.fsmGraphCenterX(0f),
            UiLayout.fsmGraphCenterY(0f),
            UiLayout.FSM_RING_RADIUS,
            UiLayout.FSM_NODE_RADIUS);
        this.arrow = layout.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH);
        layout.layout(0);
    }

    /**
     * Anchors the graph {@link UiLayout#FSM_GRAPH_MARGIN_X} px from the left and
     * {@link UiLayout#FSM_GRAPH_MARGIN_Y} px from the bottom screen edge for this window size.
     *
     * <p>Called whenever the renderer sees a new window size; a no-op (and allocation-free) when the
     * size did not change, which is the normal case.</p>
     *
     * @param viewW screen width in pixels
     * @param viewH screen height in pixels
     */
    public void place(float viewW, float viewH) {
        layout.moveTo(UiLayout.fsmGraphCenterX(viewW), UiLayout.fsmGraphCenterY(viewH));
    }

    /**
     * Recomputes the node positions for a graph of {@code stateCount} states.
     *
     * @param stateCount number of animations in the loaded file
     */
    public void layout(int stateCount) {
        layout.layout(stateCount);
        if (allowed.length < stateCount) allowed = new boolean[stateCount];
    }

    /** Number of nodes currently laid out. */
    public int nodeCount() {
        return layout.nodeCount();
    }

    /** True while there is nothing to draw. */
    public boolean isEmpty() {
        return layout.nodeCount() == 0;
    }

    /**
     * Length of the scratch segment buffer, so a test can pin that it is sized in
     * {@link FsmGraphLayout#XY_PER_ARROW} and not in the per-node
     * {@link FsmGraphLayout#XY_PER_NODE} (which is two floats too short and made every draw throw
     * {@code Index 2 out of bounds for length 2}).
     */
    int segmentLength() {
        return segment.length;
    }

    /**
     * The node under a cursor position.
     *
     * @param screenX cursor x in pixels (GL bottom-left origin)
     * @param screenY cursor y in pixels
     * @return the node index, or {@code -1}
     */
    public int hitTest(float screenX, float screenY) {
        return layout.hitTest(screenX, screenY);
    }

    /** Remembers which node the cursor is over ({@code -1} for none), for the tooltip. */
    public void hover(int nodeIndex) {
        this.hovered = nodeIndex >= 0 && nodeIndex < layout.nodeCount() ? nodeIndex : -1;
    }

    /** The node the cursor is over, or {@code -1}. */
    public int hovered() {
        return hovered;
    }

    /** x of node {@code index}'s centre (for tooltips and tests). */
    public float nodeX(int index) {
        return layout.nodeX(index);
    }

    /** y of node {@code index}'s centre. */
    public float nodeY(int index) {
        return layout.nodeY(index);
    }

    /** Radius of a node body (hit radius, see {@link FsmGraphLayout#hitTest}). */
    public float nodeRadius() {
        return layout.nodeRadius();
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the graph: translucent node fills, then arrows, then node borders, then the labels and
     * the hovered node's tooltip.
     *
     * @param shapes the screen's shape renderer (must not be inside a begin/end block)
     * @param batch  the screen's batch for the labels (must not be inside a begin/end block);
     *               may be {@code null} to skip the text
     * @param font   the font the HUD uses, so the graph matches the rest of the UI
     * @param fsm    the machine whose live state colours the nodes, or {@code null} for neutral
     */
    public void draw(ShapeRenderer shapes, Batch batch, BitmapFont font, PlayerAnimationFsm fsm) {
        if (shapes == null || layout.nodeCount() == 0) return;
        // A batch that is still open cannot be begun again: ShapeRenderer.begin() throws
        // "Call end() before beginning a new shape batch." — and because the screen catches drawing
        // exceptions to keep the window alive, the same error would repeat on every later frame.
        // Close the stray batch once (it is ours: nothing else draws with this renderer) so the graph
        // recovers instead of staying invisible for the rest of the session.
        if (shapes.isDrawing()) {
            Gdx.app.error("FsmGraphOverlay", "closing a shape batch left open by a previous frame");
            shapes.end();
        }
        if (fsm != null) allowed = fsm.allowedTargets(allowed);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        try {
            drawFilledPass(shapes, fsm);
            drawLinePass(shapes, fsm);
        } finally {
            // Belt and braces: an exception inside a pass must never leak the begin()/end() pair.
            if (shapes.isDrawing()) shapes.end();
            Gdx.gl.glLineWidth(1f);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }

        if (batch != null && font != null) drawLabels(batch, font, fsm);
    }

    /**
     * Pass 1 (filled): node bodies and every arrow head. A {@code Line}-type {@link ShapeRenderer}
     * silently drops triangles, so the heads belong in the filled pass while the shafts go to the
     * line pass (see {@link #drawLinePass}).
     */
    private void drawFilledPass(ShapeRenderer shapes, PlayerAnimationFsm fsm) {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        try {
            for (int i = 0; i < layout.nodeCount(); i++) {
                shapes.setColor(fillColor(fsm, i));
                shapes.circle(layout.nodeX(i), layout.nodeY(i), layout.nodeRadius(), CIRCLE_SEGMENTS);
            }
            drawArrowHeads(shapes, fsm);
        } finally {
            shapes.end();
        }
    }

    /** Pass 2 (lines): the arrow shafts and the node borders. */
    private void drawLinePass(ShapeRenderer shapes, PlayerAnimationFsm fsm) {
        shapes.begin(ShapeRenderer.ShapeType.Line);
        try {
            Gdx.gl.glLineWidth(UiLayout.FSM_LINE_WIDTH);
            drawArrowShafts(shapes, fsm);
            for (int i = 0; i < layout.nodeCount(); i++) {
                shapes.setColor(borderColor(fsm, i));
                shapes.circle(layout.nodeX(i), layout.nodeY(i), layout.nodeRadius(), CIRCLE_SEGMENTS);
            }
        } finally {
            shapes.end();
        }
    }

    /**
     * Fill colour of one node: solid green for the active state, amber for the outgoing one during a
     * crossfade, translucent green for a legal target and translucent red for an illegal one.
     */
    private Color fillColor(PlayerAnimationFsm fsm, int index) {
        if (fsm == null) return UiLayout.FSM_LEGAL_FILL;
        if (index == fsm.currentIndex()) return UiLayout.FSM_ACTIVE_FILL;
        if (fsm.outgoing() != null && index == fsm.outgoing().index()) {
            return UiLayout.FSM_OUTGOING_FILL;
        }
        return allowed(index) ? UiLayout.FSM_LEGAL_FILL : UiLayout.FSM_ILLEGAL_FILL;
    }

    /** Border colour of one node, i.e. the same coding with a solid stroke. */
    private Color borderColor(PlayerAnimationFsm fsm, int index) {
        if (fsm == null) return UiLayout.FSM_LEGAL_COLOR;
        if (index == fsm.currentIndex()) return UiLayout.FSM_ACTIVE_COLOR;
        if (fsm.outgoing() != null && index == fsm.outgoing().index()) {
            return UiLayout.FSM_OUTGOING_COLOR;
        }
        return allowed(index) ? UiLayout.FSM_LEGAL_COLOR : UiLayout.FSM_ILLEGAL_COLOR;
    }

    /** Below this length a segment is a point, i.e. an arrow that does not fit (see FsmGraphLayout). */
    private static final float MIN_ARROW_LENGTH = 1e-4f;

    /**
     * True when {@code index} is a legal target of the current state (filled per frame).
     */
    private boolean allowed(int index) {
        return index >= 0 && index < allowed.length && allowed[index];
    }

    /**
     * Draws the shaft of every arrow (line pass). An arrow is <b>green</b> when the current state's
     * rule allows that transition and <b>red</b> when it refuses it; the arrow of the running
     * crossfade is amber and carries a dot that walks from the outgoing to the incoming node.
     *
     * <p>An arrow whose nodes leave no room for it comes back from
     * {@link FsmGraphLayout.Arrow#segment} as a zero-length segment; it is skipped, together with its
     * crossfade dot (which would otherwise sit on the source node).</p>
     */
    private void drawArrowShafts(ShapeRenderer shapes, PlayerAnimationFsm fsm) {
        int count = layout.nodeCount();
        int from = activeFrom(fsm);
        int to = fsm != null ? fsm.currentIndex() : -1;
        for (int a = 0; a < count; a++) {
            for (int b = 0; b < count; b++) {
                if (a == b) continue;
                arrow.segment(a, b, segment);
                if (arrowLength() < MIN_ARROW_LENGTH) continue;
                boolean active = a == from && b == to;
                shapes.setColor(arrowColor(b, active));
                shapes.line(segment[0], segment[1], segment[2], segment[3]);
                if (active) drawCrossfadeDot(shapes, fsm);
            }
        }
    }

    /** Draws one triangle head per arrow (filled pass — a Line renderer drops triangles). */
    private void drawArrowHeads(ShapeRenderer shapes, PlayerAnimationFsm fsm) {
        int count = layout.nodeCount();
        int from = activeFrom(fsm);
        int to = fsm != null ? fsm.currentIndex() : -1;
        for (int a = 0; a < count; a++) {
            for (int b = 0; b < count; b++) {
                if (a == b) continue;
                arrow.segment(a, b, segment);
                // No arrow fits between these two nodes: drawArrowHead() cannot build a direction
                // from a zero-length segment (it would divide by zero).
                if (arrowLength() < MIN_ARROW_LENGTH) continue;
                boolean active = a == from && b == to;
                shapes.setColor(arrowColor(b, active));
                drawArrowHead(shapes, segment[0], segment[1], segment[2], segment[3]);
            }
        }
    }

    /**
     * Colour of one arrow: amber for the running crossfade, otherwise green/red depending on whether
     * the rule of the <b>active</b> state allows the step into {@code target}.
     *
     * @param target the node the arrow points at
     * @param active {@code true} for the arrow of the crossfade in flight
     */
    private Color arrowColor(int target, boolean active) {
        if (active) return UiLayout.FSM_ARROW_ACTIVE_COLOR;
        return allowed(target) ? UiLayout.FSM_ARROW_LEGAL_COLOR : UiLayout.FSM_ARROW_ILLEGAL_COLOR;
    }

    /** Length of the segment in {@link #segment}, which the arrow loop just filled. */
    private float arrowLength() {
        float dx = segment[2] - segment[0];
        float dy = segment[3] - segment[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Index of the state being faded out, or {@code -1}. */
    private static int activeFrom(PlayerAnimationFsm fsm) {
        return fsm != null && fsm.outgoing() != null ? fsm.outgoing().index() : -1;
    }

    /** Adds the triangle head at the end of {@code (x0, y0) -> (x1, y1)}; no-op on a point. */
    private void drawArrowHead(ShapeRenderer shapes, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < MIN_ARROW_LENGTH) return;
        float ux = dx / len, uy = dy / len;
        float size = UiLayout.FSM_ARROW_HEAD;
        // Base of the head is one head length behind the tip; the two wings are perpendicular.
        float bx = x1 - ux * size, by = y1 - uy * size;
        float half = size * 0.5f;
        head[0] = x1;                head[1] = y1;
        head[2] = bx - uy * half;    head[3] = by + ux * half;
        head[4] = bx + uy * half;    head[5] = by - ux * half;
        shapes.triangle(head[0], head[1], head[2], head[3], head[4], head[5]);
    }

    /** The dot that travels along the active arrow while the crossfade runs. */
    private void drawCrossfadeDot(ShapeRenderer shapes, PlayerAnimationFsm fsm) {
        arrow.point(fsm.outgoing().index(), fsm.currentIndex(), fsm.crossfadeProgress(),
            segment, point);
        shapes.setColor(UiLayout.FSM_ARROW_ACTIVE_COLOR);
        shapes.circle(point[0], point[1], UiLayout.FSM_ARROW_HEAD * 0.28f, 10);
    }

    // ------------------------------------------------------------------ text

    /** Draws the node labels and, if the cursor is over a node, its rule tooltip. */
    private void drawLabels(Batch batch, BitmapFont font, PlayerAnimationFsm fsm) {
        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        boolean drawing = batch.isDrawing();
        try {
            if (!drawing) batch.begin();
            font.getData().setScale(UiLayout.FSM_LABEL_SCALE);
            for (int i = 0; i < layout.nodeCount(); i++) {
                font.setColor(fsm != null && !allowed(i) ? LABEL_ILLEGAL_COLOR : LABEL_COLOR);
                String name = fsm != null && fsm.state(i) != null ? fsm.state(i).name() : "";
                drawCentered(batch, font, name, layout.nodeX(i), layout.nodeY(i));
            }
            if (hovered >= 0) drawTooltip(batch, font, fsm);
            if (!drawing) batch.end();
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /** Centers {@code text} on {@code (x, y)} (the font draws from the baseline). */
    private void drawCentered(Batch batch, BitmapFont font, String text, float x, float y) {
        if (text.isEmpty()) return;
        glyphs.setText(font, text);
        font.draw(batch, glyphs, x - glyphs.width * 0.5f, y + glyphs.height * 0.5f);
    }

    /** The small rule tooltip next to the hovered node. */
    private void drawTooltip(Batch batch, BitmapFont font, PlayerAnimationFsm fsm) {
        DynamicAnimState state = fsm != null ? fsm.state(hovered) : null;
        if (state == null) return;
        String text = fsm.rule(hovered).label() + "  "
            + String.format(java.util.Locale.ROOT, "%.2fs", state.duration())
            + "  " + (fsm.isRuleAllowed(hovered) ? "legal" : "blocked by rule");

        font.getData().setScale(UiLayout.FSM_TOOLTIP_SCALE);
        glyphs.setText(font, text);
        float pad = UiLayout.FSM_LABEL_PADDING * 1.6f;
        float bx = layout.nodeX(hovered) + layout.nodeRadius() + pad;
        float by = layout.nodeY(hovered) + layout.nodeRadius() * 0.5f;
        float bw = glyphs.width + pad * 2f;
        float bh = glyphs.height + pad * 1.4f;

        // Panel + text both go through the batch, so it has to be the white 1×1 texture under a
        // tint — the same trick UiSkin uses for every widget background.
        if (whiteRegion != null) {
            batch.setColor(UiLayout.FSM_TOOLTIP_BORDER);
            batch.draw(whiteRegion, bx, by, bw, bh);
            batch.setColor(UiLayout.FSM_TOOLTIP_FILL);
            batch.draw(whiteRegion, bx + 1f, by + 1f, bw - 2f, bh - 2f);
            batch.setColor(Color.WHITE);
        }
        font.setColor(UiLayout.FSM_TOOLTIP_COLOR);
        font.draw(batch, glyphs, bx + pad, by + bh - pad * 0.7f - glyphs.height);
    }
}
