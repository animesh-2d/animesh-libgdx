package com.jb.rts.player;

import com.jb.rts.mesh.vat.VatAnimationData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerAnimationFsm}: the data-driven animation state machine of the mesh
 * player. It is GL-free, so it runs headless.
 *
 * <p>Each test protects one documented rule — the looping/one-shot/terminal classification, the
 * crossfade bookkeeping the renderer consumes, and the rule verdict the graph paints green or
 * red.</p>
 */
class PlayerAnimationFsmTest {

    private static final float EPS = 1e-4f;

    // ------------------------------------------------------------------ helpers

    /** A clip of {@code seconds} length with an authored last keyframe time. */
    private static VatAnimationData clip(String name, float seconds, boolean looping) {
        int frames = 3;
        float[] times = {0f, seconds / 2f, seconds};
        return new VatAnimationData(name, 2, frames, 2f, looping, times,
            new float[2 * frames * 16]);
    }

    /** Names + clips for the classic locomotion/action/terminal set. */
    private static PlayerAnimationFsm locomotionFsm() {
        List<String> names = new ArrayList<>(List.of("idle", "walk", "run", "attack", "die"));
        List<VatAnimationData> clips = new ArrayList<>(List.of(
            clip("idle", 1f, true),
            clip("walk", 1f, true),
            clip("run", 0.6f, true),
            clip("attack", 0.5f, false),
            clip("die", 2f, false)));
        return new PlayerAnimationFsm(names, clips);
    }

    private static int indexOf(PlayerAnimationFsm fsm, String name) {
        for (int i = 0; i < fsm.stateCount(); i++) {
            if (fsm.state(i).name().equals(name)) return i;
        }
        throw new IllegalArgumentException("no state " + name);
    }

    // ------------------------------------------------------------------ construction

    @Test
    void oneStatePerAnimationAndStartsInIdle() {
        PlayerAnimationFsm fsm = locomotionFsm();
        assertEquals(5, fsm.stateCount());
        assertEquals("idle", fsm.current().name());
        assertEquals(0f, fsm.localTime(), EPS);
        assertFalse(fsm.isCrossfading());
        assertSame(fsm, fsm.getOwner(), "the machine is its own owner");
    }

    @Test
    void startsInTheFirstLoopingStateWhenThereIsNoIdle() {
        List<String> names = List.of("attack", "walk");
        List<VatAnimationData> clips = List.of(clip("attack", 0.5f, false), clip("walk", 1f, true));
        PlayerAnimationFsm fsm = new PlayerAnimationFsm(names, clips);
        assertEquals("walk", fsm.current().name(), "an action is not a sensible start state");
    }

    @Test
    void rulesAreInferredPerClip() {
        PlayerAnimationFsm fsm = locomotionFsm();
        assertEquals(AnimationRule.LOOPING, fsm.rule(indexOf(fsm, "walk")));
        assertEquals(AnimationRule.ONE_SHOT, fsm.rule(indexOf(fsm, "attack")));
        assertEquals(AnimationRule.TERMINAL, fsm.rule(indexOf(fsm, "die")));
    }

    // ------------------------------------------------------------------ crossfade

    @Test
    void transitionBlendsFromTheOutgoingPoseIntoTheIncomingOne() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0.2f);

        assertTrue(fsm.requestState(indexOf(fsm, "walk")));
        assertEquals("walk", fsm.current().name(), "the incoming state is current immediately");
        assertEquals("idle", fsm.outgoing().name());
        assertEquals(0, fsm.blendTarget(), "blend target is the outgoing clip");
        assertEquals(1f, fsm.blendWeight(), EPS,
            "the fade starts fully on the outgoing pose (AnimationBlend: 1 = target clip)");
        assertEquals(0f, fsm.crossfadeProgress(), EPS, "the readout starts at 0 %");

        fsm.update(0.1f);
        assertEquals(0.5f, fsm.blendWeight(), EPS);
        assertEquals(0.5f, fsm.crossfadeProgress(), EPS);
        assertTrue(fsm.isCrossfading());

        fsm.update(0.1f);
        assertFalse(fsm.isCrossfading(), "0.2 s of fade is over");
        assertEquals(-1, fsm.blendTarget(), "no blend target once committed");
        assertEquals(0f, fsm.blendWeight(), EPS);
        assertEquals(1f, fsm.crossfadeProgress(), EPS);
    }

    @Test
    void incomingClipPlaysThroughTheFadeInsteadOfRestartingOnCommit() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0.2f);
        fsm.requestState(indexOf(fsm, "run"));

        // The incoming clip's clock starts with the fade and keeps running; committing must not
        // rewind it (that used to snap a long fade back by up to its own length).
        fsm.update(0.1f);
        assertEquals(0.1f, fsm.localTime(), 1e-3f, "halfway through the fade, halfway into run");

        fsm.update(0.1f);
        assertFalse(fsm.isCrossfading());
        assertEquals(0.2f, fsm.localTime(), 1e-3f, "the commit continues the clock, it does not reset it");

        fsm.update(0.1f);
        assertEquals(0.3f, fsm.localTime(), 1e-3f, "and it keeps playing on from there");
    }

    @Test
    void zeroFadeIsAHardCut() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "walk"));
        assertFalse(fsm.isCrossfading());
        assertEquals(-1, fsm.blendTarget());
    }

    @Test
    void fadeTimeIsClampedToTheDocumentedRange() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(-1f);
        assertEquals(0f, fsm.fadeSeconds(), EPS);
        fsm.setFadeSeconds(99f);
        assertEquals(PlayerAnimationFsm.MAX_FADE_SECONDS, fsm.fadeSeconds(), EPS);
        assertEquals(60f, PlayerAnimationFsm.MAX_FADE_SECONDS, EPS, "the spinner ends at one minute");
        assertEquals(0.1f, PlayerAnimationFsm.FADE_STEP_SECONDS, EPS, "one step is 100 ms");
    }

    @Test
    void loopingStatesReachEachOtherFreely() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        assertTrue(fsm.requestState(indexOf(fsm, "run")));
        assertTrue(fsm.requestState(indexOf(fsm, "walk")));
        assertTrue(fsm.requestState(indexOf(fsm, "idle")));
        assertEquals("idle", fsm.current().name());
    }

    @Test
    void reRequestingTheCurrentStateKeepsItsPhase() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.update(0.25f);
        float before = fsm.localTime();
        assertTrue(fsm.requestState(0), "requesting the current state is allowed (a no-op)");
        assertEquals(before, fsm.localTime(), EPS, "walk does not restart when clicked again");
    }

    // ------------------------------------------------------------------ manual selection

    /**
     * Regression: the player's animation paging moved {@code currentAnim} directly, so the graph
     * kept highlighting the clip the machine last saw and a click on the clip that <em>was</em> on
     * screen did nothing (the machine believed it was already there).
     */
    @Test
    void followMovesTheStateAndRestartsTheClock() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0.2f);
        fsm.update(0.4f);
        assertTrue(fsm.localTime() > 0f, "the idle clock has advanced");

        assertTrue(fsm.follow(indexOf(fsm, "run")));
        assertEquals("run", fsm.current().name(), "the graph follows the clip the player draws");
        assertEquals(0f, fsm.localTime(), EPS, "a manual pick restarts that clip");
        assertFalse(fsm.isCrossfading(), "manual paging is a hard cut, never a fade");
        assertEquals(-1, fsm.blendTarget(), "no outgoing clip after a manual pick");
        assertEquals(0f, fsm.blendWeight(), EPS);

        // The whole point: a later click on another node is a real transition from the new state.
        assertTrue(fsm.requestState(indexOf(fsm, "walk")));
        assertEquals("walk", fsm.current().name());
    }

    @Test
    void followEscapesATerminalStateBecauseItIsNotATransition() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "die"));
        assertFalse(fsm.isRuleAllowed(indexOf(fsm, "walk")), "a terminal state's rule allows no exit");

        assertTrue(fsm.follow(indexOf(fsm, "walk")), "manual paging always works");
        assertEquals("walk", fsm.current().name());

        assertFalse(fsm.follow(-1), "an out-of-range index is ignored");
        assertFalse(fsm.follow(fsm.stateCount()), "an out-of-range index is ignored");
        assertEquals("walk", fsm.current().name());
    }

    // ------------------------------------------------------------------ one-shot actions

    @Test
    void oneShotRuleBlocksLocomotionButTheTransitionStillHappens() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        int attack = indexOf(fsm, "attack");

        assertTrue(fsm.requestState(attack));
        assertEquals("attack", fsm.current().name());

        // The rule reports locomotion as illegal while the action runs — the graph paints those
        // nodes red — but the player never refuses a request.
        assertFalse(fsm.isRuleAllowed(indexOf(fsm, "walk")));
        assertFalse(fsm.isRuleAllowed(indexOf(fsm, "run")));
        assertFalse(fsm.isRuleAllowed(0), "idle is locomotion, too");
        assertTrue(fsm.requestState(indexOf(fsm, "walk")), "the transition happens anyway");
        assertEquals("walk", fsm.current().name());
    }

    @Test
    void anotherActionMayInterruptARunningAction() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "attack"));
        assertTrue(fsm.requestState(indexOf(fsm, "die")),
            "a terminal state is not locomotion, so it may take over");
    }

    @Test
    void actionReturnsToTheInterruptedLocomotionState() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "run"));
        fsm.requestState(indexOf(fsm, "attack"));

        // Walk the action's clock past its 0.5 s length.
        fsm.update(0.5f);
        assertEquals("run", fsm.current().name(), "returns to the state it interrupted");
    }

    @Test
    void finishedActionFallsBackToIdleWhenItStartedFromIdle() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "attack"));
        fsm.update(0.5f);
        assertEquals("idle", fsm.current().name());
    }

    @Test
    void actionWithoutAuthoredTimingNeverEndsByItself() {
        // duration() == 0: there is no end to detect, so the state stays until it is forced.
        List<String> names = List.of("idle", "jump");
        List<VatAnimationData> clips = List.of(
            VatAnimationData.uniform("idle", 1, 4, 12f, true, new float[64]),
            new VatAnimationData("jump", 1, 1, 12f, false, new float[]{0f}, new float[16]));
        PlayerAnimationFsm fsm = new PlayerAnimationFsm(names, clips);
        fsm.setFadeSeconds(0f);
        fsm.requestState(1);
        fsm.update(5f);
        assertEquals("jump", fsm.current().name());
    }

    // ------------------------------------------------------------------ terminal states

    @Test
    void terminalRuleAllowsNoExitButEveryTransitionStillHappens() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        int die = indexOf(fsm, "die");
        fsm.requestState(die);
        assertEquals("die", fsm.current().name());

        fsm.update(5f); // long past its 2 s length — it holds the last frame
        assertEquals("die", fsm.current().name(), "die never leaves by itself");
        for (int i = 0; i < fsm.stateCount(); i++) {
            if (i == die) continue;
            assertFalse(fsm.isRuleAllowed(i), fsm.state(i).name() + " is red on the graph");
            assertTrue(fsm.requestState(i), "but the click still works — the rules never block");
            fsm.requestState(die);
        }
        assertEquals("die", fsm.current().name());
    }

    @Test
    void resetReturnsToTheStartState() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "die"));
        fsm.update(1f);

        fsm.reset();
        assertEquals("idle", fsm.current().name());
        assertEquals(0f, fsm.localTime(), EPS);
        assertFalse(fsm.isCrossfading());
        assertTrue(fsm.isRuleAllowed(indexOf(fsm, "walk")));
    }

    // ------------------------------------------------------------------ transition rules as a verdict

    @Test
    void invalidIndexIsRefusedWithoutThrowing() {
        PlayerAnimationFsm fsm = locomotionFsm();
        assertFalse(fsm.requestState(-1));
        assertFalse(fsm.requestState(99));
        assertFalse(fsm.isRuleAllowed(-1));
        assertEquals("idle", fsm.current().name());
    }

    @Test
    void machineDrivenTransitionAfterAOneShotEndsOnTheReturnState() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "attack"));
        fsm.update(0.5f); // completes -> machine-driven return to idle
        assertEquals("idle", fsm.current().name());
    }

    // ------------------------------------------------------------------ clocks

    @Test
    void loopingClipWrapsItsClock() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.update(1.25f); // idle is 1 s long
        assertEquals(0.25f, fsm.localTime(), 1e-3f);
    }

    @Test
    void terminalClipClampsItsClockToTheLastFrame() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.requestState(indexOf(fsm, "die"));
        fsm.update(10f);
        assertEquals(2f, fsm.localTime(), EPS, "die holds its final pose");
    }

    @Test
    void pauseFreezesTheClockAndStepAdvancesOneFrame() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.setPaused(true);
        fsm.update(1f);
        assertEquals(0f, fsm.localTime(), EPS, "paused: time stands still");

        assertTrue(fsm.stepOneFrame());
        assertEquals(1f / 60f, fsm.localTime(), EPS);

        fsm.setPaused(false);
        assertFalse(fsm.stepOneFrame(), "stepping is a paused-only action");
    }

    @Test
    void timeScaleMultipliesTheClock() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        fsm.setTimeScale(2f);
        fsm.update(0.25f);
        assertEquals(0.5f, fsm.localTime(), EPS);

        fsm.setTimeScale(9f);
        assertEquals(2f, fsm.timeScale(), EPS, "clamped to the slider's range");
    }

    // ------------------------------------------------------------------ live rule overrides

    @Test
    void ruleOverrideChangesTheBehaviourOfAState() {
        PlayerAnimationFsm fsm = locomotionFsm();
        fsm.setFadeSeconds(0f);
        int walk = indexOf(fsm, "walk");

        fsm.setRuleOverride(walk, AnimationRule.TERMINAL);
        assertTrue(fsm.hasRuleOverride(walk));
        assertEquals(AnimationRule.TERMINAL, fsm.rule(walk));

        fsm.requestState(walk);
        assertFalse(fsm.isRuleAllowed(0), "walk now behaves like a terminal state");

        fsm.clearRuleOverrides();
        assertFalse(fsm.hasRuleOverride(walk));
        assertEquals(AnimationRule.LOOPING, fsm.rule(walk));
        assertTrue(fsm.isRuleAllowed(0));
    }

    @Test
    void allowedTargetsCanReuseACallerArray() {
        PlayerAnimationFsm fsm = locomotionFsm();
        boolean[] first = fsm.allowedTargets(null);
        assertEquals(fsm.stateCount(), first.length);
        boolean[] second = fsm.allowedTargets(first);
        assertSame(first, second, "no allocation when the caller reuses its array");
        assertNull(fsm.state(99));
    }
}
