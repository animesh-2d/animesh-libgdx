package com.jb.rts.mesh.blend;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.Array;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One animation of a mesh export as a timeline of {@link MeshKeyFrame}s, ready for the 4-slot GPU
 * blend pipeline ({@link MeshBlendAnimator} → {@link MeshBlenderGroup} /
 * {@link InstancedMeshBlenderGroup}).
 *
 * <p>Every keyframe names one {@link BlendableMesh} pose, e.g. {@code dino_longneck_walk_7}. Clips
 * are built once at load time, share their keyframes with every entity that plays them, and
 * {@link #sample} is a pure, allocation-free lookup.
 *
 * <h3>Building clips</h3>
 * <pre>{@code
 * MeshExport export = MeshExportLoader.load(Gdx.files.internal("meshes/dino_longneck-export.json"));
 * Map<String, MeshAnimationClip> clips =
 *     MeshAnimationClip.loadAllFromExport(export, Set.of("walk", "run")); // walk/run loop, die holds
 * }</pre>
 * {@link #fromExport} reads {@link MeshExport#animationFrameTimes} (the deduplicated keyframe
 * times, already aligned 1:1 with {@link BlendableMesh#poseIndex}) and
 * {@link MeshExport#animationStepFrames}. It applies <b>the same deduplication
 * {@link MeshExportLoader} applied when it dropped the poses</b>, so frame times and pose indices
 * never drift apart. Exports without {@code frameTimes} fall back to a {@code 1 / fps} grid —
 * exactly the grid the loader used to number the poses.
 *
 * <h3>Playback / sampling</h3>
 * {@link #sample} maps a clip-local time onto two bracketing poses and a blend factor: just before
 * a keyframe it is {@code [frame_i, frame_i+1, alpha≈1]}, at the keyframe's exact time it is
 * {@code [frame_i+1, frame_i+2, alpha=0]}. No allocation, no timeline objects, no garbage.
 *
 * <h3>Step frames (attachment geometry must not stretch)</h3>
 * A keyframe flagged {@link MeshKeyFrame#stepFrame} <b>holds</b> its pose across its whole
 * interval and switches to the next pose at that pose's exact {@code startTime}. Until that
 * moment {@link SampleResult#alpha} is {@code 0} (the held pose at weight {@code 1.0}); from that
 * moment on it is {@code 1} (the next pose at weight {@code 1.0}). Intermediate values never
 * occur, so an attachment is either fully in place or fully collapsed — never stretched to half
 * its size. The snap also applies when the <b>arriving</b> pose is a step frame, so both sides of
 * a transition switch cleanly.
 */
public final class MeshAnimationClip {

    /** Animation name (e.g. {@code "walk"}); also the {@link BlendableMesh#poseName()}. */
    public final String clipName;

    /** Keyframes ordered by {@link MeshKeyFrame#startTime} (read-only). */
    public final Array<MeshKeyFrame> frames;

    /**
     * Length of one playback cycle in seconds: the time of the <b>last authored keyframe</b> — the
     * same value {@link MeshSkinClip#duration()} reports, so a looping clip repeats after exactly
     * the authored period instead of accumulating the final keyframe's wrap interval.
     */
    public final float totalDuration;

    /** {@code true} when playback wraps back to the first pose at {@link #totalDuration}. */
    public final boolean looping;

    /**
     * Duration of the last keyframe's interval ({@code 1 / fps} for exports without explicit
     * times). Use it to build custom keyframe lists that loop the way loader-built clips do.
     */
    public final float frameDuration;
    private MeshAnimationClip(String clipName, Array<MeshKeyFrame> frames, float totalDuration,
                              boolean looping, float frameDuration) {
        this.clipName = clipName;
        this.frames = frames;
        this.totalDuration = totalDuration;
        this.looping = looping;
        this.frameDuration = frameDuration;
    }

    // =======================================================================
    // Factories
    // =======================================================================

    /** Builds the clip of {@code animName} with linear easing. */
    public static MeshAnimationClip fromExport(MeshExport export, String animName, boolean looping) {
        return fromExport(export, animName, looping, null);
    }

    /**
     * Builds the clip of animation {@code animName} from a loaded export.
     *
     * @param export   the export that owns the poses (see {@link MeshExportLoader})
     * @param animName animation name, e.g. {@code "walk"}
     * @param looping  {@code true} to wrap from the last pose back to the first
     * @param easing   optional easing for every ordinary interval (see {@link #applyEasing});
     *                 {@code null} keeps {@link Interpolation#linear}
     * @throws IllegalArgumentException when the export has no poses for {@code animName}
     * @throws IllegalStateException    when the keyframe times and poses disagree in length
     */
    public static MeshAnimationClip fromExport(MeshExport export, String animName, boolean looping,
                                               Interpolation easing) {
        List<BlendableMesh> poses = posesOf(export, animName);
        if (poses.isEmpty()) {
            throw new IllegalArgumentException("Export '" + export.name + "' has no animation '"
                + animName + "' (a non-empty \"frames\" array is required).");
        }
        float frameDuration = 1f / fpsOf(export, animName);
        float[] times = export.animationFrameTimes.get(animName);
        if (times == null || times.length == 0) {
            // No animation-level times at all (hand-built export): number the poses on the fps grid.
            times = new float[poses.size()];
            for (int i = 0; i < times.length; i++) times[i] = i * frameDuration;
        } else if (times.length != poses.size()) {
            throw new IllegalStateException("Animation '" + animName + "' of '" + export.name
                + "' has " + poses.size() + " poses but " + times.length + " keyframe times.");
        }
        // MeshExport.animationFrameTimes is already deduplicated by MeshExportLoader; running the
        // identical rule again makes the factory safe for altered exports and keeps the times 1:1
        // with the poses (which the loader renumbered the same way).
        boolean[] rawSteps = export.animationStepFrames.get(animName);
        Kept kept = deduplicate(poses, times, rawSteps);
        return build(animName, kept, looping, frameDuration, easing);
    }

    /** Builds every clip; none of them loops. */
    public static Map<String, MeshAnimationClip> loadAllFromExport(MeshExport export) {
        return loadAllFromExport(export, Set.of(), null);
    }

    /** Builds every clip; animations listed in {@code loopingAnims} loop, the others play once. */
    public static Map<String, MeshAnimationClip> loadAllFromExport(MeshExport export,
                                                                   Set<String> loopingAnims) {
        return loadAllFromExport(export, loopingAnims, null);
    }

    /**
     * Builds one clip per animation of {@code export}.
     *
     * @param export       the loaded export
     * @param loopingAnims animation names that loop; everything else plays once and holds its
     *                     last pose (see {@link MeshBlendAnimator})
     * @param easingByAnim optional per-animation easing ({@code null} = all linear)
     * @return clips keyed by animation name, in export order
     */
    public static Map<String, MeshAnimationClip> loadAllFromExport(
            MeshExport export, Set<String> loopingAnims,
            Map<String, ? extends Interpolation> easingByAnim) {
        Map<String, MeshAnimationClip> clips = new LinkedHashMap<>();
        for (String anim : animationNames(export)) {
            try {
                clips.put(anim, fromExport(export, anim,
                    loopingAnims != null && loopingAnims.contains(anim),
                    easingByAnim != null ? easingByAnim.get(anim) : null));
            } catch (IllegalArgumentException | IllegalStateException skipped) {
                // An animation whose poses cannot be paired with its times is not playable; the
                // other clips of the export still are, so skip it instead of failing the whole load.
            }
        }
        return clips;
    }

    // =======================================================================
    // Easing
    // =======================================================================

    /**
     * Applies a per-keyframe easing to this clip's frames ({@code linear} for every ordinary
     * interval) and returns the same clip, so it can be chained onto {@link #fromExport}:
     * <pre>{@code
     * MeshAnimationClip walk = MeshAnimationClip.fromExport(export, "walk", true)
     *     .applyEasing(Interpolation.smooth, false);
     * }</pre>
     * Step frames always get easing {@code linear} — they never interpolate anyway.
     *
     * @param easing           easing for the ordinary intervals ({@code null} = keep current)
     * @param overrideExisting {@code false} keeps frames whose easing was already customised via
     *                         the {@code easing} argument of {@link #fromExport}
     * @return {@code this}, for chaining
     */
    public MeshAnimationClip applyEasing(Interpolation easing, boolean overrideExisting) {
        if (easing == null) {
            return this;
        }
        for (int i = 0; i < frames.size; i++) {
            MeshKeyFrame frame = frames.get(i);
            if (frame.stepFrame) {
                frame.interpolation = Interpolation.linear;
            } else if (overrideExisting || frame.interpolation == Interpolation.linear) {
                frame.interpolation = easing;
            }
        }
        return this;
    }

    // =======================================================================
    // Sampling (zero allocation)
    // =======================================================================

    /**
     * Writes the two bracketing poses and the blend factor for {@code stateTime} into {@code out}.
     *
     * <h3>Time mapping</h3>
     * <ul>
     *   <li><b>Looping:</b> {@code stateTime} is wrapped into {@code [0, totalDuration)} first, so
     *       any large or negative value is valid.</li>
     *   <li><b>Not looping:</b> times {@code <= 0} return the first pose ({@code alpha = 0}); times
     *       at or after {@link #totalDuration} return the last pose at full weight.</li>
     * </ul>
     *
     * <h3>Alpha</h3>
     * Inside interval {@code i} (from {@code frame_i.startTime} to the next keyframe)
     * {@code rawAlpha = (t - start) / duration}, then:
     * <ul>
     *   <li>ordinary frame: {@code alpha = frame_i.interpolation.apply(rawAlpha)};</li>
     *   <li>step frame: {@code alpha = 0} — the pose is held at weight {@code 1.0} — and the
     *       <b>next</b> pose takes over with {@code alpha = 1} at exactly its {@code startTime}.
     *       The arriving pose's own {@code stepFrame} flag is honoured as well: if
     *       {@code frame_i+1} is a step frame the tail of the interval snaps
     *       ({@code alpha = 1} once {@code rawAlpha >= 0.5}).</li>
     * </ul>
     *
     * @param stateTime clip-local time in seconds
     * @param out       the reusable result to overwrite
     * @return {@code out}
     * @throws IllegalArgumentException when {@code out == null}
     */
    public SampleResult sample(float stateTime, SampleResult out) {
        if (out == null) {
            throw new IllegalArgumentException("sample() needs a reusable SampleResult.");
        }
        int n = frames.size;
        if (n == 0) {
            out.reset();
            return out;
        }
        if (n == 1) {
            return at(0, 0f, out);
        }
        float t = stateTime;
        if (looping && totalDuration > 0f) {
            t = t % totalDuration;
            if (t < 0f) t += totalDuration;
        }
        if (t <= 0f) {
            return at(0, 0f, out);
        }
        if (!looping && t >= totalDuration) {
            return at(n - 1, 1f, out);
        }
        int i = indexOf(t);
        MeshKeyFrame frame = frames.get(i);
        float rawAlpha = (t - frame.startTime) / frame.duration;
        if (rawAlpha < 0f) rawAlpha = 0f;
        if (rawAlpha > 1f) rawAlpha = 1f;
        boolean nextIsStep = i + 1 < n && frames.get(i + 1).stepFrame;
        if (frame.stepFrame) {
            // Held pose wins until the interval ends, then it snaps over completely. A step
            // arrival is not an option here: the held pose must not blend at all.
            return at(i, nextIsStep && rawAlpha >= 0.5f ? 1f : 0f, out);
        }
        float alpha = frame.interpolation.apply(rawAlpha);
        if (nextIsStep && rawAlpha >= 0.5f) {
            alpha = 1f;
        }
        return at(i, alpha, out);
    }

    /** Index of the keyframe interval containing {@code t} (clamped; binary search). */
    public int indexOf(float t) {
        int n = frames.size;
        if (n <= 1 || t <= frames.get(0).startTime) {
            return 0;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frames.get(mid).startTime <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The keyframe at {@code index} (clamped into range; {@code null} only when empty). */
    public MeshKeyFrame at(int index) {
        if (frames.size == 0) {
            return null;
        }
        return frames.get(Math.max(0, Math.min(frames.size - 1, index)));
    }

    /**
     * Writes keyframe {@code index} into {@code out} at blend factor {@code alpha} and returns it:
     * {@code poseA} is the keyframe's pose, {@code poseB} the following one (itself at the end).
     */
    public SampleResult at(int index, float alpha, SampleResult out) {
        int n = frames.size;
        if (n == 0) {
            out.reset();
            return out;
        }
        int i = Math.max(0, Math.min(n - 1, index));
        MeshKeyFrame frame = frames.get(i);
        out.frameIndexA = i;
        out.poseA = frame.poseName;
        if (i + 1 < n) {
            out.frameIndexB = i + 1;
            out.poseB = frames.get(i + 1).poseName;
        } else {
            // At the last keyframe: the clip ends (or loops). Both names stay valid.
            out.frameIndexB = i;
            out.poseB = frame.poseName;
        }
        out.alpha = alpha;
        return out;
    }

    /** The dominant pose at {@code stateTime} ({@code scratch} is reused, nothing is allocated). */
    public String poseAt(float stateTime, SampleResult scratch) {
        sample(stateTime, scratch);
        return scratch.alpha < 0.5f ? scratch.poseA : scratch.poseB;
    }

    /** The pose a looping clip wraps to (its first keyframe's pose), or {@code null} when empty. */
    public String loopPose() {
        return frames.size > 0 ? frames.get(0).poseName : null;
    }

    // =======================================================================
    // Internal builders
    // =======================================================================

    /** Poses kept after deduplication plus their times and step flags. */
    private record Kept(List<BlendableMesh> poses, float[] times, boolean[] steps) {}

    /**
     * Drops zero-length keyframe intervals exactly like {@link MeshExportLoader} when it renumbered
     * the poses: when {@code times[i + 1] <= times[i] + TIME_EPSILON} the earlier pose is
     * superseded, its attachment switch is carried over to the surviving frame, and the surviving
     * frame takes the later time.
     */
    private static Kept deduplicate(List<BlendableMesh> poses, float[] times, boolean[] rawSteps) {
        List<BlendableMesh> keptPoses = new ArrayList<>(poses.size());
        List<Float> keptTimes = new ArrayList<>(poses.size());
        List<Boolean> keptSteps = new ArrayList<>(poses.size());
        for (int i = 0; i < poses.size(); i++) {
            boolean step = rawSteps != null && i < rawSteps.length && rawSteps[i];
            if (i + 1 < poses.size() && i + 1 < times.length
                    && times[i + 1] <= times[i] + SkinExportParser.TIME_EPSILON) {
                if (step && !keptSteps.isEmpty()) {
                    keptSteps.set(keptSteps.size() - 1, true);
                }
                continue;
            }
            keptPoses.add(poses.get(i));
            keptTimes.add(times[i]);
            keptSteps.add(step);
        }
        float[] outTimes = new float[keptTimes.size()];
        boolean[] outSteps = new boolean[keptSteps.size()];
        boolean anyStep = false;
        for (int i = 0; i < outTimes.length; i++) {
            outTimes[i] = keptTimes.get(i);
            outSteps[i] = keptSteps.get(i);
            anyStep |= outSteps[i];
        }
        return new Kept(keptPoses, outTimes, anyStep ? outSteps : null);
    }

    /** Turns the kept poses/times into keyframes with positive, monotonic durations. */
    private static MeshAnimationClip build(String animName, Kept kept, boolean looping,
                                           float frameDuration, Interpolation easing) {
        List<BlendableMesh> poses = kept.poses();
        float[] times = kept.times();
        int n = poses.size();
        // Merge any remaining equal times so every interval has a positive length (only possible
        // when an export lists its *last* frames at the same instant).
        int m = 0;
        float[] starts = new float[n];
        List<String> names = new ArrayList<>(n);
        List<Boolean> steps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean step = kept.steps() != null && kept.steps()[i];
            if (m > 0 && times[i] <= starts[m - 1] + SkinExportParser.TIME_EPSILON) {
                names.set(m - 1, poses.get(i).name);
                steps.set(m - 1, steps.get(m - 1) || step);
                continue;
            }
            starts[m] = times[i];
            names.add(poses.get(i).name);
            steps.add(step);
            m++;
        }
        if (m == 0) {
            throw new IllegalArgumentException("Animation '" + animName + "' has no keyframes.");
        }
        Array<MeshKeyFrame> frames = new Array<>(true, m, MeshKeyFrame.class);
        for (int i = 0; i < m; i++) {
            // Explicitly positive, monotonic start times (legacy files may start at t > 0).
            float floor = i == 0 ? 0f : starts[i - 1] + SkinExportParser.TIME_EPSILON;
            starts[i] = Math.max(starts[i], floor);
            float end = i + 1 < m ? starts[i + 1] : starts[i] + frameDuration;
            float duration = Math.max(end - starts[i], SkinExportParser.TIME_EPSILON);
            frames.add(new MeshKeyFrame(names.get(i), starts[i], duration, steps.get(i), easing));
        }
        float total = m >= 2 ? starts[m - 1] : starts[0] + frameDuration;
        return new MeshAnimationClip(animName, frames, total, looping, frameDuration);
    }

    /** {@link BlendableMesh} poses of {@code animName}, ordered by {@link BlendableMesh#poseIndex}. */
    private static List<BlendableMesh> posesOf(MeshExport export, String animName) {
        List<BlendableMesh> poses = new ArrayList<>();
        for (BlendableMesh mesh : export.meshes) {
            if (animName.equals(mesh.poseName())) {
                poses.add(mesh);
            }
        }
        poses.sort(Comparator.comparingInt(BlendableMesh::poseIndex));
        return poses;
    }

    /** Animation names of {@code export}, in export order. */
    private static List<String> animationNames(MeshExport export) {
        List<String> names = new ArrayList<>();
        if (!export.animationFrameTimes.isEmpty()) {
            names.addAll(export.animationFrameTimes.keySet());
            return names;
        }
        Set<String> seen = new HashSet<>();
        for (BlendableMesh mesh : export.meshes) {
            String name = mesh.poseName();
            if (!name.isEmpty() && !"rest".equals(name) && seen.add(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Nominal frame rate of the animation, or the export-wide default. */
    private static float fpsOf(MeshExport export, String animName) {
        Float fps = export.animationFps.get(animName);
        return fps != null && fps > 0f && Float.isFinite(fps) ? fps : SkinExportParser.DEFAULT_FPS;
    }

    @Override
    public String toString() {
        return "MeshAnimationClip{'" + clipName + "', frames=" + frames.size
            + ", duration=" + totalDuration + "s, looping=" + looping + "}";
    }
}




