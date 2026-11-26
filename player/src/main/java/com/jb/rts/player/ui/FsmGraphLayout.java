package com.jb.rts.player.ui;

/**
 * The <b>geometry of the FSM state graph</b>: where the nodes of an {@code n}-state graph sit and
 * how the arrows between them are routed. Deliberately GL-free and dependency-free, so the layout
 * and the hit test can be unit-tested headless — {@link FsmGraphOverlay} only draws what this class
 * computes.
 *
 * <p>Nodes are placed on a circle, the first state at the top and the rest clockwise, so the order
 * of the loaded animation list is readable at a glance ({@code idle} on top, then {@code walk},
 * {@code run}, …). A directed edge is a straight chord between two node borders, shifted
 * perpendicular by {@link UiLayout#FSM_ARROW_SPREAD} so that {@code walk ↔ run} shows two
 * distinct arrows instead of one overlapping line.</p>
 *
 * <p>All output is written into caller-supplied arrays; nothing is allocated after
 * {@link #layout(int)}.</p>
 *
 * <h2>Degenerate chords</h2>
 *
 * <p>The graph lives in screen pixels, while {@link UiLayout} sizes the nodes in pixels too — so with
 * many clips the room between two neighbouring nodes can drop below what the trims of both ends need:
 * at {@link UiLayout#FSM_NODE_RADIUS} = 26 px and {@link UiLayout#FSM_RING_RADIUS} = 132 px a chord
 * of 63.2 px between neighbours (13 states) is shorter than the 65.4 px the two trims take, so from
 * about thirteen states on an arrow would be routed backwards and poke out of both nodes.</p>
 *
 * <p>Such a chord is short enough to be hidden by the nodes anyway, so it is drawn as a
 * <b>self-centred dot</b> (zero length, far inside both nodes) instead: nothing pokes out, and the
 * overlay's readers — {@link Arrow#point}, the head and the shaft — see a degenerate segment and skip
 * it. That is what keeps the whole graph drawable at any state count.</p>
 */
public final class FsmGraphLayout {

    /** Number of coordinate pairs per node: {@code x0, y0, x1, y1, ...}. */
    public static final int XY_PER_NODE = 2;

    /**
     * Floats in one arrow segment ({@code x0, y0, x1, y1}), i.e. {@code 2 * }{@link #XY_PER_NODE}.
     * <p>Use this — <b>not</b> {@link #XY_PER_NODE} — to size the array passed to
     * {@link Arrow#segment(int, int, float[])}: a segment holds two points, so a buffer of only
     * {@code XY_PER_NODE} floats makes {@code segment()} throw
     * {@code ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2}.</p>
     */
    public static final int XY_PER_ARROW = 2 * XY_PER_NODE;

    private final float ringRadius;
    private final float nodeRadius;

    /** x of the ring's centre; {@link #moveTo(float, float)} re-places the graph on a resize. */
    private float centerX;
    /** y of the ring's centre. */
    private float centerY;

    /** Node centres as {@code [x0, y0, x1, y1, …]}, rebuilt by {@link #layout(int)}. */
    private float[] nodeCenters = new float[0];

    /**
     * @param centerX    x of the ring's centre in screen pixels
     * @param centerY    y of the ring's centre in screen pixels
     * @param ringRadius radius the node centres sit on
     * @param nodeRadius radius of one node body
     */
    public FsmGraphLayout(float centerX, float centerY, float ringRadius, float nodeRadius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.ringRadius = ringRadius;
        this.nodeRadius = nodeRadius;
    }

    /**
     * Computes the node centres for {@code count} states.
     *
     * @param count number of states; {@code 0} clears the layout
     * @return this layout, for chaining
     */
    public FsmGraphLayout layout(int count) {
        if (count < 0) count = 0;
        if (nodeCenters.length != count * XY_PER_NODE) {
            nodeCenters = new float[count * XY_PER_NODE];
        }
        // A single node sits dead centre; otherwise the states spread evenly over the full circle,
        // the first one at the TOP (+90° in the GL bottom-left space) so the machine's start state
        // is easy to find, then clockwise.
        for (int i = 0; i < count; i++) {
            if (count == 1) {
                nodeCenters[0] = centerX;
                nodeCenters[1] = centerY;
                break;
            }
            double angle = Math.PI / 2.0 - (2.0 * Math.PI * i) / count;
            nodeCenters[i * 2] = centerX + (float) (Math.cos(angle) * ringRadius);
            nodeCenters[i * 2 + 1] = centerY + (float) (Math.sin(angle) * ringRadius);
        }
        return this;
    }

    /**
     * Re-places the ring's centre (the screen's bottom-left corner keeps a fixed distance) and
     * recomputes every node position immediately.
     *
     * <p>Reuses the existing arrays, so calling it on every resize allocates nothing.</p>
     *
     * @param x new centre x in screen pixels
     * @param y new centre y in screen pixels
     * @return this layout, for chaining
     */
    public FsmGraphLayout moveTo(float x, float y) {
        if (x == centerX && y == centerY) return this;
        centerX = x;
        centerY = y;
        return layout(nodeCount());
    }

    /** Number of nodes the current layout was built for. */
    public int nodeCount() {
        return nodeCenters.length / XY_PER_NODE;
    }

    /** x of node {@code index}'s centre. */
    public float nodeX(int index) {
        return nodeCenters[index * 2];
    }

    /** y of node {@code index}'s centre. */
    public float nodeY(int index) {
        return nodeCenters[index * 2 + 1];
    }

    /** Radius of a node body. */
    public float nodeRadius() {
        return nodeRadius;
    }

    /** Radius of the ring the node centres sit on. */
    public float ringRadius() {
        return ringRadius;
    }

    /**
     * The node under a screen-space point.
     *
     * @param screenX cursor x in pixels (GL bottom-left origin)
     * @param screenY cursor y in pixels
     * @return the node index, or {@code -1} when the point hits no node
     */
    public int hitTest(float screenX, float screenY) {
        float r2 = nodeRadius * nodeRadius;
        for (int i = 0; i < nodeCount(); i++) {
            float dx = screenX - nodeX(i);
            float dy = screenY - nodeY(i);
            if (dx * dx + dy * dy <= r2) return i;
        }
        return -1;
    }

    /**
     * Router of the arrow that leads from node {@code from} to node {@code to}: a chord trimmed to
     * both node borders and offset sideways when the edge is part of a bidirectional pair.
     *
     * <p>Create one per arrow direction and call {@link #segment(int, int, float[])} every frame;
     * the router keeps no state, so a single instance serves the whole graph.</p>
     */
    public final class Arrow {
        /** Trim added to {@code nodeRadius} so the head does not overlap the border. */
        private float borderTrim = UiLayout.FSM_LINE_WIDTH;

        /** Sets the extra trim between the node border and the arrow. */
        public Arrow borderTrim(float trim) {
            this.borderTrim = Math.max(0f, trim);
            return this;
        }

        /**
         * Computes {@code from -> to}.
         *
         * <p>A chord with no room for an arrow head between the two node borders (see the class
         * documentation) collapses to the <b>centre</b> of the source node, so the head and the shaft
         * vanish inside the node instead of poking through it — and no caller can read a {@code NaN}
         * from a zero-length chord either.</p>
         *
         * @param from source node index
         * @param to   target node index
         * @param out  receives {@code x0, y0, x1, y1}; the head belongs at {@code (x1, y1)}.
         *             Must hold at least {@value #XY_PER_ARROW} floats — use
         *             {@link FsmGraphLayout#XY_PER_ARROW}, not {@link FsmGraphLayout#XY_PER_NODE}
         * @return {@code out}
         */
        public float[] segment(int from, int to, float[] out) {
            float x0 = nodeX(from), y0 = nodeY(from);
            float x1 = nodeX(to), y1 = nodeY(to);
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4f) {
                out[0] = x0; out[1] = y0; out[2] = x0; out[3] = y0;
                return out;
            }
            float ux = dx / len, uy = dy / len;

            // Bidirectional pairs: shift each direction to its own side of the chord.
            float offset = from != to ? UiLayout.FSM_ARROW_SPREAD : 0f;
            float px = -uy * offset, py = ux * offset;

            float start = nodeRadius + borderTrim;
            float end = nodeRadius + borderTrim + UiLayout.FSM_ARROW_HEAD;
            if (len < start + end) {
                // Two neighbouring nodes so close that the trim of both ends already overlaps (on a
                // small window: the graph and the nodes are both sized in plain pixels). No arrow
                // fits in there, so hide it inside the source node.
                out[0] = x0; out[1] = y0; out[2] = x0; out[3] = y0;
                return out;
            }

            out[0] = x0 + ux * start + px;
            out[1] = y0 + uy * start + py;
            out[2] = x1 - ux * end + px;
            out[3] = y1 - uy * end + py;
            return out;
        }

        /**
         * Point at {@code t} in {@code 0..1} along {@code from -> to}, used to walk a dot along the
         * active transition while it crossfades.
         *
         * @param from source node index
         * @param to   target node index
         * @param t    progress along the arrow ({@code 0} = source border, {@code 1} = arrow head)
         * @param segment reusable {@value #XY_PER_ARROW}-element scratch array
         * @param out      receives {@code x, y}
         */
        public void point(int from, int to, float t, float[] segment, float[] out) {
            segment(from, to, segment);
            float k = Math.max(0f, Math.min(1f, t));
            out[0] = segment[0] + (segment[2] - segment[0]) * k;
            out[1] = segment[1] + (segment[3] - segment[1]) * k;
        }
    }

    /** Creates a router for one arrow direction (see {@link Arrow}). */
    public Arrow arrow() {
        return new Arrow();
    }
}
