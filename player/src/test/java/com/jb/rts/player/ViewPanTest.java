package com.jb.rts.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ViewPan} — the mesh player's drag-to-pan gesture. The guaranteed properties
 * are: the scene follows the cursor (a drag right moves the content right, a drag down moves it
 * down), the pixel delta is converted with the caller's world-per-pixel ratio (so zooming keeps the
 * grabbed point under the cursor), only the owning pointer moves the view, and a new export starts
 * centred again.
 */
class ViewPanTest {

    private static final float EPS = 1e-4f;

    @Test
    void draggingRightMovesTheSceneRight() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);

        // 30 px to the right, 2 world units per pixel: the camera moves 60 units the other way.
        assertTrue(pan.drag(0, 130f, 100f, 2f, 2f));

        assertEquals(-60f, pan.offsetX(), EPS, "the content follows the cursor");
        assertEquals(0f, pan.offsetY(), EPS);
    }

    @Test
    void draggingDownMovesTheSceneDown() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);

        // Screen y grows downwards, world y grows upwards: dragging down pans the camera up.
        assertTrue(pan.drag(0, 100f, 120f, 2f, 5f));

        assertEquals(0f, pan.offsetX(), EPS);
        assertEquals(100f, pan.offsetY(), EPS, "20 px * 5 world units per pixel");
    }

    @Test
    void theDragIsIncrementalAndUsesEachAxisOwnRatio() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);
        pan.drag(0, 110f, 110f, 1f, 2f);   // +10 px right, +10 px down
        pan.drag(0, 120f, 100f, 1f, 2f);   // +10 px right, -10 px up

        assertEquals(-20f, pan.offsetX(), EPS, "10 + 10 px * 1 world unit per pixel");
        assertEquals(0f, pan.offsetY(), EPS, "+10 * 2 then -10 * 2 world units");
    }

    @Test
    void onlyTheOwningPointerMovesTheView() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);

        assertFalse(pan.drag(1, 200f, 200f, 1f, 1f), "a second finger must not pan");
        assertEquals(0f, pan.offsetX(), EPS);
        assertEquals(0f, pan.offsetY(), EPS);
    }

    @Test
    void theReleaseEndsTheDrag() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);
        assertTrue(pan.isDragging());

        assertFalse(pan.end(1), "only the owning pointer's release ends the drag");
        assertTrue(pan.isDragging());

        assertTrue(pan.end(0));
        assertFalse(pan.isDragging(), "the release ends the drag");
        assertFalse(pan.drag(0, 200f, 200f, 1f, 1f), "no panning without a press");
        assertFalse(pan.end(0), "a second release is not our event");
    }

    @Test
    void aNewPressTakesOverAStaleDrag() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);       // the release of this drag was lost
        pan.begin(0, 500f, 500f);       // a fresh press must pan again

        assertTrue(pan.drag(0, 510f, 500f, 1f, 1f));
        assertEquals(-10f, pan.offsetX(), EPS);
    }

    @Test
    void keyboardPanningSharesTheOffset() {
        ViewPan pan = new ViewPan();
        pan.panBy(25f, -12.5f);

        assertEquals(25f, pan.offsetX(), EPS);
        assertEquals(-12.5f, pan.offsetY(), EPS);
    }

    @Test
    void resetCentresTheViewAndDropsTheDrag() {
        ViewPan pan = new ViewPan();
        pan.begin(0, 100f, 100f);
        pan.drag(0, 300f, 300f, 1f, 1f);
        pan.reset();

        assertEquals(0f, pan.offsetX(), EPS);
        assertEquals(0f, pan.offsetY(), EPS);
        assertFalse(pan.isDragging());
        assertFalse(pan.drag(0, 400f, 400f, 1f, 1f), "the old drag must not continue");
    }
}
