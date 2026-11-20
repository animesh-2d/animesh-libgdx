package com.jb.rts.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InstanceField} — the placement/size generator the mesh player uses. The
 * guaranteed properties are: the array is sorted by <b>descending y</b> (that order is the
 * z-order of the single instanced draw), every copy lies inside the field extent, and the random
 * size stays inside the requested deviation band.
 */
class InstanceFieldTest {

    private static final float EPS = 1e-4f;

    @Test
    void gridIsSortedByDescendingY() {
        InstanceField field = InstanceField.build(InstanceField.Layout.GRID, 6, 10f, 0f, 0f,
            0f, 1L);

        assertEquals(6, field.size());
        for (int i = 1; i < field.size(); i++) {
            assertTrue(field.y(i) <= field.y(i - 1),
                "instance " + i + " must not be drawn behind instance " + (i - 1));
        }
        assertTrue(field.extent() > 10f, "the field is larger than one cell");
    }

    @Test
    void gridIsCenteredAndItsCopiesKeepTheAuthoredSize() {
        float centerX = 40f;
        float centerY = -25f;
        InstanceField field = InstanceField.build(InstanceField.Layout.GRID, 4, 8f, centerX,
            centerY, 0f, 1L);

        for (int i = 0; i < field.size(); i++) {
            assertEquals(1f, field.scale(i), EPS, "no deviation requested");
            assertTrue(Math.abs(field.x(i) - centerX) <= field.extent() * 0.5f, "x inside extent");
            assertTrue(Math.abs(field.y(i) - centerY) <= field.extent() * 0.5f, "y inside extent");
        }
    }

    @Test
    void aSingleCopySitsOnTheCenter() {
        InstanceField field = InstanceField.build(InstanceField.Layout.GRID, 1, 10f, 5f, 7f,
            0f, 1L);

        assertEquals(1, field.size());
        assertEquals(5f, field.x(0), EPS);
        assertEquals(7f, field.y(0), EPS);
    }

    @Test
    void randomLayoutStaysInsideTheFieldAndIsZSorted() {
        InstanceField field = InstanceField.build(InstanceField.Layout.RANDOM, 200, 10f, 0f, 0f,
            0f, 42L);

        assertEquals(200, field.size());
        float half = field.extent() * 0.5f;
        for (int i = 0; i < field.size(); i++) {
            assertTrue(Math.abs(field.x(i)) <= half, "x of instance " + i);
            assertTrue(Math.abs(field.y(i)) <= half, "y of instance " + i);
            if (i > 0) {
                assertTrue(field.y(i) <= field.y(i - 1), "random field must be z-sorted");
            }
        }
    }

    @Test
    void sizeDeviationStaysInsideTheRequestedBand() {
        InstanceField field = InstanceField.build(InstanceField.Layout.GRID, 100, 10f, 0f, 0f,
            30f, 7L);

        boolean smaller = false;
        boolean larger = false;
        for (int i = 0; i < field.size(); i++) {
            float scale = field.scale(i);
            assertTrue(scale >= 0.7f - EPS && scale <= 1.3f + EPS,
                "scale " + scale + " outside the +/-30% band");
            smaller |= scale < 1f;
            larger |= scale > 1f;
        }
        assertTrue(smaller && larger, "the random sizes use both sides of the band");
    }

    @Test
    void theSameSeedProducesTheSameLayout() {
        InstanceField first = InstanceField.build(InstanceField.Layout.RANDOM, 50, 10f, 0f, 0f,
            20f, 99L);
        InstanceField second = InstanceField.build(InstanceField.Layout.RANDOM, 50, 10f, 0f, 0f,
            20f, 99L);

        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.x(i), second.x(i), EPS);
            assertEquals(first.y(i), second.y(i), EPS);
            assertEquals(first.scale(i), second.scale(i), EPS);
        }
    }

    @Test
    void emptyFieldHasNoInstances() {
        InstanceField field = InstanceField.empty();

        assertEquals(0, field.size());
        assertTrue(field.extent() > 0f, "the camera needs a positive extent");
    }
}
