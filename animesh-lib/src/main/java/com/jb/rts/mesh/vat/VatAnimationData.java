package com.jb.rts.mesh.vat;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;

/**
 * Immutable GPU animation: every bone pose of one mesheditor animation baked into a flat float
 * array that is uploaded as a single {@code RGBA32F} texture (see {@link VatFormat} for the
 * layout).
 *
 * <h3>No per-object work</h3>
 * The runtime never multiplies matrices on the CPU. Gameplay code only needs two numbers per
 * instance: a world position and a <b>fractional frame index</b>:
 * <pre>{@code
 * float baseFrame = animation.frameAt(stateTime);      // 0..frameCount-1, fractional
 * batch.begin();
 * for (Unit u : units) {
 *     batch.add(u.x, u.y, animation.wrapFrame(baseFrame + u.phaseFrames));
 * }
 * batch.render(camera.combined);                       // ONE glDrawArraysInstanced
 * }</pre>
 *
 * <h3>Keyframe timing</h3>
 * The mesheditor stores an explicit time (in milliseconds) per keyframe, so the baker records
 * those times ({@link #frameTimes()}, seconds) and {@link #frameAt(float)} converts seconds into
 * the fractional frame index by interpolating between keyframe times. When a file has no
 * per-frame times the animation is treated as a uniform {@code fps} grid.
 */
public final class VatAnimationData {

    private final String  name;
    private final int     boneCount;
    private final int     frameCount;
    private final float   fps;
    private final boolean looping;
    private final float[] frameTimes;   // seconds, ascending, length == frameCount
    private final float[] boneMatrices; // boneCount * frameCount * 16, see VatFormat
    private final int[]   frameColors;  // vertexCount * frameCount packed RGBA, or null
    private final int     colorVertexCount; // vertex rows of frameColors (0 = none)
    private final boolean[] stepFrames; // per frame: attachment snap, or empty

    /**
     * Creates animation data.
     *
     * @param name         animation name (for logs / tooling, may be empty)
     * @param boneCount    number of bone rows
     * @param frameCount   number of frame columns ({@code frameCount * 4} texels wide)
     * @param fps          nominal frames per second (used when {@code frameTimes} is null)
     * @param looping      whether playback wraps from the last frame back to frame 0
     * @param frameTimes   keyframe times in seconds (may be {@code null} for a uniform grid)
     * @param boneMatrices baked matrices, laid out as described in {@link VatFormat}
     */
    public VatAnimationData(String name, int boneCount, int frameCount, float fps,
                            boolean looping, float[] frameTimes, float[] boneMatrices) {
        this(name, boneCount, frameCount, fps, looping, frameTimes, boneMatrices,
            0, null, null);
    }

    /**
     * Creates animation data with an optional <b>per-frame colour strip</b>.
     *
     * <p>Export formats can override the vertex colours per animation frame
     * ({@code animations[].frames[].colors} in the mesheditor JSON). The static vertex
     * buffer holds one colour per vertex, so those overrides live in a separate
     * {@code RGBA8} texture that the vertex shader samples for both neighbouring frames
     * and {@code mix()}es with the same weight it uses for the positions — this is the
     * GPU equivalent of the CPU blend renderer's colour blending.
     *
     * @param colorVertexCount number of expanded vertices in the colour strip (0 when
     *                         {@code frameColors} is {@code null})
     * @param frameColors      {@code colorVertexCount * frameCount} packed RGBA texels
     *                         ({@code 0xAABBGGRR}, see {@link VatFrameColorTexture}),
     *                         or {@code null} to keep the static {@code a_color}
     */
    public VatAnimationData(String name, int boneCount, int frameCount, float fps,
                            boolean looping, float[] frameTimes, float[] boneMatrices,
                            int colorVertexCount, int[] frameColors) {
        this(name, boneCount, frameCount, fps, looping, frameTimes, boneMatrices,
            colorVertexCount, frameColors, null);
    }

    /**
     * Creates animation data with optional per-frame colours and attachment step flags.
     *
     * @param stepFrames one flag per frame ({@code true} = attachment geometry snaps at that
     *                   frame instead of blending into it), or {@code null}
     */
    public VatAnimationData(String name, int boneCount, int frameCount, float fps,
                            boolean looping, float[] frameTimes, float[] boneMatrices,
                            int colorVertexCount, int[] frameColors, boolean[] stepFrames) {
        if (boneCount <= 0) {
            throw new IllegalArgumentException("boneCount must be > 0, was " + boneCount);
        }
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be > 0, was " + frameCount);
        }
        if (boneMatrices == null
                || boneMatrices.length != VatFormat.matrixFloatCount(boneCount, frameCount)) {
            throw new IllegalArgumentException(
                "boneMatrices must hold boneCount*frameCount*"
                    + VatFormat.FLOATS_PER_MATRIX + " = "
                    + VatFormat.matrixFloatCount(boneCount, frameCount) + " floats.");
        }
        if (frameTimes != null && frameTimes.length != frameCount) {
            throw new IllegalArgumentException(
                "frameTimes must hold frameCount = " + frameCount + " entries, was "
                    + frameTimes.length);
        }
        if (frameColors != null) {
            if (colorVertexCount <= 0) {
                throw new IllegalArgumentException(
                    "colorVertexCount must be > 0 when frameColors are given, was "
                        + colorVertexCount);
            }
            if (frameColors.length != VatFormat.colorTexelCount(colorVertexCount, frameCount)) {
                throw new IllegalArgumentException(
                    "frameColors must hold colorVertexCount*frameCount = "
                        + VatFormat.colorTexelCount(colorVertexCount, frameCount)
                        + " texels, was " + frameColors.length);
            }
        }
        if (stepFrames != null && stepFrames.length != 0 && stepFrames.length != frameCount) {
            throw new IllegalArgumentException(
                "stepFrames must hold frameCount = " + frameCount + " entries, was "
                    + stepFrames.length);
        }
        this.name = name != null ? name : "";
        this.boneCount = boneCount;
        this.frameCount = frameCount;
        this.fps = fps > 0f ? fps : 24f;
        this.looping = looping;
        this.frameTimes = frameTimes;
        this.boneMatrices = boneMatrices;
        this.frameColors = frameColors;
        this.colorVertexCount = frameColors != null ? colorVertexCount : 0;
        this.stepFrames = stepFrames != null ? stepFrames : new boolean[0];
    }

    /** Creates a uniform-frame-rate animation ({@code frameTimes == null}). */
    public static VatAnimationData uniform(String name, int boneCount, int frameCount, float fps,
                                           boolean looping, float[] boneMatrices) {
        return new VatAnimationData(name, boneCount, frameCount, fps, looping, null, boneMatrices);
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    /** Animation name (never {@code null}). */
    public String name() {
        return name;
    }

    /** Number of bone rows in the texture. */
    public int boneCount() {
        return boneCount;
    }

    /** Number of baked frames. */
    public int frameCount() {
        return frameCount;
    }

    /** Nominal frame rate (frames per second). */
    public float fps() {
        return fps;
    }

    /** True when playback should wrap from the last frame back to frame 0. */
    public boolean looping() {
        return looping;
    }

    /** Baked matrix texture width in texels ({@code frameCount * 4}). */
    public int textureWidth() {
        return VatFormat.textureWidth(frameCount);
    }

    /** Baked matrix texture height in texels ({@code boneCount}). */
    public int textureHeight() {
        return VatFormat.textureHeight(boneCount);
    }

    /** Raw baked matrices. Do not modify. */
    public float[] boneMatrices() {
        return boneMatrices;
    }

    /** Keyframe times in seconds, or {@code null} for a uniform {@code 1/fps} grid. */
    public float[] frameTimes() {
        return frameTimes;
    }

    // -----------------------------------------------------------------------
    // Per-frame colours
    // -----------------------------------------------------------------------

    /** True when this animation carries per-frame vertex colour overrides. */
    public boolean hasFrameColors() {
        return frameColors != null;
    }

    /** Number of expanded vertex rows in the colour strip (0 when {@link #hasFrameColors()} is false). */
    public int colorVertexCount() {
        return colorVertexCount;
    }

    /** True when this animation carries per-frame attachment step flags. */
    public boolean hasStepFrames() {
        for (boolean step : stepFrames) {
            if (step) return true;
        }
        return false;
    }

    /** Per-frame step flags (empty when the animation has none). Do not modify. */
    public boolean[] stepFrames() {
        return stepFrames;
    }

    /** True when {@code frame} must snap attachment geometry instead of blending. */
    public boolean isStepFrame(int frame) {
        return frame >= 0 && frame < stepFrames.length && stepFrames[frame];
    }

    /**
     * Packed colour strip ({@code colorVertexCount * frameCount} texels,
     * {@code 0xAABBGGRR} — the memory order is R,G,B,A), or {@code null}.
     *
     * <p>Texel index of vertex {@code v} at frame {@code f}:
     * {@link VatFormat#frameColorTexel(int, int, int)}. Do not modify.
     */
    public int[] frameColors() {
        return frameColors;
    }

    /** Colour strip texture width in texels ({@code min(vertexCount, 2048)}). */
    public int colorTextureWidth() {
        return VatFormat.colorTextureWidth(colorVertexCount);
    }

    /** Colour strip texture height in texels ({@code ceil(vertexCount * frameCount / row)}). */
    public int colorTextureHeight() {
        return VatFormat.colorTextureHeight(colorVertexCount, frameCount);
    }

    /** Length of the clip in seconds (time of the last keyframe). */
    public float duration() {
        if (frameTimes != null) {
            return frameTimes[frameCount - 1];
        }
        return (frameCount - 1) / fps;
    }

    /** Time in seconds of frame {@code frame} (clamped to the clip range). */
    public float timeOfFrame(int frame) {
        if (frameTimes != null) {
            return frameTimes[MathUtils.clamp(frame, 0, frameCount - 1)];
        }
        return MathUtils.clamp(frame, 0, frameCount - 1) / fps;
    }

    // -----------------------------------------------------------------------
    // Time → frame
    // -----------------------------------------------------------------------

    /**
     * Converts a playback time into a <b>fractional frame index</b> for {@code a_instanceFrame}.
     * The result is clamped to {@code [0, frameCount-1]}; the GPU interpolates between the two
     * neighbouring frames. Call this once per animation and frame, then add each instance's phase
     * offset in frames (see {@link #wrapFrame(float)}).
     *
     * @param seconds playback time in seconds
     */
    public float frameAt(float seconds) {
        if (frameTimes == null) {
            return MathUtils.clamp(seconds * fps, 0f, frameCount - 1f);
        }
        if (seconds <= frameTimes[0]) return 0f;
        int last = frameCount - 1;
        if (seconds >= frameTimes[last]) return last;

        int lo = 0;
        int hi = last;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (frameTimes[mid] <= seconds) lo = mid;
            else hi = mid;
        }
        float span = frameTimes[hi] - frameTimes[lo];
        float t = span > 1e-6f ? (seconds - frameTimes[lo]) / span : 0f;
        return lo + t;
    }

    /**
     * Wraps a fractional frame index into the animation's range: modulo for looping clips,
     * clamping otherwise. Use it after adding an instance's phase offset.
     */
    public float wrapFrame(float frame) {
        if (looping) {
            float f = frame % frameCount;
            return f < 0f ? f + frameCount : f;
        }
        return MathUtils.clamp(frame, 0f, frameCount - 1f);
    }

    // -----------------------------------------------------------------------
    // Tooling (CPU access, never called per instance / per frame)
    // -----------------------------------------------------------------------

    /**
     * Reads one baked matrix into {@code out} (column-major, exactly like the GPU sees it).
     * Intended for tools, debugging and tests — not for runtime skinning.
     */
    public Matrix4 boneMatrix(int boneIndex, int frameIndex, Matrix4 out) {
        if (boneIndex < 0 || boneIndex >= boneCount) {
            throw new IndexOutOfBoundsException("boneIndex " + boneIndex + " / " + boneCount);
        }
        if (frameIndex < 0 || frameIndex >= frameCount) {
            throw new IndexOutOfBoundsException("frameIndex " + frameIndex + " / " + frameCount);
        }
        int o = VatFormat.matrixFloatOffset(boneIndex, frameIndex, frameCount);
        System.arraycopy(boneMatrices, o, out.val, 0, VatFormat.FLOATS_PER_MATRIX);
        return out;
    }

    @Override
    public String toString() {
        return "VatAnimationData{name='" + name + "', bones=" + boneCount + ", frames=" + frameCount
            + ", fps=" + fps + ", looping=" + looping + ", texture=" + textureWidth() + "x"
            + textureHeight()
            + (frameColors != null ? ", colors=" + colorTextureWidth() + "x"
                + colorTextureHeight() : "")
            + "}";
    }
}