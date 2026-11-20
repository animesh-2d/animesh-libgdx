package com.jb.rts.mesh.blend;

/**
 * One animation's baked <b>2D affine bone matrices</b> as stored by the mesheditor export
 * (version&nbsp;4) or derived by {@link MeshExportLoader} from the legacy centre/direction
 * keyframes.
 *
 * <p>Per bone and frame six floats are stored — everything a 2D skinning shader needs and nothing
 * more:
 * <pre>
 *   [ m00, m01, m03, m10, m11, m13 ]      x' = m00·x + m01·y + m03
 *                                         y' = m10·x + m11·y + m13
 * </pre>
 * (column&nbsp;2 of the 4×4 matrix is the constant {@code (0,0,1,0)}). Missing bones of a frame
 * are identity — the loader fills them in, so every clip is dense.
 *
 * <p>Frame {@code f} of bone {@code b} starts at
 * {@code (f * boneCount + b) * FLOATS_PER_MATRIX}.
 */
public final class MeshSkinClip {

    /** Floats stored per bone matrix ({@code m00, m01, m03, m10, m11, m13}). */
    public static final int FLOATS_PER_MATRIX = 6;

    /** Animation name (for logs / UI). */
    public final String name;

    /** Whether playback wraps from the last frame back to the first. */
    public final boolean looping;

    /** Nominal frame rate (metadata; keyframe times are authoritative). */
    public final float fps;

    /** Number of bone rows ({@code skin.boneCount()}). */
    public final int boneCount;

    /** Number of frames. */
    public final int frameCount;

    /** Keyframe times in seconds, ascending, length {@link #frameCount}. */
    public final float[] frameTimes;

    /** {@code frameCount * boneCount * FLOATS_PER_MATRIX} floats (mutable for {@link #flipY()}). */
    public final float[] matrices;

    /**
     * Per frame: {@code true} when attachment geometry must <b>snap</b> at that
     * frame instead of blending into it (the effective attachment state differs
     * from the previous frame). Empty when the clip has no such frame.
     */
    public final boolean[] stepFrames;

    /**
     * Creates a clip.
     *
     * @throws IllegalArgumentException when the array sizes disagree
     */
    public MeshSkinClip(String name, boolean looping, float fps, int boneCount,
                        float[] frameTimes, float[] matrices) {
        this(name, looping, fps, boneCount, frameTimes, matrices, null);
    }

    /**
     * Creates a clip with per-frame attachment step flags.
     *
     * @param stepFrames one flag per frame, or {@code null} for none
     * @throws IllegalArgumentException when the array sizes disagree
     */
    public MeshSkinClip(String name, boolean looping, float fps, int boneCount,
                        float[] frameTimes, float[] matrices, boolean[] stepFrames) {
        if (boneCount <= 0) {
            throw new IllegalArgumentException("boneCount must be > 0, was " + boneCount);
        }
        if (frameTimes == null || frameTimes.length == 0) {
            throw new IllegalArgumentException("A clip needs at least one keyframe time.");
        }
        int frames = frameTimes.length;
        if (matrices == null || matrices.length != frames * boneCount * FLOATS_PER_MATRIX) {
            throw new IllegalArgumentException(
                "matrices must hold frameCount*boneCount*" + FLOATS_PER_MATRIX + " = "
                    + (frames * boneCount * FLOATS_PER_MATRIX) + " floats.");
        }
        if (stepFrames != null && stepFrames.length != 0 && stepFrames.length != frames) {
            throw new IllegalArgumentException(
                "stepFrames must hold one entry per frame (" + frames + "), was "
                    + stepFrames.length + ".");
        }
        this.name = name != null ? name : "";
        this.looping = looping;
        this.fps = fps > 0f ? fps : 30f;
        this.boneCount = boneCount;
        this.frameCount = frames;
        this.frameTimes = frameTimes;
        this.matrices = matrices;
        this.stepFrames = stepFrames != null ? stepFrames : new boolean[0];
    }

    /** True when {@code frame} must snap instead of blending into the next frame. */
    public boolean isStepFrame(int frame) {
        return stepFrames.length > 0 && frame >= 0 && frame < stepFrames.length
            && stepFrames[frame];
    }

    /** True when any frame carries an attachment step. */
    public boolean hasStepFrames() {
        for (boolean step : stepFrames) {
            if (step) return true;
        }
        return false;
    }

    /** Flat float offset of {@code bone} in {@code frame}. */
    public int offset(int bone, int frame) {
        return (frame * boneCount + bone) * FLOATS_PER_MATRIX;
    }

    /** Value {@code element} (0..5, order {@code m00,m01,m03,m10,m11,m13}) of one matrix. */
    public float value(int bone, int frame, int element) {
        return matrices[offset(bone, frame) + element];
    }

    /**
     * Mirrors every matrix on y ({@code m01 = -m01}, {@code m10 = -m10}, {@code m13 = -m13}) so it
     * maps y-down export geometry that was flipped into world y-up space correctly.
     */
    public void flipY() {
        for (int i = 0; i < matrices.length; i += FLOATS_PER_MATRIX) {
            matrices[i + 1] = -matrices[i + 1]; // m01
            matrices[i + 3] = -matrices[i + 3]; // m10
            matrices[i + 5] = -matrices[i + 5]; // m13
        }
    }

    /** Duration of the clip in seconds (time of the last keyframe). */
    public float duration() {
        return frameTimes[frameTimes.length - 1];
    }

    @Override
    public String toString() {
        return "MeshSkinClip{name='" + name + "', frames=" + frameCount
            + ", bones=" + boneCount + ", looping=" + looping + "}";
    }
}
