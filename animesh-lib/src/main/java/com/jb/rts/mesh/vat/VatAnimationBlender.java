package com.jb.rts.mesh.vat;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;

/**
 * Blends <b>two baked animations</b> of one mesh into a third {@link VatAnimationData} — the
 * runtime counterpart of a locomotion blend (e.g. <i>walk</i> at weight 0 … <i>run</i> at
 * weight 1).
 *
 * <p>The result is an ordinary VAT animation: it lives in the same bone-matrix texture layout,
 * so {@link VatInstancedMesh} draws it without shader changes. That also means a blended clip can
 * be <b>re-uploaded in place</b> when only the weight changes
 * ({@link VatInstancedMesh#updateAnimation}) — no mesh, VBO or shader is rebuilt.</p>
 *
 * <h3>How the clips are combined</h3>
 * <ul>
 *   <li><b>Same phase, not the same frame number.</b> Both clips are sampled at the same
 *       normalised position of their cycle ({@code p = i / (frames - 1)}), so a 1&nbsp;s walk and a
 *       0.8&nbsp;s run stay in step instead of drifting apart.</li>
 *   <li><b>Frame count</b> is the larger of the two clips: the finer clip keeps its resolution and
 *       the coarser one is resampled onto it (nearest normalised keyframe; the GPU still
 *       interpolates between the surrounding output frames).</li>
 *   <li><b>Bone matrices</b> are blended by decomposing translation, rotation and scale, slerping
 *       the rotation (shortest path) and lerping the rest. A bone that is collapsed to scale 0 in
 *       either clip (a hidden attachment) falls back to a plain element-wise lerp, so a zero
 *       matrix cannot produce a NaN orientation.</li>
 *   <li><b>Per-frame colours</b> ({@link VatAnimationData#hasFrameColors()}) are lerped per
 *       channel when <b>both</b> clips carry a strip for the same vertex count; a clip without
 *       one keeps the static {@code a_color} colours, which cannot be blended on the CPU.</li>
 *   <li><b>Attachment step frames</b> are OR-ed: a frame that snapped in either clip keeps
 *       snapping, so an attachment never grows or shrinks smoothly.</li>
 * </ul>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Both clips must belong to the same mesh ({@code boneCount} must match) — otherwise an
 *       {@link IllegalArgumentException} is thrown.</li>
 *   <li>The blended clip uses a <b>uniform</b> frame timeline spanning the blended duration
 *       ({@code lerp(from.duration(), to.duration(), weight)}). Uneven authored keyframe timing
 *       (a long hold at the end of a clip) is therefore normalised — fine for locomotion blends,
 *       not intended as a general animation-retargeting tool.</li>
 *   <li>Only two sources can be mixed; blend a third clip into the result by blending that result
 *       again.</li>
 * </ul>
 *
 * <p>The class is stateless and GL-free: it runs headless, so it is covered by unit tests.</p>
 */
public final class VatAnimationBlender {

    /** Scratch objects — a blender instance is never shared between threads. */
    private final Matrix4 matrixA = new Matrix4();
    private final Matrix4 matrixB = new Matrix4();
    private final Matrix4 matrixOut = new Matrix4();
    private final Quaternion rotationA = new Quaternion();
    private final Quaternion rotationB = new Quaternion();
    private final Quaternion rotationOut = new Quaternion();
    private final Vector3 translationA = new Vector3();
    private final Vector3 translationB = new Vector3();
    private final Vector3 translationOut = new Vector3();
    private final Vector3 scaleA = new Vector3();
    private final Vector3 scaleB = new Vector3();
    private final Vector3 scaleOut = new Vector3();

    /**
     * Blends {@code from} (weight 0) and {@code to} (weight 1).
     *
     * <p>The result always has the frame count of the longer clip and the
     * {@code from.looping() || to.looping()} flag, even at weight 0 and 1. That keeps its texture
     * dimensions stable for one pair, which is what lets a running batch swap between weights in
     * place ({@link VatInstancedMesh#updateAnimation}).</p>
     *
     * @param from   clip at weight 0
     * @param to     clip at weight 1
     * @param weight blend weight, clamped to {@code 0..1}
     * @return a new animation data set (never the source instances)
     * @throws IllegalArgumentException when a clip is {@code null} or the bone counts differ
     */
    public static VatAnimationData blend(VatAnimationData from, VatAnimationData to, float weight) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both animations must be non-null.");
        }
        if (from.boneCount() != to.boneCount()) {
            throw new IllegalArgumentException("Cannot blend '" + from.name() + "' (" + from.boneCount()
                + " bones) with '" + to.name() + "' (" + to.boneCount()
                + " bones): the bone counts differ.");
        }
        return new VatAnimationBlender().mix(from, to, MathUtils.clamp(weight, 0f, 1f));
    }


    /** Blends the two clips with the scratch objects of this instance. */
    private VatAnimationData mix(VatAnimationData from, VatAnimationData to, float weight) {
        final int bones = from.boneCount();
        final int frames = Math.max(from.frameCount(), to.frameCount());

        float duration = blendDuration(from, to, weight);
        float[] frameTimes = uniformFrameTimes(frames, duration);

        float[] matrices = new float[VatFormat.matrixFloatCount(bones, frames)];
        for (int frame = 0; frame < frames; frame++) {
            float phase = frames <= 1 ? 0f : frame / (float) (frames - 1);
            int frameA = sampleFrame(from, phase);
            int frameB = sampleFrame(to, phase);
            for (int bone = 0; bone < bones; bone++) {
                int offset = VatFormat.matrixFloatOffset(bone, frame, frames);
                System.arraycopy(from.boneMatrices(),
                    VatFormat.matrixFloatOffset(bone, frameA, from.frameCount()),
                    matrixA.val, 0, VatFormat.FLOATS_PER_MATRIX);
                System.arraycopy(to.boneMatrices(),
                    VatFormat.matrixFloatOffset(bone, frameB, to.frameCount()),
                    matrixB.val, 0, VatFormat.FLOATS_PER_MATRIX);
                blendMatrix(matrixA, matrixB, weight, matrices, offset);
            }
        }

        int[] frameColors = blendFrameColors(from, to, frames, weight);
        boolean[] stepFrames = blendStepFrames(from, to, frames);

        return new VatAnimationData(from.name() + "+" + to.name(), bones, frames,
            duration > 1e-6f ? (frames > 1 ? (frames - 1) / duration : 1f) : 1f,
            from.looping() || to.looping(), frameTimes, matrices,
            frameColors != null ? from.colorVertexCount() : 0, frameColors, stepFrames);
    }

    // ------------------------------------------------------------------ timing

    /**
     * Blended cycle length: the weighted mean of both durations. A clip without usable timing
     * (a single-frame pose, or a legacy bake) does not drag the result to zero — the other clip
     * wins in that case.
     */
    private static float blendDuration(VatAnimationData from, VatAnimationData to, float weight) {
        float a = from.duration();
        float b = to.duration();
        if (!(a > 1e-6f)) return b > 1e-6f ? b : 0f;
        if (!(b > 1e-6f)) return a;
        return MathUtils.lerp(a, b, weight);
    }

    /** Uniform keyframe times over {@code duration}, or {@code null} when it has no length. */
    private static float[] uniformFrameTimes(int frames, float duration) {
        if (!(duration > 1e-6f)) return null;
        float[] times = new float[frames];
        for (int i = 0; i < frames; i++) {
            times[i] = frames <= 1 ? 0f : duration * i / (frames - 1f);
        }
        return times;
    }

    /** Frame index of one clip at the normalised cycle position {@code phase} (0..1). */
    private static int sampleFrame(VatAnimationData animation, float phase) {
        if (animation.frameCount() <= 1) return 0;
        float frame = animation.frameAt(phase * animation.duration());
        return MathUtils.clamp(Math.round(frame), 0, animation.frameCount() - 1);
    }

    // ------------------------------------------------------------------ matrices

    /**
     * Writes {@code lerp(a, b, t)} into {@code out[offset]} using translation/rotation/scale
     * decomposition; degenerate (zero-scale) matrices are lerped element-wise.
     */
    private void blendMatrix(Matrix4 a, Matrix4 b, float t, float[] out, int offset) {
        a.getScale(scaleA);
        b.getScale(scaleB);
        if (!isUsable(scaleA) || !isUsable(scaleB)) {
            lerpElements(a, b, t, out, offset);
            return;
        }
        a.getTranslation(translationA);
        b.getTranslation(translationB);
        a.getRotation(rotationA, true);
        b.getRotation(rotationB, true);

        translationOut.set(translationA).lerp(translationB, t);
        scaleOut.set(scaleA).lerp(scaleB, t);
        rotationOut.set(rotationA).slerp(rotationB, t);
        if (!isUsable(rotationOut)) {
            // Opposite rotations at exactly 50 % can cancel out; keep the linear blend instead.
            lerpElements(a, b, t, out, offset);
            return;
        }
        matrixOut.set(translationOut, rotationOut, scaleOut);
        System.arraycopy(matrixOut.val, 0, out, offset, VatFormat.FLOATS_PER_MATRIX);
    }

    /** Element-wise matrix lerp (the fallback for collapsed / degenerate bones). */
    private static void lerpElements(Matrix4 a, Matrix4 b, float t, float[] out, int offset) {
        for (int i = 0; i < VatFormat.FLOATS_PER_MATRIX; i++) {
            out[offset + i] = MathUtils.lerp(a.val[i], b.val[i], t);
        }
    }

    /** A scale vector with all three components clearly above zero (an invertible matrix). */
    private static boolean isUsable(Vector3 scale) {
        return scale.x > 1e-6f && scale.y > 1e-6f && scale.z > 1e-6f;
    }

    /** A normalised quaternion (a zero-length one would produce a singular matrix). */
    private static boolean isUsable(Quaternion rotation) {
        float lengthSq = rotation.x * rotation.x + rotation.y * rotation.y
            + rotation.z * rotation.z + rotation.w * rotation.w;
        return lengthSq > 1e-6f;
    }

    // ------------------------------------------------------------------ colours

    /**
     * Lerps the two colour strips ({@code 0xAABBGGRR} texels) when both clips carry one for the
     * same number of vertices, otherwise {@code null} (keep the static vertex colours).
     */
    private static int[] blendFrameColors(VatAnimationData from, VatAnimationData to,
                                          int frames, float weight) {
        if (!from.hasFrameColors() || !to.hasFrameColors()) return null;
        int vertexCount = from.colorVertexCount();
        if (vertexCount != to.colorVertexCount()) return null;

        int[] colors = new int[VatFormat.colorTexelCount(vertexCount, frames)];
        for (int frame = 0; frame < frames; frame++) {
            float phase = frames <= 1 ? 0f : frame / (float) (frames - 1);
            int frameA = sampleFrame(from, phase);
            int frameB = sampleFrame(to, phase);
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int packedA = from.frameColors()[VatFormat.frameColorTexel(vertexCount, frameA, vertex)];
                int packedB = to.frameColors()[VatFormat.frameColorTexel(vertexCount, frameB, vertex)];
                colors[VatFormat.frameColorTexel(vertexCount, frame, vertex)] =
                    mixPacked(packedA, packedB, weight);
            }
        }
        return colors;
    }

    /** Per-channel lerp of two {@code 0xAABBGGRR} texels. */
    private static int mixPacked(int from, int to, float t) {
        int r = Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * t));
        int g = Math.round(((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t));
        int b = Math.round(((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t));
        int a = Math.round(((from >> 24) & 0xFF) + ((((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t));
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ------------------------------------------------------------------ step frames

    /** ORs the attachment step flags of both clips; {@code null} when neither has any. */
    private static boolean[] blendStepFrames(VatAnimationData from, VatAnimationData to, int frames) {
        if (!from.hasStepFrames() && !to.hasStepFrames()) return null;
        boolean[] steps = new boolean[frames];
        boolean any = false;
        for (int frame = 0; frame < frames; frame++) {
            float phase = frames <= 1 ? 0f : frame / (float) (frames - 1);
            boolean step = from.isStepFrame(sampleFrame(from, phase))
                || to.isStepFrame(sampleFrame(to, phase));
            steps[frame] = step;
            any |= step;
        }
        return any ? steps : null;
    }
}
