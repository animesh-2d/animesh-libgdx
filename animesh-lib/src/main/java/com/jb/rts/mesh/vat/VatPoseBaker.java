package com.jb.rts.mesh.vat;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;

import java.util.List;

/**
 * Bakes mesheditor <em>export poses</em> into the GPU-skinned VAT representation used by
 * {@link VatInstancedMesh}: one {@link VatMeshData} (static bind pose + bone influences) and one
 * {@link VatAnimationData} (bone matrices of every frame, uploaded as a single {@code RGBA32F}
 * texture).
 *
 * <h3>Why this class exists</h3>
 * {@link com.jb.rts.mesh.blend.MeshExport} stores per-frame vertex <em>snapshots</em> (offsets from
 * the shared rest pose) but deliberately drops the bones — they are irrelevant to the CPU blend
 * renderer. VAT, however, skins with per-bone 2D affine matrices, so a snapshot has to be
 * expressed in bone space before the GPU can draw it.
 *
 * <p>The baker therefore assigns <b>one translation bone per expanded vertex</b>: bone {@code v}
 * of frame {@code f} is the matrix {@code T(pose_offset(v, f))}. Skinning the bind-pose vertex
 * {@code p} with weight 1 gives
 * <pre>
 *   M(v, f) · (p, 1) = p + pose_offset(v, f) = posed_vertex(v, f)
 * </pre>
 * so the shader reconstructs every exported frame exactly. Because the shader also {@code mix()}es
 * the two neighbouring frames' matrices, a fractional frame index reproduces the CPU blend
 * renderer's {@code defaultPositions + mix(offset0, offset1, t)} bit-for-bit (a translation is
 * linear, so mixing matrices and mixing offsets is the same operation).
 *
 * <h3>Cost</h3>
 * Bone rows = expanded vertex count (one triangle corner each, see {@link VatMeshData}) and the
 * matrix texture uses {@code vertexCount * frameCount * 64 bytes}. That is cheap for the editor's
 * meshes (hundreds of vertices) and is what makes an unmodified export file playable through the
 * VAT pipeline. For large meshes export a real bone skinning texture with the mesheditor's
 * {@code VatExporter} instead — one bone row per bone is orders of magnitude smaller.
 *
 * <h3>Limitations (inherited from the export format)</h3>
 * <ul>
 *   <li><b>Colours are per vertex.</b> VAT holds one colour per vertex in the static buffer, so
 *       the bind pose's colour indexes are baked there. Per-frame colour overrides of the export
 *       are baked into a separate {@code RGBA8} colour strip
 *       ({@link VatFrameColorTexture}) that the shader mixes between frames, which restores the
 *       CPU blend renderer's colour blending exactly.</li>
 *   <li><b>{@code z} is draw order.</b> The 2D shader passes {@code a_position.z} through
 *       unchanged, so a per-frame {@code z} offset is stored in the matrix but has no effect.</li>
 *   <li>The animation's {@code fps} is metadata only: it feeds
 *       {@link VatAnimationData#frameAt(float)} / {@link VatAnimationData#duration()}. Renderers
 *       that drive their own timing (like {@code MeshPlayer}) write the fractional frame index
 *       directly.</li>
 * </ul>
 */
public final class VatPoseBaker {

    /** Frame rate used by {@link #bake(String, List, ColorPalette)}. */
    public static final float DEFAULT_FPS = 30f;

    private VatPoseBaker() {}

    /** A baked static bind-pose mesh plus the animation sampled by the VAT renderer. */
    public record Result(VatMeshData mesh, VatAnimationData animation) {}

    /** Bakes with {@link #DEFAULT_FPS} (clip timing is not used by the VAT shader). */
    public static Result bake(String animationName, List<BlendableMesh> poses,
                              ColorPalette palette) {
        return bake(animationName, poses, palette, DEFAULT_FPS);
    }

    /**
     * Bakes one animation's poses into VAT data.
     *
     * @param animationName name recorded in {@link VatAnimationData} (for logs / tooling)
     * @param poses         the animation's frames, in playback order; every frame must share the
     *                      topology and the {@code defaultPositions} of the first one
     * @param palette       colour palette the poses' colour indexes refer to (may be
     *                      {@code null}, vertices then become white)
     * @param fps           nominal frame rate for {@link VatAnimationData}, must be {@code > 0}
     * @return the static mesh data and the baked animation
     * @throws IllegalArgumentException when the poses are missing, empty or disagree on topology
     */
    public static Result bake(String animationName, List<BlendableMesh> poses,
                              ColorPalette palette, float fps) {
        return bake(animationName, poses, palette, fps, null);
    }

    /**
     * Bakes one animation's poses into VAT data, optionally marking attachment
     * vertices so the runtime snaps their frame index instead of blending them.
     *
     * @param stepVertices one flag per expanded vertex ({@code null} for none)
     */
    public static Result bake(String animationName, List<BlendableMesh> poses,
                              ColorPalette palette, float fps, boolean[] stepVertices) {
        return bake(animationName, poses, palette, fps, stepVertices, null);
    }

    /**
     * Same as {@link #bake(String, List, ColorPalette, float, boolean[])}, plus
     * per-frame attachment step flags (a flag marks a frame whose attachment
     * state differs from the previous one; the shader snaps there).
     */
    public static Result bake(String animationName, List<BlendableMesh> poses,
                              ColorPalette palette, float fps, boolean[] stepVertices,
                              boolean[] stepFrames) {
        return bake(animationName, poses, palette, fps, stepVertices, stepFrames, null);
    }

    /**
     * Full form: also carries per-vertex texture coordinates into the static mesh data
     * ({@code 2} floats per expanded vertex, see {@link VatMeshData#uvs()}). {@code uvs} may be
     * {@code null} or empty for meshes drawn with their palette colours.
     */
    public static Result bake(String animationName, List<BlendableMesh> poses,
                              ColorPalette palette, float fps, boolean[] stepVertices,
                              boolean[] stepFrames, float[] uvs) {
        if (poses == null || poses.isEmpty()) {
            throw new IllegalArgumentException(
                "At least one pose is required to bake a VAT animation.");
        }

        BlendableMesh bind = poses.get(0);
        int vertexCount = bind.vertexCount();
        if (vertexCount == 0) {
            throw new IllegalArgumentException("Cannot bake an empty pose into VAT data.");
        }
        // VatMeshData expects an implicit triangle list: 3t, 3t+1, 3t+2.
        if (vertexCount % 3 != 0) {
            throw new IllegalArgumentException(
                "VAT needs an expanded triangle list (vertexCount % 3 == 0), but the poses have "
                    + vertexCount + " vertices.");
        }
        for (int i = 1; i < poses.size(); i++) {
            BlendableMesh pose = poses.get(i);
            if (pose == null || !bind.sameTopology(pose)) {
                throw new IllegalArgumentException(
                    "Pose " + i + " does not share the bind pose's topology ("
                        + vertexCount + " vertices / " + bind.triangleCount() + " triangles).");
            }
        }

        float[] positions = new float[vertexCount * BlendableMesh.POSITION_FLOATS];
        System.arraycopy(bind.defaultPositions, 0, positions, 0,
            Math.min(bind.defaultPositions.length, positions.length));

        float[] colors = VatPalette.resolve(bind, palette);

        // One translation bone per expanded vertex, weight 1 (the other two influences stay at 0
        // and are skipped by the shader).
        final int influences = VatFormat.INFLUENCES;
        int[] boneIds = new int[vertexCount * influences];
        float[] weights = new float[vertexCount * influences];
        String[] boneNames = new String[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            int base = v * influences;
            for (int k = 0; k < influences; k++) {
                boneIds[base + k] = v;
            }
            weights[base] = 1f;
            boneNames[v] = "vertex" + v;
        }

        int frameCount = poses.size();
        float[] matrices = bakeMatrices(poses, vertexCount, frameCount);

        // Per-frame colour overrides (fire flicker, flashes, …). Null when every frame uses the
        // bind pose's colours, which keeps the GPU cost at zero for ordinary meshes. The indexes
        // themselves are kept as well, so a palette edit can rebuild the strip without a rebake
        // (see VatInstancedMesh#refreshPalette).
        int[] frameColorIndices = VatPalette.frameColorIndices(poses);
        int[] frameColors = frameColorIndices != null
            ? VatPalette.resolveFrameColors(frameColorIndices, vertexCount, frameCount, palette)
            : null;

        VatMeshData meshData = new VatMeshData(positions, colors, boneIds, weights, null, boneNames,
            stepVertices, uvs != null && uvs.length > 0 ? uvs : null, bind.colorIndices);
        VatAnimationData animationData = new VatAnimationData(
            animationName, vertexCount, frameCount, fps, false, null, matrices,
            vertexCount, frameColors, stepFrames, frameColorIndices);
        return new Result(meshData, animationData);
    }

    // -----------------------------------------------------------------------
    // Baking helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the bone-matrix array: for every vertex row and frame a 2D translation matrix
     * {@code T(dx, dy, dz)} in column-major order (see {@link VatFormat}).
     */
    private static float[] bakeMatrices(List<BlendableMesh> poses, int vertexCount,
                                        int frameCount) {
        float[] matrices = new float[VatFormat.matrixFloatCount(vertexCount, frameCount)];
        for (int frame = 0; frame < frameCount; frame++) {
            float[] offsets = poses.get(frame).positions;
            for (int v = 0; v < vertexCount; v++) {
                int o = VatFormat.matrixFloatOffset(v, frame, frameCount);
                int i3 = v * BlendableMesh.POSITION_FLOATS;
                // Column-major identity: m00 (0), m11 (5), m22 (10), m33 (15).
                matrices[o]      = 1f;
                matrices[o + 5]  = 1f;
                matrices[o + 10] = 1f;
                matrices[o + 15] = 1f;
                // Column 3 holds the translation (m03, m13, m23).
                matrices[o + 12] = offsets[i3];
                matrices[o + 13] = offsets[i3 + 1];
                matrices[o + 14] = offsets[i3 + 2];
            }
        }
        return matrices;
    }
}
