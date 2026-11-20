package com.jb.rts.mesh.vat;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VatAnimationBlender} — the CPU blend of two VAT clips that the player uses for
 * a walk/run weight slider. Everything here is GL-free and runs headless.
 */
class VatAnimationBlenderTest {

    private static final float EPS = 1e-4f;

    // =======================================================================
    // Timing and resampling
    // =======================================================================

    @Test
    void blend_atHalfwayAveragesTheTranslations() {
        VatAnimationData walk = translated("walk", 2, 1f, 0f, 10f);
        VatAnimationData run = translated("run", 2, 1f, 100f, 110f);

        VatAnimationData blended = VatAnimationBlender.blend(walk, run, 0.5f);

        assertEquals(2, blended.frameCount());
        assertEquals(1, blended.boneCount());
        assertEquals(50f, x(blended, 0), EPS, "frame 0: (0 + 100) / 2");
        assertEquals(60f, x(blended, 1), EPS, "frame 1: (10 + 110) / 2");
    }

    @Test
    void blend_resamplesAShorterClipOntoTheLongerOne() {
        VatAnimationData shortClip = translated("short", 2, 1f, 0f, 10f);
        VatAnimationData longClip = translated("long", 5, 2f, 100f, 140f);

        VatAnimationData blended = VatAnimationBlender.blend(shortClip, longClip, 0.5f);

        assertEquals(5, blended.frameCount(), "the finer clip keeps its resolution");
        assertEquals(50f, x(blended, 0), EPS);
        assertEquals(75f, x(blended, 4), EPS, "both clips end at the same phase");
        for (int frame = 1; frame < blended.frameCount(); frame++) {
            assertTrue(x(blended, frame) > x(blended, frame - 1), "positions advance monotonically");
        }
    }

    @Test
    void blend_usesTheWeightedDurationAndAUniformTimeline() {
        VatAnimationData oneSecond = timed("one", 2, 1f, 0f);
        VatAnimationData twoSeconds = timed("two", 3, 2f, 100f);

        VatAnimationData blended = VatAnimationBlender.blend(oneSecond, twoSeconds, 0.5f);

        assertEquals(1.5f, blended.duration(), EPS, "lerp(1 s, 2 s, 0.5)");
        assertNotNull(blended.frameTimes());
        assertEquals(0f, blended.frameTimes()[0], EPS);
        assertEquals(0.75f, blended.frameTimes()[1], EPS, "uniform 1.5 s / 2 steps");
        assertEquals(1.5f, blended.frameTimes()[blended.frameCount() - 1], EPS);
    }

    @Test
    void blend_keepsTheSharedDimensionsAtTheExtremeWeights() {
        VatAnimationData from = translated("from", 2, 1f, 0f, 10f);
        VatAnimationData to = translated("to", 3, 1f, 20f, 30f);

        VatAnimationData start = VatAnimationBlender.blend(from, to, 0f);
        VatAnimationData end = VatAnimationBlender.blend(from, to, 5f);

        assertEquals(3, start.frameCount(), "the pair always keeps the longer clip's frame count");
        assertEquals(3, end.frameCount(), "weights are clamped, the dimensions never change");
        assertEquals(0f, x(start, 0), EPS, "weight 0 = the from clip's pose");
        assertEquals(10f, x(start, 2), EPS);
        assertEquals(20f, x(end, 0), EPS, "weight 1 = the to clip's pose");
        assertEquals(30f, x(end, 2), EPS);
        assertNotSame(from, start, "a blend always returns a new data set");
        assertNotSame(to, end);
    }

    @Test
    void blend_rejectsClipsWithDifferentBoneCounts() {
        VatAnimationData oneBone = translated("a", 2, 1f, 0f, 1f);
        VatAnimationData twoBones = new VatAnimationData("b", 2, 2, 1f, true, null,
            new float[VatFormat.matrixFloatCount(2, 2)]);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> VatAnimationBlender.blend(oneBone, twoBones, 0.5f));
        assertTrue(error.getMessage().contains("bone counts differ"), error.getMessage());
    }

    // =======================================================================
    // Matrices
    // =======================================================================

    @Test
    void blend_slerpsRotationsInsteadOfCollapsingThem() {
        VatAnimationData straight = rotated("straight", 0f);
        VatAnimationData turned = rotated("turned", 90f);

        VatAnimationData blended = VatAnimationBlender.blend(straight, turned, 0.5f);

        Matrix4 matrix = blended.boneMatrix(0, 0, new Matrix4());
        Vector3 xAxis = new Vector3(1f, 0f, 0f).mul(matrix);
        assertEquals((float) Math.cos(Math.toRadians(45.0)), xAxis.x, 1e-3f);
        assertEquals((float) Math.sin(Math.toRadians(45.0)), xAxis.y, 1e-3f);
        Quaternion rotation = matrix.getRotation(new Quaternion(), true);
        assertEquals((float) Math.cos(Math.toRadians(22.5)),
            Math.abs(rotation.w), 1e-3f, "45° about z → w = cos(22.5°)");
    }

    @Test
    void blend_survivesACollapsedAttachmentBone() {
        VatAnimationData visible = translated("visible", 1, 1f, 5f, 5f);
        // A hidden attachment is baked to a zero matrix — the blender must not emit NaN.
        float[] zero = new float[VatFormat.matrixFloatCount(1, 1)];
        VatAnimationData hidden = new VatAnimationData("hidden", 1, 1, 1f, true, null, zero);

        VatAnimationData blended = VatAnimationBlender.blend(visible, hidden, 0.5f);

        Matrix4 matrix = blended.boneMatrix(0, 0, new Matrix4());
        for (float value : matrix.val) {
            assertTrue(Float.isFinite(value), "a zero-scale bone must stay finite");
        }
        assertEquals(2.5f, matrix.val[Matrix4.M03], EPS, "element-wise lerp of the translation");
    }

    // =======================================================================
    // Colours and step frames
    // =======================================================================

    @Test
    void blend_mixesPerFrameColoursChannelWise() {
        final int vertices = 3;
        int red = pack(1f, 0f, 0f, 1f);
        int blue = pack(0f, 0f, 1f, 1f);
        VatAnimationData from = coloured("red", vertices, red);
        VatAnimationData to = coloured("blue", vertices, blue);

        VatAnimationData blended = VatAnimationBlender.blend(from, to, 0.5f);

        assertTrue(blended.hasFrameColors());
        assertEquals(vertices, blended.colorVertexCount());
        int packed = blended.frameColors()[0];
        assertEquals(128, packed & 0xFF, "red fades from 255 to 0");
        assertEquals(0, (packed >> 8) & 0xFF, "green stays 0");
        assertEquals(128, (packed >> 16) & 0xFF, "blue fades from 0 to 255");
    }

    @Test
    void blend_keepsStepFramesOfEitherClip() {
        float[] matrices = new float[VatFormat.matrixFloatCount(1, 3)];
        VatAnimationData from = new VatAnimationData("from", 1, 3, 2f, true,
            new float[]{0f, 0.5f, 1f}, matrices, 0, null, new boolean[]{false, true, false});
        VatAnimationData to = new VatAnimationData("to", 1, 3, 2f, true,
            new float[]{0f, 0.5f, 1f}, matrices);

        VatAnimationData blended = VatAnimationBlender.blend(from, to, 0.5f);

        assertTrue(blended.hasStepFrames());
        assertTrue(blended.isStepFrame(1));
        assertEquals(3, blended.stepFrames().length);
    }

    @Test
    void blend_withoutAnyColourStripHasNone() {
        VatAnimationData from = translated("from", 2, 1f, 0f, 1f);
        VatAnimationData to = translated("to", 2, 1f, 2f, 3f);

        VatAnimationData blended = VatAnimationBlender.blend(from, to, 0.5f);

        assertNull(blended.frameColors());
        assertEquals(0, blended.colorVertexCount());
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    /** Clip that only translates bone 0 along x, from {@code fromX} to {@code toX}. */
    private static VatAnimationData translated(String name, int frames, float duration,
                                               float fromX, float toX) {
        float[] matrices = new float[VatFormat.matrixFloatCount(1, frames)];
        Matrix4 matrix = new Matrix4();
        for (int frame = 0; frame < frames; frame++) {
            float t = frames <= 1 ? 0f : frame / (float) (frames - 1);
            matrix.idt().translate(fromX + (toX - fromX) * t, 0f, 0f);
            System.arraycopy(matrix.val, 0, matrices,
                VatFormat.matrixFloatOffset(0, frame, frames), VatFormat.FLOATS_PER_MATRIX);
        }
        return new VatAnimationData(name, 1, frames, frames > 1 ? (frames - 1) / duration : 1f,
            true, uniformTimes(frames, duration), matrices);
    }

    /** Clip whose matrices start at {@code startX} (used to give blend sources a fixed timing). */
    private static VatAnimationData timed(String name, int frames, float duration, float startX) {
        return translated(name, frames, duration, startX, startX + 100f);
    }

    /** Single-frame clip rotating bone 0 around z by {@code degrees}. */
    private static VatAnimationData rotated(String name, float degrees) {
        float[] matrices = new float[VatFormat.matrixFloatCount(1, 1)];
        Matrix4 matrix = new Matrix4().idt()
            .rotateRad(0f, 0f, 1f, (float) Math.toRadians(degrees));
        System.arraycopy(matrix.val, 0, matrices, 0, VatFormat.FLOATS_PER_MATRIX);
        return new VatAnimationData(name, 1, 1, 1f, true, null, matrices);
    }

    /** Two-frame clip with one constant colour texel per vertex. */
    private static VatAnimationData coloured(String name, int vertices, int packed) {
        int[] colors = new int[VatFormat.colorTexelCount(vertices, 2)];
        java.util.Arrays.fill(colors, packed);
        return new VatAnimationData(name, 1, 2, 1f, true, null,
            new float[VatFormat.matrixFloatCount(1, 2)], vertices, colors);
    }

    private static float[] uniformTimes(int frames, float duration) {
        float[] times = new float[frames];
        for (int i = 0; i < frames; i++) {
            times[i] = frames <= 1 ? 0f : duration * i / (frames - 1f);
        }
        return times;
    }

    /** X translation of bone 0 in one frame (column-major {@code M03}). */
    private static float x(VatAnimationData animation, int frame) {
        return animation.boneMatrix(0, frame, new Matrix4()).val[Matrix4.M03];
    }

    /** Packs RGBA floats into the {@code 0xAABBGGRR} layout of the colour strip. */
    private static int pack(float r, float g, float b, float a) {
        int ri = Math.round(r * 255f);
        int gi = Math.round(g * 255f);
        int bi = Math.round(b * 255f);
        int ai = Math.round(a * 255f);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }
}
