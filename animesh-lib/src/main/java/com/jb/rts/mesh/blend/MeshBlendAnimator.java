package com.jb.rts.mesh.blend;

/**
 * Plays a {@link MeshAnimationClip} into a {@link MeshBlendCommand} that a {@link MeshBlenderGroup}
 * or {@link InstancedMeshBlenderGroup} can upload, <b>without allocating per frame</b>.
 *
 * <p>One animator belongs to one entity. The command and the two scratch {@link SampleResult}s are
 * created once and rewritten in place, so a running animation produces no garbage at all.
 *
 * <h3>Playback</h3>
 * {@link #update(float)} advances the clip time and writes the result into {@link #command()}:
 * <ul>
 *   <li><b>Normal playback</b> uses slots {@code 0} and {@code 1} â€” the two poses bracketing
 *       {@link #stateTime()} (see {@link MeshAnimationClip#sample}).</li>
 *   <li><b>Crossfade</b> uses all four slots: the two poses of the outgoing clip in {@code 0/1} and
 *       the two poses of the incoming clip in {@code 2/3}, ramped by {@link #fade()}.
 *       {@code alpha = 0} is the old clip, {@code alpha = 1} the new one. The first
 *       {@link #update(float)} after {@link #play(MeshAnimationClip, float)} already advances the
 *       ramp by its own delta, so the fade lasts exactly {@code fadeDuration}.</li>
 *   <li><b>Step frames</b> ({@link MeshKeyFrame#stepFrame}) never blend: when either clip's visible
 *       poses contain a step frame, or {@code fadeDuration <= 0}, {@link #play} snaps instead of
 *       crossing over, so attachment geometry is never stretched (see
 *       {@link MeshAnimationClip#sample}).</li>
 *   <li><b>Rapid interruption</b> freezes the blend where it is: if {@link #play} is called again
 *       while a crossfade is still running, the half-blended pose becomes the new <em>outgoing</em>
 *       slot content, so an interrupted fade never pops back to a full pose.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MeshBlendAnimator animator = new MeshBlendAnimator(clips.get("walk"));
 * ...
 * animator.play(clips.get("attack"), 0.08f);   // crossfade in 80 ms (or snap for step frames)
 * animator.update(delta);
 * group.setBlend(animator.command());
 * group.render(camera.combined);
 * }</pre>
 *
 * <h3>Threading</h3>
 * Not thread-safe; drive it from the render thread like the blender groups it feeds.
 */
public final class MeshBlendAnimator {

    /** Clip that is currently being played (never {@code null} after construction). */
    private MeshAnimationClip currentClip;

    /** Clip that is being faded out, or {@code null} when no crossfade runs. */
    private MeshAnimationClip outgoingClip;

    /** Clip-local playback time of {@link #currentClip}, in seconds. */
    private float stateTime;

    /** Clip-local playback time of {@link #outgoingClip}, in seconds. */
    private float outgoingStateTime;

    /** Crossfade progress: {@code 0} = outgoing clip, {@code 1} = current clip only. */
    private float fade = 1f;

    /** Duration of the running crossfade in seconds ({@code 0} when none runs). */
    private float fadeDuration;

    /** Scratch results - allocated once, rewritten by every {@link #update(float)}. */
    private final SampleResult sampleCurrent = new SampleResult();
    private final SampleResult sampleOutgoing = new SampleResult();

    /** The reusable command handed to the blender; never reallocated after construction. */
    private final MeshBlendCommand command = new MeshBlendCommand(
        null, 0f, null, 0f, null, 0f, null, 0f);

    /**
     * Plays {@code clip} immediately (no fade).
     *
     * @param clip the clip to play (not {@code null})
     * @throws IllegalArgumentException when {@code clip} is {@code null}
     */
    public MeshBlendAnimator(MeshAnimationClip clip) {
        if (clip == null) {
            throw new IllegalArgumentException("MeshBlendAnimator needs a clip.");
        }
        this.currentClip = clip;
        writeCurrentOnly();
    }

    // =======================================================================
    // Play requests
    // =======================================================================


    /**
     * Starts (or restarts) {@code clip} and crossfades from whatever is playing.
     *
     * <p>Shorthand for {@link #play(MeshAnimationClip, float, boolean)} with
     * {@code forceRestart = true}: use it for clip triggers (an attack played twice must replay
     * from its first pose instead of continuing).
     *
     * @param clip         the clip to play (not {@code null})
     * @param fadeDuration seconds for the crossfade; {@code <= 0} snaps
     * @return {@code true} when a new clip was started, {@code false} when nothing changed
     */
    public boolean play(MeshAnimationClip clip, float fadeDuration) {
        return play(clip, fadeDuration, true);
    }

    /**
     * Plays {@code clip}, crossfading over {@code fadeDuration} seconds.
     *
     * <p>Requesting the clip that is already playing is a no-op unless {@code forceRestart} is
     * {@code true}, so a state machine may call this every frame without restarting. A crossfade is
     * replaced by a snap when {@code fadeDuration <= 0} or when the outgoing or incoming clip shows
     * a step frame ({@link MeshKeyFrame#stepFrame}); attachment geometry must never be blended.
     *
     * @param clip         the clip to play (not {@code null})
     * @param fadeDuration seconds for the crossfade; {@code <= 0} snaps
     * @param forceRestart {@code true} restarts {@code clip} from time {@code 0} even when it is
     *                     already the current clip
     * @return {@code true} when a new clip was started (or restarted), {@code false} when nothing
     *         changed
     * @throws IllegalArgumentException when {@code clip} is {@code null}
     */
    public boolean play(MeshAnimationClip clip, float fadeDuration, boolean forceRestart) {
        if (clip == null) {
            throw new IllegalArgumentException("play() needs a clip.");
        }
        if (clip == currentClip) {
            if (!forceRestart) {
                return false;
            }
            // Restart the same clip; a crossfade that was running into it is simply dropped.
            this.stateTime = 0f;
            this.outgoingClip = null;
            this.fade = 1f;
            this.fadeDuration = 0f;
            writeCurrentOnly();
            return true;
        }
        // Whatever is visible right now becomes the outgoing side of the fade. A fade that is
        // already running keeps the progress it reached, so the visible pose weight never jumps
        // when a third clip interrupts (see class doc); a fresh fade starts fully on the old clip.
        boolean interruptingFade = this.outgoingClip != null;
        this.outgoingClip = currentClip;
        this.outgoingStateTime = stateTime;
        this.currentClip = clip;
        this.stateTime = 0f;
        if (fadeDuration <= 0f || crossesStepFrame()) {
            this.fade = 1f;
            this.fadeDuration = 0f;
            this.outgoingClip = null;
            writeCurrentOnly();
        } else {
            this.fadeDuration = fadeDuration;
            this.fade = interruptingFade ? fade : 0f;
            writeCrossfade();
        }
        return true;
    }

    // =======================================================================
    // Per-frame update
    // =======================================================================

    /**
     * Advances the animation by {@code delta} seconds and rewrites {@link #command()}.
     *
     * <p>Non-looping clips stop on their last pose (see {@link MeshAnimationClip#sample}); the clip
     * time keeps counting so {@link #isFinished()} stays {@code true}. A negative {@code delta} is
     * ignored. Allocation-free.
     *
     * @param delta seconds since the last frame
     * @return the updated {@link #command()} (the same instance on every call)
     */
    public MeshBlendCommand update(float delta) {
        if (delta > 0f) {
            this.stateTime += delta;
            if (outgoingClip != null) {
                this.outgoingStateTime += delta;
            }
        }
        if (outgoingClip == null) {
            writeCurrentOnly();
            return command;
        }
        if (fadeDuration > 0f && delta > 0f) {
            this.fade += delta / fadeDuration;
        }
        if (fade >= 1f) {
            this.fade = 1f;
            this.fadeDuration = 0f;
            this.outgoingClip = null;
            writeCurrentOnly();
            return command;
        }
        writeCrossfade();
        return command;
    }
    // =======================================================================
    // Command writers
    // =======================================================================

    /**
     * Normal playback: the two poses of {@link #currentClip} go to slots {@code 0/1}.
     * Slot weights sum to 1 ({@code pose B} weight may be {@code 0}, then it is left out).
     */
    private void writeCurrentOnly() {
        currentClip.sample(stateTime, sampleCurrent);
        String a = sampleCurrent.poseA;
        String b = sampleCurrent.poseB;
        float t = sampleCurrent.alpha;
        clearSlots();
        if (a == null) {
            return;
        }
        if (b == null || b.equals(a) || t <= 0f) {
            setSlot(0, a, 1f);
        } else if (t >= 1f) {
            setSlot(0, b, 1f);
        } else {
            setSlot(0, a, 1f - t);
            setSlot(1, b, t);
        }
    }

    /**
     * Crossfade: outgoing clip poses in slots {@code 0/1}, current clip poses in slots {@code 2/3},
     * each side normalised to 1 and scaled by {@code 1 - fade} / {@code fade}.
     */
    private void writeCrossfade() {
        currentClip.sample(stateTime, sampleCurrent);
        float incoming = fade;
        float outgoing = 1f - fade;
        if (incoming >= 1f) {
            writeCurrentOnly();
            return;
        }
        clearSlots();
        if (outgoing > 0f) {
            outgoingClip.sample(outgoingStateTime, sampleOutgoing);
            addSample(0, sampleOutgoing, outgoing);
        }
        if (incoming > 0f) {
            addSample(2, sampleCurrent, incoming);
        }
    }

    /**
     * Writes {@code sample}'s two poses into the two slots starting at {@code slot}, scaled by
     * {@code weight}. A pose at weight {@code >= 1} fills the whole side.
     */
    private void addSample(int slot, SampleResult sample, float weight) {
        String a = sample.poseA;
        String b = sample.poseB;
        float t = sample.alpha;
        if (a == null) {
            return;
        }
        if (b == null || b.equals(a) || t <= 0f) {
            setSlot(slot, a, weight);
        } else if (t >= 1f) {
            setSlot(slot, b, weight);
        } else {
            setSlot(slot, a, weight * (1f - t));
            setSlot(slot + 1, b, weight * t);
        }
    }

    private void clearSlots() {
        for (int i = 0; i < MeshBlendCommand.SLOTS; i++) {
            command.names[i] = null;
            command.weights[i] = 0f;
        }
    }

    private void setSlot(int slot, String name, float weight) {
        command.names[slot] = name;
        command.weights[slot] = weight;
    }
    // =======================================================================
    // Queries
    // =======================================================================

    /**
     * {@code true} when the visible poses of the outgoing or incoming clip include a step frame,
     * i.e. blending the two clips could stretch attachment geometry and must be snapped instead.
     */
    private boolean crossesStepFrame() {
        currentClip.sample(stateTime, sampleCurrent);
        outgoingClip.sample(outgoingStateTime, sampleOutgoing);
        return isStepFrame(currentClip, sampleCurrent) || isStepFrame(outgoingClip, sampleOutgoing);
    }

    private static boolean isStepFrame(MeshAnimationClip clip, SampleResult sample) {
        MeshKeyFrame frameA = clip.at(sample.frameIndexA);
        MeshKeyFrame frameB = clip.at(sample.frameIndexB);
        return (frameA != null && frameA.stepFrame) || (frameB != null && frameB.stepFrame);
    }

    /**
     * The command describing the current pose mix. It is <b>the same instance on every call</b> and
     * its {@link MeshBlendCommand#names} / {@link MeshBlendCommand#weights} arrays are rewritten by
     * {@link #update(float)}; hand it straight to {@code setBlend(...)} and do not keep it.
     */
    public MeshBlendCommand command() {
        return command;
    }

    /** The clip currently being played (the incoming one during a crossfade). */
    public MeshAnimationClip currentClip() {
        return currentClip;
    }

    /** The clip being faded out, or {@code null} when no crossfade runs. */
    public MeshAnimationClip outgoingClip() {
        return outgoingClip;
    }

    /** Clip-local playback time of {@link #currentClip()} in seconds. */
    public float stateTime() {
        return stateTime;
    }

    /** Crossfade progress: {@code 0} = outgoing clip only, {@code 1} = current clip only. */
    public float fade() {
        return fade;
    }

    /** {@code true} while a crossfade is running (and therefore all four slots may be used). */
    public boolean isCrossfading() {
        return outgoingClip != null;
    }

    /**
     * {@code true} when a non-looping {@link #currentClip()} has reached its last pose - the signal
     * a state machine uses to switch from an attack or death animation to the next state.
     * Always {@code false} for a looping clip.
     */
    public boolean isFinished() {
        return !currentClip.looping && stateTime >= currentClip.totalDuration;
    }

    @Override
    public String toString() {
        return "MeshBlendAnimator{'" + currentClip.clipName + "' @" + stateTime + "s"
            + (outgoingClip != null
                ? ", fading from '" + outgoingClip.clipName + "' at " + fade
                : "")
            + ", pose=" + command + "}";
    }
}



