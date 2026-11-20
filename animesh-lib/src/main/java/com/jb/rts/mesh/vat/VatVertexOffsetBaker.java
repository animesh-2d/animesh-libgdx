package com.jb.rts.mesh.vat;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;

import java.util.Arrays;
import java.util.List;

/**
 * Restores <b>hand-animated vertices</b> that a bone-only skin cannot reproduce.
 *
 * <h3>Why this class exists</h3>
 * The mesheditor stores every animation frame twice:
 * <ul>
 *   <li>as <b>bone matrices</b> ({@code animations[].frames[].boneMatrices}) — enough for pure
 *       pose (bone) animation, and</li>
 *   <li>as <b>per-vertex offsets</b> ({@code animations[].frames[].offsets}) — the absolute pose
 *       of every vertex, i.e. bones <em>plus</em> everything the user dragged directly with the
 *       vertex tool of the animation panel.</li>
 * </ul>
 * {@link VatSkinBaker} only consumes the matrices, so a vertex that was moved by hand (or that has
 * no bone weight at all) stayed at its bind-pose position at runtime — the frames' {@code offsets}
 * were silently dropped. This baker closes that gap <b>without changing the export format</b>:
 * for every expanded vertex whose authored position differs from what the skin produces, it
 * appends one synthetic matrix row
 * <pre>
 *   A(v, f) = T(residual(v, f)) · Σ_k w_k · M(bone_k, f)
 *   residual(v, f) = posed(v, f) − Σ_k w_k · M(bone_k, f) · bind(v)
 * </pre>
 * and rebinds that vertex to the new row with weight&nbsp;1. The existing shader then evaluates
 * {@code A · bind(v) = skinned(v, f) + residual(v, f) = posed(v, f)}, so every exported frame is
 * reproduced <b>exactly</b> — bones and hand edits alike — with the unchanged VAT pipeline
 * (no new texture, no shader change, no binary format change).
 *
 * <h3>Cost</h3>
 * Only vertices that are actually hand-animated get a row; a purely bone-driven clip adds
 * <b>no</b> row at all (the baker then returns {@code null}). Because a residual row is a
 * translation on top of the skin matrix, mixing two neighbouring frames reproduces the CPU blend
 * renderer's {@code mix(offset0, offset1, t)} for hand edits too.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Requires one pose per clip frame ({@code frames.size() == clip.frameCount}). The export
 *       loader merges zero-length keyframe intervals, which can leave fewer clip frames than
 *       poses; in that (rare) case the baker returns {@code null} instead of guessing an
 *       alignment.</li>
 *   <li>{@code z} is draw order only (the shader passes it through unchanged), so a residual z
 *       is not baked — exactly like the bone path.</li>
 * </ul>
 */
public final class VatVertexOffsetBaker {

    /**
     * Distance (in export units) below which a frame's vertex position counts as fully explained
     * by the skin. Keeps numeric noise of the editor's deformation maths from adding rows.
     */
    public static final float DEFAULT_EPSILON = 1e-3f;

    /** Name prefix of the synthetic rows, e.g. {@code <vertex:42>}. */
    static final String ROW_NAME_PREFIX = "<vertex:";

    /**
     * A patched skin + clip pair.
     *
     * @param skin        the skin with {@code rowCount} extra rows appended
     * @param clip        the clip with matrices for every row of {@code skin}
     * @param rowCount    number of appended (hand-animated) rows
     * @param maxResidual largest per-vertex distance the bones alone would have missed
     */
    public record Result(MeshSkin skin, MeshSkinClip clip, int rowCount, float maxResidual) {}

    private VatVertexOffsetBaker() {}

    /** Bakes with {@link #DEFAULT_EPSILON}. */
    public static Result bake(BlendableMesh bindPose, MeshSkin skin, MeshSkinClip clip,
                              List<BlendableMesh> frames) {
        return bake(bindPose, skin, clip, frames, DEFAULT_EPSILON);
    }

    /**
     * Appends one matrix row per hand-animated vertex.
     *
     * @param bindPose the exported bind pose (the skin's bind positions)
     * @param skin     the export's skin (rows, influences, weights)
     * @param clip     the animation's baked bone matrices
     * @param frames   the animation's poses, one per clip frame (offsets over the bind pose)
     * @param epsilon  residual distance below which a vertex counts as bone-driven
     * @return the patched pair, or {@code null} when nothing needs patching (or the inputs cannot
     *         be aligned — see the class documentation)
     */
    public static Result bake(BlendableMesh bindPose, MeshSkin skin, MeshSkinClip clip,
                              List<BlendableMesh> frames, float epsilon) {
        if (bindPose == null || skin == null || clip == null || frames == null) return null;
        int vertexCount = skin.vertexCount();
        int frameCount = clip.frameCount;
        if (vertexCount == 0 || frameCount == 0) return null;
        if (frames.size() != frameCount) return null;
        if (bindPose.vertexCount() != vertexCount) return null;
        if (bindPose.defaultPositions == null
                || bindPose.defaultPositions.length < vertexCount * BlendableMesh.POSITION_FLOATS) {
            return null;
        }
        for (BlendableMesh frame : frames) {
            if (frame == null || frame.positions == null
                    || frame.positions.length < vertexCount * BlendableMesh.POSITION_FLOATS) {
                return null;
            }
        }

        // ---- pass 1: which vertices does the skin fail to explain? ----
        boolean[] animated = new boolean[vertexCount];
        float[] matrix = new float[MeshSkinClip.FLOATS_PER_MATRIX];
        float maxResidual = 0f;
        int rowCount = 0;
        for (int frame = 0; frame < frameCount; frame++) {
            float[] offsets = frames.get(frame).positions;
            for (int v = 0; v < vertexCount; v++) {
                int i3 = v * BlendableMesh.POSITION_FLOATS;
                float x = bindPose.defaultPositions[i3];
                float y = bindPose.defaultPositions[i3 + 1];
                accumulate(skin, clip, v, frame, matrix);
                float dx = x + offsets[i3] - applyX(matrix, x, y);
                float dy = y + offsets[i3 + 1] - applyY(matrix, x, y);
                float distance = (float) Math.hypot(dx, dy);
                if (distance > maxResidual) maxResidual = distance;
                if (distance > epsilon && !animated[v]) {
                    animated[v] = true;
                    rowCount++;
                }
            }
        }
        if (rowCount == 0) return null;

        // ---- pass 2: append the rows and bake their per-frame matrices ----
        MeshSkin patchedSkin = patchedSkin(skin, animated, rowCount);
        MeshSkinClip patchedClip = patchedClip(bindPose, skin, clip, frames, animated);
        return new Result(patchedSkin, patchedClip, rowCount, maxResidual);
    }

    // -----------------------------------------------------------------------
    // Skin / clip construction
    // -----------------------------------------------------------------------

    /** Copies the skin and rebinds every animated vertex to its own (appended) row. */
    private static MeshSkin patchedSkin(MeshSkin skin, boolean[] animated, int rowCount) {
        int vertexCount = skin.vertexCount();
        int oldBones = skin.boneCount();
        int newBones = oldBones + rowCount;

        String[] names = Arrays.copyOf(skin.boneNames, newBones);
        float[] centerX = Arrays.copyOf(skin.bindCenterX, newBones);
        float[] centerY = Arrays.copyOf(skin.bindCenterY, newBones);
        float[] dirX = Arrays.copyOf(skin.bindDirX, newBones);
        float[] dirY = Arrays.copyOf(skin.bindDirY, newBones);
        int[] boneIds = Arrays.copyOf(skin.boneIds, skin.boneIds.length);
        float[] weights = Arrays.copyOf(skin.weights, skin.weights.length);

        int row = oldBones;
        for (int v = 0; v < vertexCount; v++) {
            if (!animated[v]) continue;
            names[row] = ROW_NAME_PREFIX + v + ">";
            // The synthetic rows are translation-only: a unit direction keeps the bind table
            // consistent for tooling that still reconstructs matrices from centre/direction.
            dirX[row] = 1f;
            int base = v * skin.influences;
            for (int k = 0; k < skin.influences; k++) {
                boneIds[base + k] = row;
                weights[base + k] = k == 0 ? 1f : 0f;
            }
            row++;
        }
        return new MeshSkin(names, skin.influences, boneIds, weights, skin.sourceVertex,
            skin.bindPoseRow, centerX, centerY, dirX, dirY);
    }

    /**
     * Copies the clip into the wider layout ({@code oldBones + rowCount} rows) and fills the
     * synthetic rows with {@code T(residual) · Σ w M}.
     */
    private static MeshSkinClip patchedClip(BlendableMesh bindPose, MeshSkin skin, MeshSkinClip clip,
                                            List<BlendableMesh> frames, boolean[] animated) {
        final int stride = MeshSkinClip.FLOATS_PER_MATRIX;
        int vertexCount = skin.vertexCount();
        int frameCount = clip.frameCount;
        int oldBones = skin.boneCount();
        int newBones = oldBones + countTrue(animated);

        float[] matrices = new float[frameCount * newBones * stride];
        for (int frame = 0; frame < frameCount; frame++) {
            // Frame blocks are contiguous in both layouts: (frame * boneCount + bone) * 6.
            System.arraycopy(clip.matrices, frame * oldBones * stride,
                matrices, frame * newBones * stride, oldBones * stride);
        }

        float[] matrix = new float[stride];
        int row = oldBones;
        for (int v = 0; v < vertexCount; v++) {
            if (!animated[v]) continue;
            int i3 = v * BlendableMesh.POSITION_FLOATS;
            float x = bindPose.defaultPositions[i3];
            float y = bindPose.defaultPositions[i3 + 1];
            for (int frame = 0; frame < frameCount; frame++) {
                float[] offsets = frames.get(frame).positions;
                accumulate(skin, clip, v, frame, matrix);
                int o = (frame * newBones + row) * stride;
                matrices[o]     = matrix[0];
                matrices[o + 1] = matrix[1];
                matrices[o + 2] = matrix[2] + (x + offsets[i3] - applyX(matrix, x, y));
                matrices[o + 3] = matrix[3];
                matrices[o + 4] = matrix[4];
                matrices[o + 5] = matrix[5] + (y + offsets[i3 + 1] - applyY(matrix, x, y));
            }
            row++;
        }
        return new MeshSkinClip(clip.name, clip.looping, clip.fps, newBones,
            clip.frameTimes, matrices, clip.stepFrames);
    }

    // -----------------------------------------------------------------------
    // Maths helpers (identical to the VAT vertex shader's linear blend skinning)
    // -----------------------------------------------------------------------

    /**
     * Blends the matrices of one vertex's influences into
     * {@code out = [m00, m01, m03, m10, m11, m13]}.
     */
    private static void accumulate(MeshSkin skin, MeshSkinClip clip, int vertex, int frame,
                                   float[] out) {
        float m00 = 0f, m01 = 0f, m03 = 0f, m10 = 0f, m11 = 0f, m13 = 0f;
        int base = vertex * skin.influences;
        for (int k = 0; k < skin.influences; k++) {
            float w = skin.weights[base + k];
            if (w == 0f) continue;
            int o = clip.offset(skin.boneIds[base + k], frame);
            float[] m = clip.matrices;
            m00 += w * m[o];
            m01 += w * m[o + 1];
            m03 += w * m[o + 2];
            m10 += w * m[o + 3];
            m11 += w * m[o + 4];
            m13 += w * m[o + 5];
        }
        out[0] = m00;
        out[1] = m01;
        out[2] = m03;
        out[3] = m10;
        out[4] = m11;
        out[5] = m13;
    }

    /** {@code x'} of the affine matrix {@code [m00, m01, m03, m10, m11, m13]}. */
    private static float applyX(float[] m, float x, float y) {
        return m[0] * x + m[1] * y + m[2];
    }

    /** {@code y'} of the affine matrix {@code [m00, m01, m03, m10, m11, m13]}. */
    private static float applyY(float[] m, float x, float y) {
        return m[3] * x + m[4] * y + m[5];
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) count++;
        }
        return count;
    }
}
