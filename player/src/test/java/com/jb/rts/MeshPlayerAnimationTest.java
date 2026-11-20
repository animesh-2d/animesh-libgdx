package com.jb.rts;

import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkinClip;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatSkinBaker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link MeshPlayer#frameIndex(VatAnimationData, float)}: the player must map its
 * wall-clock time onto the animation's <b>own</b> timeline (the export's {@code frameTimes}), so a
 * clip plays as fast as it does in the mesheditor. A hard-coded cycle length used to stretch every
 * animation to the same number of seconds (the dino's 1&nbsp;s {@code walk} took 4&nbsp;s).
 */
class MeshPlayerAnimationTest {

    private static final float EPS = 1e-4f;

    /** The shipped dino export, relative to the {@code core} module (Gradle's test directory). */
    private static final Path DINO_EXPORT =
        Path.of("..", "test-assets", "meshes", "dino_longneck-export.json");

    /** Three frames, one second long: 0 s, 0.5 s, 1 s. */
    private static VatAnimationData oneSecondClip(boolean looping) {
        return new VatAnimationData("walk", 2, 3, 2f, looping,
            new float[]{0f, 0.5f, 1f}, new float[2 * 3 * 16]);
    }

    @Test
    void cycleLengthIsTheClipsOwnDuration() {
        VatAnimationData anim = oneSecondClip(true);
        assertEquals(1f, anim.duration(), EPS);

        // Half a second in: halfway between frame 0 and frame 1.
        assertEquals(0.5f, MeshPlayer.frameIndex(anim, 0.25f), EPS);
        // One second in: the clip restarts (not after 4 s, as the old fixed cycle did).
        assertEquals(0f, MeshPlayer.frameIndex(anim, 1f), EPS);
        assertEquals(0.5f, MeshPlayer.frameIndex(anim, 1.25f), EPS, "2nd cycle, same phase");
        assertEquals(0.5f, MeshPlayer.frameIndex(anim, 425.25f), EPS, "many cycles later");
    }

    @Test
    void keyframeTimesWinOverTheFrameCount() {
        // Frames 0 and 1 are 0.1 s apart, frame 2 comes a full second later — the mesheditor's
        // "die" clip has exactly this shape (a fast fall, then a long hold).
        VatAnimationData anim = new VatAnimationData("die", 1, 3, 2f, false,
            new float[]{0f, 0.1f, 1f}, new float[1 * 3 * 16]);

        assertEquals(1f, MeshPlayer.frameIndex(anim, 0.1f), EPS, "frame 1 at its own keyframe");
        assertEquals(1.5f, MeshPlayer.frameIndex(anim, 0.55f), EPS,
            "halfway through the long hold, not evenly spread over the frame count");
        // Spreading the frames evenly over the cycle would already be at frame 2 here.
        assertTrue(MeshPlayer.frameIndex(anim, 0.55f) < 2f);
    }

    @Test
    void lastFrameKeepsFullWeightAtTheLoopPoint() {
        VatAnimationData anim = oneSecondClip(true);
        assertEquals(2f, MeshPlayer.frameIndex(anim, 0.9999f), 2e-3f,
            "last frame is shown, never blended back to frame 0");
    }

    @Test
    void singleFrameClipStaysOnFrameZero() {
        // The synthetic "rest" animation has one keyframe at t = 0, i.e. duration() == 0.
        VatAnimationData rest = new VatAnimationData("rest", 3, 1, 1f, false,
            new float[]{0f}, new float[3 * 16]);
        assertEquals(0f, rest.duration(), EPS);
        assertEquals(0f, MeshPlayer.frameIndex(rest, 0f), EPS);
        assertEquals(0f, MeshPlayer.frameIndex(rest, 3.7f), EPS, "falls back to the fixed cycle");
    }

    @Test
    void uniformClipWithoutKeyframeTimesUsesItsFps() {
        VatAnimationData anim = VatAnimationData.uniform("idle", 1, 10, 20f, true, new float[160]);
        assertEquals(0.45f, anim.duration(), EPS);

        assertEquals(2f, MeshPlayer.frameIndex(anim, 0.1f), EPS);
        assertEquals(2f, MeshPlayer.frameIndex(anim, 0.55f), EPS, "wraps after 0.45 s");
    }

    @Test
    void negativeTimeIsTreatedAsTheClipStart() {
        assertEquals(0f, MeshPlayer.frameIndex(oneSecondClip(true), -2f), EPS);
    }

    /**
     * End-to-end regression test with the export the bug report was filed for: load the shipped
     * dino export, bake its clips the way {@code MeshPlayer} does and check that every clip keeps
     * the length the mesheditor saved (walk 1 s, die 2 s) instead of being stretched to 4 s.
     */
    @Test
    void realDinoExportPlaysAtTheAuthoredSpeed() throws Exception {
        assumeTrue(Files.isRegularFile(DINO_EXPORT),
            "asset not available: " + DINO_EXPORT.toAbsolutePath());

        MeshExport export = MeshExportLoader.loadFromString(Files.readString(DINO_EXPORT));
        assumeTrue(export.skin != null && !export.skinClips.isEmpty(), "export has no skin");

        VatAnimationData walkAnim = VatSkinBaker.animation("walk", export.skin,
            clip(export, "walk"));
        VatAnimationData dieAnim = VatSkinBaker.animation("die", export.skin,
            clip(export, "die"));

        // Authored lengths, straight from assets/meshes/model/dino_longneck.json
        // (walk: 0…1000 ms, die: 0…2000 ms).
        assertEquals(1f, walkAnim.duration(), 1e-3f);
        assertEquals(2f, dieAnim.duration(), 1e-3f);

        // 31 frames over 1 s -> around frame 15 after half a second, and the loop restarts
        // after 1 s instead of 4 s.
        assertEquals(15f, MeshPlayer.frameIndex(walkAnim, 0.5f), 0.5f);
        assertEquals(MeshPlayer.frameIndex(walkAnim, 0.25f),
            MeshPlayer.frameIndex(walkAnim, 1.25f), 1e-3f, "cycle length is 1 s, not 4 s");

        // "die" is 19 frames in the first second (subdivided by the exporter) plus a 1 s hold on
        // the final pose; the hold must still take a full second of playback.
        assertEquals(4.5f, MeshPlayer.frameIndex(dieAnim, 0.25f), 0.5f);
        assertTrue(MeshPlayer.frameIndex(dieAnim, 1.5f) >= 18f,
            "the final pose is held for the second half of the clip");
    }

    /** Returns the baked clip called {@code name} (the same lookup {@code MeshPlayer} uses). */
    private static MeshSkinClip clip(MeshExport export, String name) {
        for (MeshSkinClip clip : export.skinClips) {
            if (clip.name.equals(name)) return clip;
        }
        throw new IllegalArgumentException("no clip '" + name + "'");
    }
}
