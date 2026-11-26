package com.jb.rts.mesh.blend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protects {@link MeshBlendAnimator}: slot packing, the crossfade ramp, the snap rules (explicit
 * snap fades and step frames) and the reusable {@link MeshBlendCommand} contract.
 */
class MeshBlendAnimatorTest {

    /** Two zero offset (the export's four shared vertices) for one animation frame. */
    private static final String ZERO_OFFSETS = """
            [ { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 },
              { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 } ]""";

    /**
     * Three animations of the same mesh, all at 4 fps on a 0.25 s grid:
     * {@code idle} (0, 0.25, 0.5), {@code walk} (0, 0.25, 0.5) and {@code hit} (0, 0.25, 0.5,
     * non-looping, emitted with attachment step frames so it must never be blended).
     */
    private static MeshExport sampleExport() {
        String json = """
                {
                  "name": "t",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1.0 } ],
                  "vertices": [
                    { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 1.0, "y": 0.0, "z": 0.0 },
                    { "x": 0.0, "y": 1.0, "z": 0.0 }, { "x": 1.0, "y": 1.0, "z": 0.0 }
                  ],
                  "objects": [ { "name": "o", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                    { "vertices": [0, 2, 3], "colors": [0, 0, 0] } ] } ],
                  "animations": [
                    { "name": "idle", "looping": true, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s },
                        { "name": "c", "offsets": %1$s } ] },
                    { "name": "walk", "looping": true, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s },
                        { "name": "c", "offsets": %1$s } ] },
                    { "name": "hit", "looping": false, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "attachmentSteps": [true, false, false],
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s },
                        { "name": "c", "offsets": %1$s } ] },
                    { "name": "turn", "looping": true, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s },
                        { "name": "c", "offsets": %1$s } ] }
                  ]
                }
                """.formatted(ZERO_OFFSETS);
        return MeshExportLoader.loadFromString(json);
    }

    private static MeshAnimationClip clip(String anim, boolean looping) {
        return MeshAnimationClip.fromExport(sampleExport(), anim, looping);
    }

    /** Sum of the slot weights (what the blender normalises by). */
    private static float total(MeshBlendCommand cmd) {
        return cmd.totalWeight();
    }

    // -----------------------------------------------------------------------
    // Normal playback
    // -----------------------------------------------------------------------

    @Test
    void constructorWritesSlotZeroOnly() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        MeshBlendCommand cmd = animator.command();
        assertEquals("t_idle_0", cmd.names[0]);
        assertEquals(1f, cmd.weights[0], 1e-6f);
        assertEquals(1, cmd.activeSlots());
        assertEquals(1f, total(cmd), 1e-6f);
        assertFalse(animator.isCrossfading());
        assertEquals(0f, animator.stateTime(), 1e-6f);
    }

    @Test
    void updatePacksTheTwoInterpolatedPosesIntoSlotsZeroAndOne() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        MeshBlendCommand cmd = animator.update(0.125f); // halfway between pose 0 and 1
        assertEquals("t_idle_0", cmd.names[0]);
        assertEquals(0.5f, cmd.weights[0], 1e-5f);
        assertEquals("t_idle_1", cmd.names[1]);
        assertEquals(0.5f, cmd.weights[1], 1e-5f);
        assertNull(cmd.names[2], "plain playback never touches the crossfade slots");
        assertNull(cmd.names[3]);
        assertEquals(1f, total(cmd), 1e-5f);
        assertEquals(0.125f, animator.stateTime(), 1e-6f);
    }

    @Test
    void updateNeverAllocatesANewCommand() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));
        MeshBlendCommand first = animator.command();

        assertSame(first, animator.update(0.1f));
        assertSame(first, animator.update(0.1f));
        assertSame(first, animator.command());
        assertSame(first, animator.update(-1f), "a negative delta is ignored, not a new command");
    }

    @Test
    void loopingClipNeverFinishes() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        animator.update(10f);
        assertFalse(animator.isFinished());
    }

    @Test
    void nonLoopingClipFinishesOnItsLastPose() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("hit", false));

        animator.update(0.1f);
        assertFalse(animator.isFinished());

        MeshBlendCommand cmd = animator.update(0.5f);
        assertTrue(animator.isFinished());
        assertEquals("t_hit_2", cmd.names[0], "the last pose is held");
        assertEquals(1f, cmd.weights[0], 1e-6f);
        assertEquals(1, cmd.activeSlots());
    }

    // -----------------------------------------------------------------------
    // Play requests
    // -----------------------------------------------------------------------

    @Test
    void playRejectsNull() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        assertThrows(IllegalArgumentException.class, () -> animator.play(null, 0.2f));
        assertThrows(IllegalArgumentException.class, () -> new MeshBlendAnimator(null));
    }

    @Test
    void playingTheCurrentClipIsANoOpWithoutForceRestart() {
        MeshAnimationClip idle = clip("idle", true);
        MeshBlendAnimator animator = new MeshBlendAnimator(idle);
        animator.update(0.1f);

        assertFalse(animator.play(idle, 0.2f, false), "state machines may call play() every frame");
        assertEquals(0.1f, animator.stateTime(), 1e-6f, "the clip was not restarted");
        assertFalse(animator.isCrossfading());
    }

    @Test
    void forceRestartRewindsTheSameClip() {
        MeshAnimationClip idle = clip("idle", true);
        MeshBlendAnimator animator = new MeshBlendAnimator(idle);
        animator.update(0.1f);

        assertTrue(animator.play(idle, 0.2f), "play(clip, fade) always restarts");
        assertEquals(0f, animator.stateTime(), 1e-6f);
        assertEquals("t_idle_0", animator.command().names[0]);
        assertEquals(1, animator.command().activeSlots());
    }

    // -----------------------------------------------------------------------
    // Snaps
    // -----------------------------------------------------------------------

    @Test
    void zeroFadeSnapsStraightToTheNewClip() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        assertTrue(animator.play(clip("walk", true), 0f));
        MeshBlendCommand cmd = animator.command();
        assertEquals("t_walk_0", cmd.names[0]);
        assertEquals(1f, cmd.weights[0], 1e-6f);
        assertEquals(1, cmd.activeSlots());
        assertFalse(animator.isCrossfading());
        assertNull(animator.outgoingClip());
    }

    @Test
    void stepFrameClipsAreNeverBlended() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        // A normal fade duration, but 'hit' shows attachment step frames -> snap anyway.
        assertTrue(animator.play(clip("hit", false), 0.5f));
        assertFalse(animator.isCrossfading(), "attachment geometry must not be crossfaded");
        assertNull(animator.outgoingClip());
        assertEquals("t_hit_0", animator.command().names[0]);
        assertEquals(1f, animator.command().weights[0], 1e-6f);
    }

    @Test
    void leavingAStepFrameClipAlsoSnaps() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("hit", false));
        animator.update(0.1f);

        // The outgoing clip is the one with step frames; blending it would stretch attachments.
        assertTrue(animator.play(clip("idle", true), 0.2f));
        assertFalse(animator.isCrossfading());
        assertEquals(1, animator.command().activeSlots());
    }

    // -----------------------------------------------------------------------
    // Crossfade
    // -----------------------------------------------------------------------

    @Test
    void crossfadeFillsAllFourSlotsAndRampsTheFade() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));
        animator.update(0.125f); // idle sits between its poses 0 and 1

        assertTrue(animator.play(clip("walk", true), 0.4f));
        assertTrue(animator.isCrossfading());
        assertEquals(0f, animator.fade(), 1e-6f, "a fresh fade starts fully on the outgoing clip");

        MeshBlendCommand cmd = animator.command();
        assertEquals("t_idle_0", cmd.names[0], "outgoing poses in slots 0/1");
        assertEquals("t_idle_1", cmd.names[1]);
        assertEquals(0.5f, cmd.weights[0], 1e-5f);
        assertEquals(0.5f, cmd.weights[1], 1e-5f);
        assertNull(cmd.names[2], "at fade 0 the incoming side has no weight and is left out");
        assertEquals(2, cmd.activeSlots());

        animator.update(0.2f); // half of the 0.4 s fade
        assertEquals(0.5f, animator.fade(), 1e-5f);
        cmd = animator.command();
        assertEquals("t_walk_0", cmd.names[2], "incoming poses in slots 2/3 once the fade runs");
        // Both clips keep animating, so compare side totals (each side is normalised to 1).
        float outgoingTotal = cmd.weights[0] + cmd.weights[1];
        float incomingTotal = cmd.weights[2] + cmd.weights[3];
        assertEquals(0.5f, outgoingTotal, 1e-4f);
        assertEquals(0.5f, incomingTotal, 1e-4f);
        assertEquals(1f, total(cmd), 1e-5f, "the blend is always normalised");
        assertEquals(4, cmd.activeSlots());
    }

    @Test
    void crossfadeCompletesAfterTheFadeDuration() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        animator.play(clip("walk", true), 0.4f);
        animator.update(0.4f);

        assertEquals(1f, animator.fade(), 1e-6f);
        assertFalse(animator.isCrossfading(), "the outgoing clip is dropped");
        assertNull(animator.outgoingClip());
        MeshBlendCommand cmd = animator.command();
        assertEquals("t_walk_1", cmd.names[0], "walk advanced 0.4 s into its second interval");
        assertEquals(0.4f, cmd.weights[0], 1e-5f);
        assertEquals(0.6f, cmd.weights[1], 1e-5f);
        assertNull(cmd.names[2], "crossfade slots are cleared again");
        assertEquals(2, cmd.activeSlots());
    }

    @Test
    void interruptedFadeKeepsTheProgressItHadReached() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        animator.play(clip("walk", true), 0.4f);
        animator.update(0.2f);
        float reached = animator.fade();
        assertEquals(0.5f, reached, 1e-5f);

        // A third clip arrives mid-fade: the visible weight must not jump back to 0.
        assertTrue(animator.play(clip("turn", true), 0.4f));
        assertTrue(animator.fade() >= reached - 1e-6f,
            "interrupting a fade must not restart it from 0, was " + animator.fade());
        assertEquals("t_walk_0", animator.command().names[0],
            "the clip that was fading in becomes the outgoing side");
    }

    @Test
    void fadeProgressIsClampedToOne() {
        MeshBlendAnimator animator = new MeshBlendAnimator(clip("idle", true));

        animator.play(clip("walk", true), 0.1f);
        animator.update(5f); // far beyond the fade
        assertEquals(1f, animator.fade(), 1e-6f);
        assertFalse(animator.isCrossfading());
    }
}
