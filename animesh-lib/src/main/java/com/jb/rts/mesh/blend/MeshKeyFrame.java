package com.jb.rts.mesh.blend;

import com.badlogic.gdx.math.Interpolation;

/**
 * One keyframe of a {@link MeshAnimationClip}: the pose shown from {@link #startTime} and, unless
 * the frame is a step frame, how to ease towards the next pose.
 *
 * <p>A keyframe is a <b>window</b>: it starts at {@link #startTime} and lasts
 * {@link #duration} seconds, until the next keyframe's start time. The last keyframe of a looping
 * clip wraps back to the clip's first pose; for a non-looping clip it holds until the clip ends.
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@link #poseName} — the {@link BlendableMesh#name} of the pose, e.g.
 *       {@code "dino_longneck_walk_3"}. It is the string a {@link MeshBlendCommand} slot carries,
 *       so a keyframe never needs to hold (or look up) the mesh itself.</li>
 *   <li>{@link #startTime} — seconds from the clip start.</li>
 *   <li>{@link #duration} — seconds until the next keyframe.</li>
 *   <li>{@link #stepFrame} — {@code true} when this frame <b>holds</b> its pose for the whole
 *       interval and switches to the following pose at that pose's exact {@code startTime}
 *       instead of blending. Exports mark these frames in {@code animations[].attachmentSteps}:
 *       attachment geometry is either in place (scale&nbsp;1) or collapsed (scale&nbsp;0), so an
 *       intermediate blend would stretch it. See {@link MeshAnimationClip#sample}.</li>
 *   <li>{@link #interpolation} — the easing applied to the blend factor of an ordinary
 *       (non-step) interval; {@link Interpolation#linear} by default.</li>
 * </ul>
 *
 * <p>Keyframes are shared by every instance that plays the clip and are never mutated during
 * playback (only {@link #interpolation} may be retuned once at load time), so the per-frame
 * sampling of {@link MeshAnimationClip#sample} writes into a reusable {@link SampleResult}
 * instead of allocating.
 */
public final class MeshKeyFrame {

    /** Pose name ({@link BlendableMesh#name}) shown at this keyframe. */
    public final String poseName;

    /** Start time of this keyframe in seconds, relative to the clip start. */
    public final float startTime;

    /**
     * Length of this keyframe's interval in seconds (until the next keyframe starts). Always
     * {@code > 0}: export keyframe times are deduplicated before the clip is built
     * ({@link MeshAnimationClip#fromExport}).
     */
    public final float duration;

    /**
     * {@code true} when the pose <b>snaps</b>: it is held for the whole interval and the next pose
     * takes over at its exact start time (no interpolation, no stretching attachment geometry).
     */
    public final boolean stepFrame;

    /**
     * Easing for the blend factor inside this frame's interval (never {@code null}). Mutable so a
     * whole clip can be retuned once at load time via {@link MeshAnimationClip#applyEasing}; do not
     * change it per frame, every entity playing the clip shares this keyframe.
     */
    public Interpolation interpolation;

    /**
     * Creates a keyframe.
     *
     * @param poseName      pose name, e.g. {@code "tree_redwood_idle_1"} (not {@code null})
     * @param startTime     start in seconds (must be finite and {@code >= 0})
     * @param duration      seconds until the next keyframe (must be finite and {@code > 0})
     * @param stepFrame     {@code true} to hold the pose and snap to the next one
     * @param interpolation easing of an ordinary interval, or {@code null} for
     *                      {@link Interpolation#linear}
     * @throws IllegalArgumentException when a name is missing or a time is out of range
     */
    public MeshKeyFrame(String poseName, float startTime, float duration, boolean stepFrame,
                        Interpolation interpolation) {
        if (poseName == null || poseName.isEmpty()) {
            throw new IllegalArgumentException("A keyframe needs a pose name.");
        }
        if (!Float.isFinite(startTime) || startTime < 0f) {
            throw new IllegalArgumentException(
                "startTime must be finite and >= 0, was " + startTime + " (" + poseName + ").");
        }
        if (!Float.isFinite(duration) || duration <= 0f) {
            throw new IllegalArgumentException(
                "duration must be finite and > 0, was " + duration + " (" + poseName + ").");
        }
        this.poseName = poseName;
        this.startTime = startTime;
        this.duration = duration;
        this.stepFrame = stepFrame;
        this.interpolation = interpolation != null ? interpolation : Interpolation.linear;
    }

    /** Creates an ordinary (non-step, linear) keyframe. */
    public MeshKeyFrame(String poseName, float startTime, float duration) {
        this(poseName, startTime, duration, false, null);
    }

    /** Start time of the next keyframe ({@code startTime + duration}). */
    public float endTime() {
        return startTime + duration;
    }

    @Override
    public String toString() {
        return "MeshKeyFrame{" + poseName + " @" + startTime + "s +" + duration + "s"
            + (stepFrame ? ", step" : "") + "}";
    }
}
