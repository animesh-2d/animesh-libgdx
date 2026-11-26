package com.jb.rts.player;

import com.jb.rts.mesh.vat.VatAnimationData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AnimationRule#infer}: the convention that turns an arbitrary export's
 * animation names into FSM rules. The player loads unknown files, so a wrong classification would
 * silently lock or unlock the wrong states.
 */
class AnimationRuleTest {

    /** Three frames over one second. */
    private static VatAnimationData clip(boolean looping) {
        return new VatAnimationData("clip", 1, 3, 2f, looping, new float[]{0f, 0.5f, 1f},
            new float[48]);
    }

    @Test
    void locomotionNamesLoop() {
        for (String name : new String[]{"idle", "walk", "run", "defend", "rest", "sprint"}) {
            // Even when the clip forgot to declare "looping": the name wins.
            assertEquals(AnimationRule.LOOPING,
                AnimationRule.infer(name, clip(false)), name + " must loop");
        }
    }

    @Test
    void actionNamesAreOneShot() {
        for (String name : new String[]{"attack", "jump", "hurt"}) {
            assertEquals(AnimationRule.ONE_SHOT,
                AnimationRule.infer(name, clip(true)), name + " must be a one-shot action");
        }
    }

    @Test
    void deathNamesAreTerminal() {
        for (String name : new String[]{"die", "death", "dead", "dying"}) {
            assertEquals(AnimationRule.TERMINAL,
                AnimationRule.infer(name, clip(true)), name + " must be terminal");
        }
    }

    @Test
    void unknownNamesFallBackToTheClipsOwnLoopFlag() {
        assertEquals(AnimationRule.LOOPING, AnimationRule.infer("spin", clip(true)));
        assertEquals(AnimationRule.ONE_SHOT, AnimationRule.infer("spin", clip(false)));
        assertEquals(AnimationRule.ONE_SHOT, AnimationRule.infer("spin", null));
    }

    @Test
    void singleFrameClipsNeverLoop() {
        // A one-pose "rest" would look identical when looped; the rule keeps it readable.
        VatAnimationData onePose = new VatAnimationData("rest", 1, 1, 1f, false,
            new float[]{0f}, new float[16]);
        assertEquals(AnimationRule.ONE_SHOT, AnimationRule.infer("rest", onePose));
    }

    @Test
    void namesAreMatchedCaseAndSeparatorInsensitive() {
        assertEquals(AnimationRule.LOOPING, AnimationRule.infer("WALK", null));
        assertEquals(AnimationRule.LOOPING, AnimationRule.infer("walk_01", null));
        assertEquals(AnimationRule.LOOPING, AnimationRule.infer("  Idle  ", null));
        assertEquals(AnimationRule.TERMINAL, AnimationRule.infer("Die (hit)", null));
        assertEquals(AnimationRule.ONE_SHOT, AnimationRule.infer(null, null));
        assertEquals(AnimationRule.ONE_SHOT, AnimationRule.infer("", null));
    }

    @Test
    void ruleLabelsAreTheOnesTheGraphShows() {
        assertEquals("Looping", AnimationRule.LOOPING.label());
        assertEquals("One-Shot \u2192 Idle", AnimationRule.ONE_SHOT.label());
        assertEquals("Terminal", AnimationRule.TERMINAL.label());
    }
}
