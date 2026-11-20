package com.jb.rts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the <b>Count (Enter)</b> field of the parameter panel: the player must accept a
 * positive whole number and clamp it to the capacity of its VAT instance batches, but must not
 * apply a blank, negative or non-numeric entry.
 */
class MeshPlayerInstanceCountTest {

    @Test
    void acceptsPositiveWholeNumbers() {
        assertEquals(7, MeshPlayer.parseInstanceCount("7"));
        assertEquals(7, MeshPlayer.parseInstanceCount("  7  "));
        assertEquals(2500, MeshPlayer.parseInstanceCount("2500"));
        assertEquals(MeshPlayer.MAX_INSTANCE_COUNT,
            MeshPlayer.parseInstanceCount(Integer.toString(MeshPlayer.MAX_INSTANCE_COUNT)));
    }

    @Test
    void rejectsEverythingThatIsNotAPositiveWholeNumber() {
        assertEquals(-1, MeshPlayer.parseInstanceCount(null));
        assertEquals(-1, MeshPlayer.parseInstanceCount(""));
        assertEquals(-1, MeshPlayer.parseInstanceCount("   "));
        assertEquals(-1, MeshPlayer.parseInstanceCount("abc"));
        assertEquals(-1, MeshPlayer.parseInstanceCount("12x"));
        assertEquals(-1, MeshPlayer.parseInstanceCount("1.5"));
        assertEquals(-1, MeshPlayer.parseInstanceCount("0"));
        assertEquals(-1, MeshPlayer.parseInstanceCount("-5"));
    }

    @Test
    void clampsToTheBatchCapacity() {
        assertEquals(1, MeshPlayer.clampInstanceCount(0));
        assertEquals(1, MeshPlayer.clampInstanceCount(-20));
        assertEquals(1, MeshPlayer.clampInstanceCount(1));
        assertEquals(500, MeshPlayer.clampInstanceCount(500));
        assertEquals(MeshPlayer.MAX_INSTANCE_COUNT,
            MeshPlayer.clampInstanceCount(MeshPlayer.MAX_INSTANCE_COUNT));
        assertEquals(MeshPlayer.MAX_INSTANCE_COUNT,
            MeshPlayer.clampInstanceCount(MeshPlayer.MAX_INSTANCE_COUNT + 1));
    }
}
