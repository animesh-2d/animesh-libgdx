package com.jb.rts.mesh.vat;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves an export pose's per-vertex colour indexes into the RGBA values the VAT static vertex
 * buffer stores (one colour per expanded vertex) and into the optional per-frame colour strip.
 *
 * <p>Shared by {@link VatPoseBaker} and {@link VatSkinBaker} so both paths treat a missing or
 * out-of-range palette entry identically: the vertex becomes white instead of failing the file.
 */
final class VatPalette {

    private VatPalette() {}

    /** RGBA ({@code 0..1}) per expanded vertex, in the pose's vertex order. */
    static float[] resolve(BlendableMesh pose, ColorPalette palette) {
        int vertexCount = pose.vertexCount();
        float[] colors = new float[vertexCount * 4];
        int paletteSize = palette != null ? palette.size() : 0;
        for (int v = 0; v < vertexCount; v++) {
            int o = v * 4;
            int index = pose.colorIndices[v];
            if (paletteSize == 0 || index < 0 || index >= paletteSize) {
                colors[o] = colors[o + 1] = colors[o + 2] = 1f;
                colors[o + 3] = 1f;
                continue;
            }
            colors[o]     = palette.red(index);
            colors[o + 1] = palette.green(index);
            colors[o + 2] = palette.blue(index);
            colors[o + 3] = palette.alpha(index);
        }
        return colors;
    }

    /**
     * Builds the per-frame colour strip ({@code 0xAABBGGRR} texels, frame-major) for an
     * animation, or {@code null} when every frame uses the bind pose's colour indexes — in that
     * case the static {@code a_color} attribute is already exact and no texture is needed.
     *
     * <p>This is what puts <b>colour blending</b> back into the GPU pipeline: the mesheditor
     * writes {@code animations[].frames[].colors} when a frame recolours triangle corners
     * (fire flicker, damage flash, …), {@link com.jb.rts.mesh.blend.MeshExportLoader} applies
     * them per pose, and the shader then mixes the colours of the two surrounding frames with
     * the same weight as their positions.
     *
     * @param poses   the animation frames in playback order (index 0 is frame 0)
     * @param palette palette the colour indexes refer to (may be {@code null} → white)
     * @return {@code poses.size() * vertexCount} packed texels, or {@code null}
     * @throws IllegalArgumentException when the poses do not share one vertex count
     */
    static int[] resolveFrames(List<BlendableMesh> poses, ColorPalette palette) {
        if (poses == null || poses.isEmpty()) {
            return null;
        }
        int vertexCount = poses.get(0).vertexCount();
        int[] bindColors = poses.get(0).colorIndices;
        boolean overridden = false;
        for (int f = 1; f < poses.size(); f++) {
            BlendableMesh pose = poses.get(f);
            if (pose == null || pose.vertexCount() != vertexCount) {
                throw new IllegalArgumentException(
                    "Frame " + f + " does not share frame 0's vertex count (" + vertexCount + ").");
            }
            if (!Arrays.equals(pose.colorIndices, bindColors)) {
                overridden = true;
            }
        }
        if (!overridden) {
            return null;
        }

        int[] packed = new int[VatFormat.colorTexelCount(vertexCount, poses.size())];
        for (int f = 0; f < poses.size(); f++) {
            int[] indices = poses.get(f).colorIndices;
            int base = f * vertexCount;
            for (int v = 0; v < vertexCount; v++) {
                packed[base + v] = pack(indexToRgba(indices[v], palette));
            }
        }
        return smoothColorChanges(packed, vertexCount, poses.size());
    }

    /**
     * Turns every <b>step</b> in a vertex's colour sequence into a <b>ramp</b> across the frames
     * that preceded it.
     *
     * <h3>Why this is needed</h3>
     * The mesheditor stores colours <em>per keyframe</em> (palette indexes), and the exporter
     * subdivides each keyframe interval into many sampled frames so that bone rotations become
     * small steps ({@code FrameSampler}). Those intermediate frames inherit the <em>previous</em>
     * keyframe's colours, so a colour change that spans a whole keyframe interval collapses into a
     * single sampled frame — on the GPU the colour then appears to snap instead of fading.
     *
     * <p>Colour <em>indexes</em> cannot be interpolated, but the resolved RGBA can: for every run
     * of identical colours followed by a change, the frames of that run are rewritten as
     * {@code mix(previousColour, newColour, k / runLength)}. The authored colours themselves stay
     * exact — every run start keeps its keyframe colour, and a run of a single frame (sparse
     * exports like a 3-keyframe test file) is left untouched, because the shader already blends
     * the two neighbouring frames over the whole interval.
     *
     * <p>The result is exactly what the CPU blend renderer produced before the export was
     * subdivided: a linear cross-fade between two keyframe colours.
     *
     * @param packed     {@code vertexCount * frameCount} {@code 0xAABBGGRR} texels (modified)
     * @param vertexCount number of expanded vertices
     * @param frameCount  number of frames
     * @return {@code packed} (for chaining)
     */
    static int[] smoothColorChanges(int[] packed, int vertexCount, int frameCount) {
        if (frameCount < 2) {
            return packed;
        }
        for (int v = 0; v < vertexCount; v++) {
            int runStart = 0;
            int previous = packed[v];
            for (int f = 1; f < frameCount; f++) {
                int color = packed[f * vertexCount + v];
                if (color == previous) {
                    continue;
                }
                int runLength = f - runStart;      // frames that still show `previous`
                for (int k = 1; k < runLength; k++) {
                    packed[(runStart + k) * vertexCount + v] =
                        mix(previous, color, k / (float) runLength);
                }
                runStart = f;
                previous = color;
            }
        }
        return packed;
    }

    /** Per-channel linear blend of two packed {@code 0xAABBGGRR} colours. */
    private static int mix(int from, int to, float t) {
        int r = Math.round((from & 0xFF)         + (((to & 0xFF)         - (from & 0xFF))         * t));
        int g = Math.round(((from >> 8) & 0xFF)  + ((((to >> 8) & 0xFF)  - ((from >> 8) & 0xFF))  * t));
        int b = Math.round(((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t));
        int a = Math.round(((from >> 24) & 0xFF) + ((((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t));
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** RGBA floats ({@code 0..1}) of one palette index; white for missing/out-of-range. */
    private static float[] indexToRgba(int index, ColorPalette palette) {
        int paletteSize = palette != null ? palette.size() : 0;
        if (paletteSize == 0 || index < 0 || index >= paletteSize) {
            return new float[]{1f, 1f, 1f, 1f};
        }
        return new float[]{palette.red(index), palette.green(index),
            palette.blue(index), palette.alpha(index)};
    }

    /** Packs RGBA ({@code 0..1}) into {@code 0xAABBGGRR} (memory order R, G, B, A). */
    private static int pack(float[] rgba) {
        return (toByte(rgba[3]) << 24) | (toByte(rgba[2]) << 16)
            | (toByte(rgba[1]) << 8) | toByte(rgba[0]);
    }

    private static int toByte(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

