package com.jb.rts.mesh.blend;

import com.badlogic.gdx.math.Interpolation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protects {@link MeshAnimationClip}: timeline construction from an export, time mapping, looping
 * wrap, step-frame snapping, easing and the zero-allocation sampling contract.
 */
class MeshAnimationClipTest {

    /** Four zero offsets (the export's shared vertex count) for one animation frame. */
    private static final String ZERO_OFFSETS = """
            [ { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 },
              { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 } ]""";

    /**
     * Four shared vertices, one triangle pair, two animations:
     * {@code walk} (frame times 0, 0.25, 0.5) and {@code die} (two frames at 10 fps).
     *
     * @param walkSteps the {@code attachmentSteps} mask of {@code walk} or {@code null} for none
     */
    private static MeshExport sampleExport(String walkSteps) {
        String steps = walkSteps != null ? ", \"attachmentSteps\": " + walkSteps : "";
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
                    { "name": "walk", "looping": true, "fps": 4,
                      "frameTimes": [0.0, 0.25, 0.5]%2$s,
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s },
                        { "name": "c", "offsets": %1$s } ] },
                    { "name": "die", "fps": 10,
                      "frames": [
                        { "name": "a", "offsets": %1$s },
                        { "name": "b", "offsets": %1$s } ] }
                  ]
                }
                """.formatted(ZERO_OFFSETS, steps);
        return MeshExportLoader.loadFromString(json);
    }

    /** The sample export without attachment step frames. */
    private static MeshExport sampleExport() {
        return sampleExport(null);
    }

    /**
     * The sample export whose {@code walk} animation marks {@code steps} as attachment switches.
     * The mask has to travel through the JSON: {@link MeshExport} exposes its maps as unmodifiable
     * views, exactly like a real load.
     */
    private static MeshAnimationClip clipWithSteps(boolean... steps) {
        StringBuilder mask = new StringBuilder("[");
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) mask.append(", ");
            mask.append(steps[i]);
        }
        mask.append(']');
        return MeshAnimationClip.fromExport(sampleExport(mask.toString()), "walk", true);
    }

    @Test
    void buildsKeyframesFromExportTimes() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true);

        assertEquals("walk", clip.clipName);
        assertEquals(3, clip.frames.size);
        assertTrue(clip.looping);
        // Times: 0, 0.25, 0.5 -> last keyframe starts at 0.5, so the cycle is 0.5 s long.
        assertEquals(0f, clip.frames.get(0).startTime, 1e-6f);
        assertEquals(0.25f, clip.frames.get(1).startTime, 1e-6f);
        assertEquals(0.5f, clip.frames.get(2).startTime, 1e-6f);
        assertEquals(0.5f, clip.totalDuration, 1e-5f);
        // Durations bridge to the next keyframe; the last one wraps by 1/fps.
        assertEquals(0.25f, clip.frames.get(0).duration, 1e-5f);
        assertEquals(0.25f, clip.frames.get(1).duration, 1e-5f);
        assertEquals(0.25f, clip.frames.get(2).duration, 1e-5f, "1/fps of the walk animation");
        assertEquals("t_walk_0", clip.frames.get(0).poseName);
        assertEquals("t_walk_2", clip.frames.get(2).poseName);
    }

    @Test
    void missingFrameTimesFallBackToFpsGrid() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "die", false);

        assertEquals(2, clip.frames.size);
        assertEquals(0.1f, clip.frames.get(1).startTime, 1e-5f); // fps 10
        assertEquals(0.1f, clip.totalDuration, 1e-5f);
        assertFalse(clip.looping);
        // Without frameTimes the last interval is one frame long, so the clip lasts 2 frames.
        assertEquals(0.2f, clip.frames.get(1).endTime(), 1e-5f);
    }

    @Test
    void loadAllBuildsEveryAnimationAndHonoursLoopingSet() {
        Map<String, MeshAnimationClip> clips =
            MeshAnimationClip.loadAllFromExport(sampleExport(), Set.of("walk"));

        assertEquals(2, clips.size());
        assertTrue(clips.get("walk").looping);
        assertFalse(clips.get("die").looping);
    }

    @Test
    void unknownAnimationIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> MeshAnimationClip.fromExport(sampleExport(), "nope", true));
    }

    @Test
    void easingIsAppliedToOrdinaryFrames() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true)
            .applyEasing(Interpolation.smooth, true);

        assertSame(Interpolation.smooth, clip.frames.get(0).interpolation);
        assertSame(Interpolation.smooth, clip.frames.get(2).interpolation);
        // Without overrideExisting a second call keeps the smoothed frames.
        clip.applyEasing(Interpolation.bounce, false);
        assertSame(Interpolation.smooth, clip.frames.get(0).interpolation);
    }

    // -----------------------------------------------------------------------
    // Sampling
    // -----------------------------------------------------------------------

    @Test
    void sampleBracketsPosesAndInterpolatesLinearly() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true);
        SampleResult out = new SampleResult();

        clip.sample(0f, out);
        assertEquals("t_walk_0", out.poseA);
        assertEquals("t_walk_1", out.poseB);
        assertEquals(0f, out.alpha, 1e-6f);

        clip.sample(0.125f, out); // halfway through the first interval
        assertEquals("t_walk_0", out.poseA);
        assertEquals("t_walk_1", out.poseB);
        assertEquals(0.5f, out.alpha, 1e-5f);

        clip.sample(0.25f, out); // exactly on the second keyframe
        assertEquals("t_walk_1", out.poseA, "a keyframe starts on its own pose at full weight");
        assertEquals(0f, out.alpha, 1e-6f);

        assertEquals(1, clip.indexOf(0.4f));
        assertEquals(1, clip.indexOf(0.49f), "the last interval runs until the final keyframe at 0.5");
        assertEquals(2, clip.indexOf(0.5f));
    }

    @Test
    void sampleWrapsAroundALoop() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true);
        SampleResult out = new SampleResult();

        clip.sample(clip.totalDuration + 0.125f, out);
        assertEquals("t_walk_0", out.poseA);
        assertEquals(0.5f, out.alpha, 1e-4f);

        // Negative times wrap as well instead of clamping: -0.125 -> 0.375 (second interval).
        clip.sample(-0.125f, out);
        assertEquals("t_walk_1", out.poseA, "time wraps into [0, totalDuration)");
        assertEquals(0.5f, out.alpha, 1e-4f);
    }

    @Test
    void nonLoopingClipHoldsTheLastPose() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "die", false);
        SampleResult out = new SampleResult();

        clip.sample(-1f, out);
        assertEquals("t_die_0", out.poseA);

        clip.sample(clip.totalDuration, out);
        assertEquals("t_die_1", out.poseA);
        assertEquals("t_die_1", clip.poseAt(clip.totalDuration * 10f, out));
    }

    @Test
    void stepFrameHoldsItsPoseAndSnapsAtTheNextKeyframe() {
        // step=true on frame 0: the pose must be held at weight 1 for the whole interval and the
        // next pose must appear at exactly its own start time (never blended).
        MeshAnimationClip clip = clipWithSteps(true, false, false);
        SampleResult out = new SampleResult();

        assertTrue(clip.frames.get(0).stepFrame);
        for (float t = 0f; t < 0.25f; t += 0.01f) {
            clip.sample(t, out);
            assertEquals(0f, out.alpha, 1e-6f, "held pose at t=" + t);
            assertEquals("t_walk_0", out.poseA);
        }
        clip.sample(0.25f, out);
        assertEquals("t_walk_1", out.poseA);
        assertEquals(0f, out.alpha, 1e-6f);
    }

    @Test
    void arrivingStepFrameSnapsInTheSecondHalfOfTheInterval() {
        MeshAnimationClip clip = clipWithSteps(false, true, false);
        SampleResult out = new SampleResult();

        clip.sample(0.10f, out); // first half: ordinary linear blend
        assertTrue(out.alpha < 0.5f, "linear before the snap point, was " + out.alpha);

        clip.sample(0.13f, out); // rawAlpha >= 0.5 -> snap to the step pose
        assertEquals(1f, out.alpha, 1e-6f);
        assertEquals("t_walk_1", out.poseB);
    }

    @Test
    void poseAtReportsTheDominantPose() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true);
        SampleResult scratch = new SampleResult();

        assertEquals("t_walk_0", clip.poseAt(0.1f, scratch));
        assertEquals("t_walk_1", clip.poseAt(0.2f, scratch));
        assertEquals("t_walk_0", clip.loopPose());
    }

    @Test
    void sampleReusesTheResultInstance() {
        MeshAnimationClip clip = MeshAnimationClip.fromExport(sampleExport(), "walk", true);
        SampleResult out = new SampleResult();

        assertSame(out, clip.sample(0.1f, out));
        assertSame(out, clip.sample(0.2f, out), "no result is ever allocated per sample");
        out.reset();
        assertNull(out.poseA);
        assertEquals(-1, out.frameIndexA);
        assertThrows(IllegalArgumentException.class, () -> clip.sample(0f, null));
    }
}
