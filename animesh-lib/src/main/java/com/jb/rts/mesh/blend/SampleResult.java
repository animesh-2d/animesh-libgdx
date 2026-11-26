package com.jb.rts.mesh.blend;

/**
 * Reusable output of {@link MeshAnimationClip#sample(float, SampleResult)}: the two poses that
 * bracket a point in time plus the blend factor between them.
 *
 * <h3>Zero allocations</h3>
 * The object is <b>mutable on purpose</b>. Create one per animated entity (or one per frame) and
 * let the clip write into it over and over — a clip never allocates a result for you. Reading the
 * fields is free:
 * <pre>{@code
 * private final SampleResult sample = new SampleResult();
 * ...
 * clip.sample(stateTime, sample);          // poseA + poseB + alpha, no garbage
 * MeshBlendCommand cmd = new MeshBlendCommand(
 *     sample.poseA, 1f - sample.alpha, sample.poseB, sample.alpha);
 * }</pre>
 * (In a real game loop you should not build the command either — {@link MeshBlendAnimator} keeps one
 * {@link MeshBlendCommand} and rewrites its slots through {@link MeshBlendCommand#names} and
 * {@link MeshBlendCommand#weights}, so no command object is allocated per frame.)
 *
 * <h3>Meaning of {@link #alpha}</h3>
 * {@code alpha} is the weight of {@link #poseB}; {@code 1 - alpha} is the weight of
 * {@link #poseA}, so the pair always sums to {@code 1}. Both names are never {@code null} after a
 * successful sample (the sampler caps the factor at 1 for any larger value it passes in — see
 * {@link MeshAnimationClip#sample}).
 */
public final class SampleResult {

    /** Pose at the start of the current interval ({@code null} until the first sample). */
    public String poseA;

    /** Pose at the end of the current interval ({@code null} until the first sample). */
    public String poseB;

    /**
     * Blend factor in {@code 0..1}: {@code 0} shows {@link #poseA} at full weight, {@code 1} shows
     * {@link #poseB}. For a step frame it is exactly {@code 0} or {@code 1} — never in between.
     */
    public float alpha;

    /** Index of {@link #poseA}'s {@link MeshKeyFrame} in the clip, or {@code -1} before the first sample. */
    public int frameIndexA = -1;

    /** Index of {@link #poseB}'s {@link MeshKeyFrame} in the clip, or {@code -1} before the first sample. */
    public int frameIndexB = -1;

    /** Clears the result (used before a defensive sample). */
    public void reset() {
        poseA = null;
        poseB = null;
        alpha = 0f;
        frameIndexA = -1;
        frameIndexB = -1;
    }

    @Override
    public String toString() {
        return "Sample{[" + frameIndexA + "]" + poseA + " ×" + (1f - alpha)
            + ", [" + frameIndexB + "]" + poseB + " ×" + alpha + "}";
    }
}
