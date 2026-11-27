package com.jb.rts.player.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NumberSpinner}'s arithmetic — the part that decides whether the crossfade
 * value the player receives is legal: it must stay inside {@code 0..60 s}, land on the
 * {@code 100 ms} grid and never be written back as {@code 0.30000001}.
 *
 * <p>Only the static helpers are exercised, so the test is headless (no {@code Skin}, no GL).</p>
 */
class NumberSpinnerTest {

    /** The crossfade spinner's configuration (see {@code FsmControlsSection}). */
    private static final float MIN = 0f;
    private static final float MAX = 60f;
    private static final float STEP = 0.1f;
    private static final int DECIMALS = 1;

    private static float snap(float value) {
        return NumberSpinner.snap(value, MIN, MAX, STEP, DECIMALS);
    }

    @Test
    void valuesBelowTheMinimumSnapToZero() {
        assertEquals(0f, snap(-5f), 1e-6f);
        assertEquals(0f, snap(-0.01f), 1e-6f, "a negative crossfade is not a fade");
    }

    @Test
    void valuesAboveTheMaximumSnapToOneMinute() {
        assertEquals(MAX, snap(61f), 1e-6f);
        assertEquals(MAX, snap(60.06f), 1e-6f);
        assertEquals(60f, MAX, 1e-6f);
    }

    @Test
    void valuesLandOnTheHundredMillisecondGrid() {
        assertEquals(0.3f, snap(0.31f), 1e-6f);
        assertEquals(0.2f, snap(0.17f), 1e-6f);
        assertEquals(0f, snap(0.04f), 1e-6f);
        // 0.1f * 3 is 0.30000001 in binary floating point; the value handed to the machine must not
        // be, or the readout would print 0.30000001 in some locales.
        assertEquals("0.3", NumberSpinner.format(snap(0.3f), DECIMALS));
    }

    @Test
    void aFullMinuteIsReachableInHundredMillisecondSteps() {
        float value = 0f;
        for (int i = 0; i < 600; i++) value = snap(value + STEP);
        assertEquals(MAX, value, 1e-6f, "600 presses of the + button reach 60 s");
    }

    @Test
    void typedTextAcceptsBothDecimalSeparators() {
        assertEquals(0.25f, NumberSpinner.parse("0.25"), 1e-6f);
        assertEquals(0.25f, NumberSpinner.parse(" 0,25 "), 1e-6f);
        assertEquals(12f, NumberSpinner.parse("12"), 1e-6f);
    }

    @Test
    void invalidTextIsReportedAsNotANumber() {
        assertTrue(Float.isNaN(NumberSpinner.parse(null)));
        assertTrue(Float.isNaN(NumberSpinner.parse("")));
        assertTrue(Float.isNaN(NumberSpinner.parse("   ")));
        assertTrue(Float.isNaN(NumberSpinner.parse("abc")));
    }

    @Test
    void formattingUsesTheConfiguredNumberOfDigits() {
        assertEquals("0.2", NumberSpinner.format(0.2f, 1));
        assertEquals("60.0", NumberSpinner.format(60f, 1));
        assertEquals("1", NumberSpinner.format(1f, 0));
    }
}
