package com.jb.rts.player.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FsmGraphLayout}: where the FSM graph puts its nodes and how it routes the
 * arrows. GL-free, so it runs headless — the drawing code in {@link FsmGraphOverlay} is a thin
 * consumer of exactly these numbers.
 */
class FsmGraphLayoutTest {

    private static final float EPS = 1e-3f;
    private static final float CX = 400f;
    private static final float CY = 300f;
    private static final float RING = 120f;
    private static final float NODE = 24f;

    private static FsmGraphLayout layout(int count) {
        return new FsmGraphLayout(CX, CY, RING, NODE).layout(count);
    }

    @Test
    void firstNodeSitsOnTopAndNodesSpreadEvenlyOnTheRing() {
        FsmGraphLayout graph = layout(4);
        assertEquals(4, graph.nodeCount());

        // Node 0 is at the top of the ring (-90°).
        assertEquals(CX, graph.nodeX(0), EPS);
        assertEquals(CY + RING, graph.nodeY(0), EPS);

        // Every node is exactly on the ring.
        for (int i = 0; i < 4; i++) {
            float dx = graph.nodeX(i) - CX;
            float dy = graph.nodeY(i) - CY;
            assertEquals(RING, (float) Math.hypot(dx, dy), EPS, "node " + i + " is on the ring");
        }
        // ... and 90° apart clockwise.
        assertEquals(CX + RING, graph.nodeX(1), EPS);
        assertEquals(CY, graph.nodeY(1), EPS);
    }

    @Test
    void singleNodeIsCentred() {
        FsmGraphLayout graph = layout(1);
        assertEquals(CX, graph.nodeX(0), EPS);
        assertEquals(CY, graph.nodeY(0), EPS);
    }

    @Test
    void emptyLayoutHasNoNodesAndHitsNothing() {
        FsmGraphLayout graph = layout(0);
        assertEquals(0, graph.nodeCount());
        assertEquals(-1, graph.hitTest(CX, CY));
    }

    @Test
    void hitTestFindsTheNodeUnderTheCursorOnlyInsideItsRadius() {
        FsmGraphLayout graph = layout(4);

        assertEquals(0, graph.hitTest(graph.nodeX(0), graph.nodeY(0)), "centre of node 0");
        assertEquals(1, graph.hitTest(graph.nodeX(1) + NODE * 0.9f, graph.nodeY(1)), "near the rim");
        assertEquals(-1, graph.hitTest(CX, CY), "the ring's centre is not a node");
        assertEquals(-1, graph.hitTest(graph.nodeX(0) + NODE * 1.5f, graph.nodeY(0)),
            "outside the radius");
    }

    @Test
    void arrowIsTrimmedToBothNodeBorders() {
        FsmGraphLayout graph = layout(4);
        FsmGraphLayout.Arrow arrow = graph.arrow().borderTrim(2f);
        float[] segment = new float[4];

        arrow.segment(0, 2, segment); // top -> bottom, a straight vertical chord
        // Node 0 is at the top and node 2 at the bottom, so the arrow runs downwards.
        assertTrue(segment[1] < graph.nodeY(0), "starts below the source centre");
        assertTrue(segment[3] > graph.nodeY(2), "ends above the target centre");
        // Both ends are trimmed by the node radius, the border trim and (at the head) the head size.
        assertEquals(graph.nodeY(0) - NODE - 2f, segment[1], EPS, "trimmed at the source border");
        assertEquals(graph.nodeY(2) + NODE + 2f + UiLayout.FSM_ARROW_HEAD, segment[3], EPS,
            "the head sits one head-length before the target border");
    }

    @Test
    void bidirectionalPairIsSplitIntoTwoParallelArrows() {
        FsmGraphLayout graph = layout(4);
        FsmGraphLayout.Arrow arrow = graph.arrow();
        float[] forward = new float[4];
        float[] backward = new float[4];

        arrow.segment(0, 1, forward);
        arrow.segment(1, 0, backward);

        // Both arrows must be offset from the straight chord between the two node centres, to
        // opposite sides, by exactly the configured spread.
        float sideForward = perpendicularOffset(graph, 0, 1, forward);
        float sideBackward = perpendicularOffset(graph, 0, 1, backward);
        assertEquals(UiLayout.FSM_ARROW_SPREAD, Math.abs(sideForward), EPS,
            "the forward arrow is shifted off the chord");
        assertEquals(UiLayout.FSM_ARROW_SPREAD, Math.abs(sideBackward), EPS,
            "the backward arrow is shifted off the chord");
        assertTrue(sideForward * sideBackward < 0f,
            "the two directions sit on opposite sides of the chord");
    }

    /**
     * Signed distance of an arrow segment's start point from the straight line through the two node
     * centres ({@code > 0} on one side, {@code < 0} on the other).
     */
    private static float perpendicularOffset(FsmGraphLayout graph, int from, int to,
                                             float[] segment) {
        float dx = graph.nodeX(to) - graph.nodeX(from);
        float dy = graph.nodeY(to) - graph.nodeY(from);
        float len = (float) Math.hypot(dx, dy);
        // Unit normal of the chord, then the projection of the vector chordStart -> segment start.
        float nx = -dy / len, ny = dx / len;
        float vx = segment[0] - graph.nodeX(from);
        float vy = segment[1] - graph.nodeY(from);
        return vx * nx + vy * ny;
    }

    @Test
    void arrowPointWalksFromOneEndToTheOther() {
        FsmGraphLayout graph = layout(4);
        FsmGraphLayout.Arrow arrow = graph.arrow();
        float[] segment = new float[4];
        float[] point = new float[2];

        arrow.point(0, 1, 0f, segment, point);
        assertEquals(segment[0], point[0], EPS);
        assertEquals(segment[1], point[1], EPS);

        arrow.point(0, 1, 1f, segment, point);
        assertEquals(segment[2], point[0], EPS);
        assertEquals(segment[3], point[1], EPS);

        // Out-of-range progress is clamped instead of overshooting the arrow.
        arrow.point(0, 1, 5f, segment, point);
        assertEquals(segment[2], point[0], EPS);
    }

    @Test
    void relayoutResizesTheNodeArray() {
        FsmGraphLayout graph = layout(3);
        assertEquals(3, graph.nodeCount());
        graph.layout(6);
        assertEquals(6, graph.nodeCount());
        assertFalse(graph.nodeX(5) == 0f && graph.nodeY(5) == 0f, "node 5 was placed");
    }

    @Test
    void crowdedGraphCollapsesArrowsThatDoNotFitInsteadOfInvertingThem() {
        // Regression: with the production sizes (26 px nodes on a 132 px ring) thirteen or more states
        // leave less room between two neighbours (63.2 px) than the trims of both ends (65.4 px), so
        // the old segment() produced a backwards arrow whose head sat OUTSIDE the target node -
        // drawFsmGraph() then threw "Index 2 out of bounds for length 2" on the very first frame.
        FsmGraphLayout graph = new FsmGraphLayout(UiLayout.fsmGraphCenterX(0f),
            UiLayout.fsmGraphCenterY(0f), UiLayout.FSM_RING_RADIUS, UiLayout.FSM_NODE_RADIUS)
            .layout(13);
        FsmGraphLayout.Arrow arrow = graph.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH);
        float[] segment = new float[4];
        float[] point = new float[2];

        // Neighbours on the ring: the closest pair there is, so this is the pairing that breaks.
        arrow.segment(0, 1, segment);

        float cx = graph.nodeX(0), cy = graph.nodeY(0);
        assertEquals(cx, segment[0], EPS, "the shaft collapses onto the source centre");
        assertEquals(cy, segment[1], EPS);
        assertEquals(cx, segment[2], EPS, "and so does the head - a zero-length segment");
        assertEquals(cy, segment[3], EPS);

        // The hidden tip is never a NaN/Inf direction, and it stays on the source side of the ring
        // instead of sweeping across it (63.2 px away from the target, i.e. one node pitch).
        float dx = segment[2] - graph.nodeX(1);
        float dy = segment[3] - graph.nodeY(1);
        assertFalse(Float.isNaN(segment[2]) || Float.isNaN(segment[3]), "no NaN direction");
        assertTrue(Math.hypot(dx, dy) < UiLayout.FSM_RING_RADIUS,
            "the hidden arrow stays at its source, clear of the ring's opposite side");

        // The progress dot of the crossfade follows the collapsed arrow instead of flying off.
        arrow.point(0, 1, 0.5f, segment, point);
        assertEquals(cx, point[0], EPS);
        assertEquals(cy, point[1], EPS);
    }

    @Test
    void segmentBufferIsSizedInArrowsNotNodes() {
        // Regression: FsmGraphOverlay allocated its scratch segment with XY_PER_NODE (a per-node
        // value, 2) instead of a whole segment, so every draw threw
        // "Index 2 out of bounds for length 2" in segment(). The two constants differ by design;
        // a segment is two points.
        assertEquals(2, FsmGraphLayout.XY_PER_NODE, "one point = one coordinate pair");
        assertEquals(4, FsmGraphLayout.XY_PER_ARROW, "a segment = two points");
        // The overlay's own scratch buffer must match, which is what the bug was.
        assertEquals(FsmGraphLayout.XY_PER_ARROW, new FsmGraphOverlay(null).segmentLength(),
            "the overlay sizes its segment buffer in arrows, not in nodes");

        // A correctly sized buffer must be filled end to end, for every routing branch and state
        // count - including the collapsed (degenerate) case, which writes the same four slots.
        float[] segment = new float[FsmGraphLayout.XY_PER_ARROW];
        for (int count = 1; count <= 16; count++) {
            FsmGraphLayout graph = new FsmGraphLayout(UiLayout.fsmGraphCenterX(0f),
            UiLayout.fsmGraphCenterY(0f), UiLayout.FSM_RING_RADIUS, UiLayout.FSM_NODE_RADIUS)
                .layout(count);
            FsmGraphLayout.Arrow arrow = graph.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH);
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    if (from == to) continue;
                    // Populated with sentinels so a branch that under-fills is caught too.
                    java.util.Arrays.fill(segment, Float.NaN);
                    arrow.segment(from, to, segment);
                    for (int i = 0; i < FsmGraphLayout.XY_PER_ARROW; i++) {
                        assertFalse(Float.isNaN(segment[i]), count + " states, " + from + " -> " + to
                            + ": segment[" + i + "] must be written");
                    }
                }
            }
        }

        // The exact frame that crashed: 13 clips, whose neighbour arrow collapses. The degenerate
        // branch writes the same four slots, so a node-sized buffer threw here too.
        FsmGraphLayout crowded = new FsmGraphLayout(UiLayout.fsmGraphCenterX(0f),
            UiLayout.fsmGraphCenterY(0f), UiLayout.FSM_RING_RADIUS, UiLayout.FSM_NODE_RADIUS)
            .layout(13);
        java.util.Arrays.fill(segment, Float.NaN);
        crowded.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH).segment(0, 1, segment);
        assertFalse(Float.isNaN(segment[2]) || Float.isNaN(segment[3]),
            "the collapsed arrow fills all four slots of a segment buffer");
    }

    @Test
    void everyArrowOfEveryGraphSizeKeepsItsHeadOffTheOppositeSideOfTheRing() {
        // Whatever the state count, an arrow either fits between its two nodes or is a point on the
        // source centre - never a backwards arrow, never a NaN. A drawn head may only poke out of its
        // target node by the arrow-head size plus the sideways offset that separates the two
        // directions of a pair, and a hidden (collapsed) one stays on the source side of the ring
        // instead of sweeping across it.
        float overhang = UiLayout.FSM_ARROW_HEAD + UiLayout.FSM_ARROW_SPREAD;
        float oppositeSide = UiLayout.FSM_RING_RADIUS * 0.75f;
        float[] segment = new float[4];
        for (int count = 1; count <= 24; count++) {
            FsmGraphLayout graph = new FsmGraphLayout(UiLayout.fsmGraphCenterX(0f),
            UiLayout.fsmGraphCenterY(0f), UiLayout.FSM_RING_RADIUS, UiLayout.FSM_NODE_RADIUS)
                .layout(count);
            FsmGraphLayout.Arrow arrow = graph.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH);
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    if (from == to) continue;
                    arrow.segment(from, to, segment);
                    String what = count + " states, " + from + " -> " + to;
                    assertFalse(Float.isNaN(segment[0]) || Float.isNaN(segment[2]), what + ": no NaN");

                    float length = (float) Math.hypot(segment[2] - segment[0], segment[3] - segment[1]);
                    boolean collapsed = length < 1e-3f;
                    if (collapsed) {
                        // Hidden: the head sits on the source centre instead of crossing the ring.
                        float toTarget = (float) Math.hypot(segment[2] - graph.nodeX(to),
                            segment[3] - graph.nodeY(to));
                        assertTrue(toTarget < oppositeSide, what + ": hidden head stays at its source");
                    } else {
                        // Drawn: it starts inside the source node and its tip lands on the target one.
                        float startToSource = (float) Math.hypot(segment[0] - graph.nodeX(from),
                            segment[1] - graph.nodeY(from));
                        assertTrue(startToSource <= graph.nodeRadius() + overhang,
                            what + ": the shaft must start inside the source node");
                        float tipToTarget = (float) Math.hypot(segment[2] - graph.nodeX(to),
                            segment[3] - graph.nodeY(to));
                        assertTrue(tipToTarget <= graph.nodeRadius() + overhang,
                            what + ": the head must not poke out of the target node");
                    }
                }
            }
        }
    }

    /**
     * Guards the graph's placement: it must keep a real distance to the bottom-left corner, and that
     * distance must survive a resize.
     *
     * <p>Regression: the graph's centre used to be a hand-tuned constant (116, 168), which put the
     * outermost node ~6 px from the window edge — the graph looked glued into the corner and the
     * bottom node covered the HUD status line.</p>
     */
    @Test
    void graphKeepsTheCornerMarginOnEveryStateCountAndAfterAResize() {
        float[] segment = new float[FsmGraphLayout.XY_PER_ARROW];
        for (int count = 1; count <= 16; count++) {
            FsmGraphLayout graph = new FsmGraphLayout(
                UiLayout.fsmGraphCenterX(1280f), UiLayout.fsmGraphCenterY(720f),
                UiLayout.FSM_RING_RADIUS, UiLayout.FSM_NODE_RADIUS).layout(count);
            assertMargin(graph, count, "initial layout");

            // A resize re-places the ring; the margin is measured to the node border, so it holds.
            graph.moveTo(UiLayout.fsmGraphCenterX(1920f), UiLayout.fsmGraphCenterY(1080f));
            assertMargin(graph, count, "after a resize");

            // The arrows follow the moved nodes: no arrow starts left of the ring's own centre line.
            FsmGraphLayout.Arrow arrow = graph.arrow().borderTrim(UiLayout.FSM_LINE_WIDTH);
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    if (from == to) continue;
                    arrow.segment(from, to, segment);
                    assertTrue(segment[0] >= UiLayout.FSM_GRAPH_MARGIN_X
                        || Math.hypot(segment[2] - segment[0], segment[3] - segment[1]) < 1e-3f,
                        count + " states, " + from + " -> " + to + ": an arrow must stay right of the margin");
                }
            }
        }

        // The graph's widest possible extent (left edge of a node) is exactly the margin away.
        assertEquals(100f, UiLayout.FSM_GRAPH_MARGIN_X, EPS, "the requested distance to the left edge");
        assertEquals(100f, UiLayout.FSM_GRAPH_MARGIN_Y, EPS, "the requested distance to the bottom edge");
    }

    /** Asserts no node border comes closer than the margins to the left and bottom screen edge. */
    private static void assertMargin(FsmGraphLayout graph, int count, String when) {
        for (int i = 0; i < count; i++) {
            float leftEdge = graph.nodeX(i) - UiLayout.FSM_NODE_RADIUS - UiLayout.FSM_LINE_WIDTH;
            float bottomEdge = graph.nodeY(i) - UiLayout.FSM_NODE_RADIUS - UiLayout.FSM_LINE_WIDTH;
            assertTrue(leftEdge >= UiLayout.FSM_GRAPH_MARGIN_X - EPS,
                count + " states, node " + i + " (" + when + "): left edge " + leftEdge
                    + " must keep " + UiLayout.FSM_GRAPH_MARGIN_X + " px from the screen edge");
            assertTrue(bottomEdge >= UiLayout.FSM_GRAPH_MARGIN_Y - EPS,
                count + " states, node " + i + " (" + when + "): bottom edge " + bottomEdge
                    + " must keep " + UiLayout.FSM_GRAPH_MARGIN_Y + " px from the screen edge");
        }
    }
}

