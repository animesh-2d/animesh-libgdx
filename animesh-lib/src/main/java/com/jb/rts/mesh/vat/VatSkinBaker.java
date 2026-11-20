package com.jb.rts.mesh.vat;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;

import java.util.List;

/**
 * Bakes real bone skinning data ({@link MeshSkin} + {@link MeshSkinClip}) into the GPU structures
 * of the VAT pipeline ({@link VatMeshData} + {@link VatAnimationData}).
 *
 * <p>Unlike {@link VatPoseBaker} — which has to invent one translation bone per vertex because
 * plain pose snapshots carry no bones — this class consumes the export's actual bone table:
 * <ul>
 *   <li><b>Static mesh</b>: bind-pose positions, per-corner colours and the loader-resolved
 *       top-3 influences (already normalized).</li>
 *   <li><b>Animation</b>: the clip's 2D affine matrices ({@code m00,m01,m03,m10,m11,m13}) are
 *       expanded into the 16-float column-major layout of {@link VatFormat} — a pure repack, no
 *       trigonometry. {@link MeshSkinClip} already stores identity for bones a frame does not
 *       touch, so the texture is dense.</li>
 * </ul>
 *
 * <p>Texture size is therefore {@code boneCount × frameCount} (e.g. 25 bones instead of 684
 * per-corner rows for the sample dino), which is the shader-optimal representation.
 */
public final class VatSkinBaker {

    /** A baked static mesh plus one baked clip. */
    public record Result(VatMeshData mesh, VatAnimationData animation) {}

    private VatSkinBaker() {}

    /** Bakes the static mesh and one clip. */
    public static Result bake(String animationName, BlendableMesh bindPose, MeshSkin skin,
                              MeshSkinClip clip, ColorPalette palette) {
        return bake(animationName, bindPose, skin, clip, palette, null);
    }

    /** Bakes the static mesh and one clip with a per-vertex attachment step mask. */
    public static Result bake(String animationName, BlendableMesh bindPose, MeshSkin skin,
                              MeshSkinClip clip, ColorPalette palette, boolean[] stepVertices) {
        return new Result(meshData(bindPose, skin, palette, stepVertices),
            animation(animationName, skin, clip));
    }

    /**
     * Builds the static half (bind pose + colours + influences).
     *
     * @throws IllegalArgumentException when the skin does not match the pose or this build's
     *                                  influence count
     */
    public static VatMeshData meshData(BlendableMesh bindPose, MeshSkin skin,
                                       ColorPalette palette) {
        return meshData(bindPose, skin, palette, null);
    }

    /**
     * Builds the static half with an optional per-vertex attachment step mask
     * (see {@link VatMeshData#hasStepVertices()}).
     */
    public static VatMeshData meshData(BlendableMesh bindPose, MeshSkin skin,
                                       ColorPalette palette, boolean[] stepVertices) {
        return meshData(bindPose, skin, palette, stepVertices, null);
    }

    /**
     * Builds the static half with an optional per-vertex attachment step mask and optional
     * per-vertex texture coordinates ({@code 2} floats per expanded vertex, see
     * {@link VatMeshData#uvs()}). {@code uvs} may be {@code null} (or empty) for meshes that
     * are drawn with their palette colours.
     */
    public static VatMeshData meshData(BlendableMesh bindPose, MeshSkin skin,
                                       ColorPalette palette, boolean[] stepVertices,
                                       float[] uvs) {
        if (bindPose == null || skin == null) {
            throw new IllegalArgumentException("bindPose and skin must not be null.");
        }
        if (skin.influences != VatFormat.INFLUENCES) {
            throw new IllegalArgumentException("Skin stores " + skin.influences
                + " influences per vertex, this build expects " + VatFormat.INFLUENCES + ".");
        }
        if (skin.vertexCount() != bindPose.vertexCount()) {
            throw new IllegalArgumentException("Skin describes " + skin.vertexCount()
                + " vertices but the bind pose has " + bindPose.vertexCount() + ".");
        }
        float[] positions = new float[bindPose.defaultPositions.length];
        System.arraycopy(bindPose.defaultPositions, 0, positions, 0, positions.length);
        float[] colors = VatPalette.resolve(bindPose, palette);
        // "no UV data" is an EMPTY array in MeshExport; VatMeshData treats null and empty alike.
        float[] resolvedUvs = uvs != null && uvs.length > 0 ? uvs : null;
        return new VatMeshData(positions, colors, skin.boneIds, skin.weights,
            skin.sourceVertex, skin.boneNames, stepVertices, resolvedUvs);
    }

    /** Expands one 2D clip into the 16-float texture layout of {@link VatFormat}. */
    public static VatAnimationData animation(String animationName, MeshSkin skin,
                                             MeshSkinClip clip) {
        return expand(animationName, skin, clip);
    }

    /**
     * Expands one 2D clip and attaches the export's <b>per-frame colour overrides</b>.
     *
     * <p>{@code frames} must be the animation's poses in playback order and must line up 1:1
     * with the clip's frames; otherwise the strip is skipped (the static bind-pose colours are
     * used, i.e. no colour blending). When no frame deviates from the bind pose's colours the
     * strip is {@code null} as well, so ordinary meshes pay nothing.
     *
     * @param frames  the animation frames the clip was baked from (may be {@code null})
     * @param palette palette the frames' colour indexes refer to (may be {@code null})
     */
    public static VatAnimationData animation(String animationName, MeshSkin skin,
                                             MeshSkinClip clip,
                                             List<BlendableMesh> frames,
                                             ColorPalette palette) {
        VatAnimationData baked = expand(animationName, skin, clip);
        if (frames == null || frames.isEmpty() || frames.size() != baked.frameCount()) {
            return baked;
        }
        int vertexCount = frames.get(0).vertexCount();
        int[] frameColors = VatPalette.resolveFrames(frames, palette);
        if (frameColors == null) {
            return baked;
        }
        return new VatAnimationData(baked.name(), baked.boneCount(), baked.frameCount(),
            baked.fps(), baked.looping(), baked.frameTimes(), baked.boneMatrices(),
            vertexCount, frameColors, baked.stepFrames());
    }

    /** Matrix repack of one clip (no colours). */
    private static VatAnimationData expand(String animationName, MeshSkin skin,
                                           MeshSkinClip clip) {
        if (skin == null || clip == null) {
            throw new IllegalArgumentException("skin and clip must not be null.");
        }
        int boneCount = skin.boneCount();
        if (clip.boneCount != boneCount) {
            throw new IllegalArgumentException("Clip '" + clip.name + "' baked " + clip.boneCount
                + " bones but the skin has " + boneCount + " rows.");
        }
        int frameCount = clip.frameCount;
        float[] matrices = new float[VatFormat.matrixFloatCount(boneCount, frameCount)];
        for (int frame = 0; frame < frameCount; frame++) {
            for (int bone = 0; bone < boneCount; bone++) {
                int src = clip.offset(bone, frame);
                int dst = VatFormat.matrixFloatOffset(bone, frame, frameCount);
                matrices[dst]      = clip.matrices[src];      // m00
                matrices[dst + 1]  = clip.matrices[src + 3];  // m10
                matrices[dst + 4]  = clip.matrices[src + 1];  // m01
                matrices[dst + 5]  = clip.matrices[src + 4];  // m11
                matrices[dst + 10] = 1f;                      // m22
                matrices[dst + 12] = clip.matrices[src + 2];  // m03
                matrices[dst + 13] = clip.matrices[src + 5];  // m13
                matrices[dst + 15] = 1f;                      // m33
            }
        }
        return new VatAnimationData(animationName, boneCount, frameCount, clip.fps,
            clip.looping, clip.frameTimes, matrices, 0, null, clip.stepFrames);
    }

    /** One-frame identity clip used for the bind ("rest") pose of a skinned mesh. */
    public static VatAnimationData restAnimation(MeshSkin skin, float fps) {
        int boneCount = skin.boneCount();
        float[] matrices = new float[VatFormat.matrixFloatCount(boneCount, 1)];
        for (int bone = 0; bone < boneCount; bone++) {
            int offset = VatFormat.matrixFloatOffset(bone, 0, 1);
            matrices[offset] = 1f;
            matrices[offset + 5] = 1f;
            matrices[offset + 10] = 1f;
            matrices[offset + 15] = 1f;
        }
        return new VatAnimationData("rest", boneCount, 1, fps, false,
            new float[]{0f}, matrices);
    }
}
