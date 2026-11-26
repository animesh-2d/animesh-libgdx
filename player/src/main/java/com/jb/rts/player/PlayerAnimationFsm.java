package com.jb.rts.player;

import com.badlogic.gdx.ai.fsm.DefaultStateMachine;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatInstancedMesh;

import java.util.Arrays;
import java.util.List;

/**
 * The player's <b>animation state machine</b>: a gdx-ai {@link DefaultStateMachine} with one
 * {@link DynamicAnimState} per animation of the loaded file, plus the crossfade bookkeeping the
 * mesh player needs to draw the transition.
 *
 * <p><b>Data-driven.</b> The player opens arbitrary exports ({@code idle}, {@code walk},
 * {@code run}, {@code defend}, {@code jump}, {@code attack}, {@code die}, …), so the states are
 * created from the loaded clip list — never from an enum. Each clip's {@link AnimationRule} is
 * inferred by {@link AnimationRule#infer} from its name and its baked data.</p>
 *
 * <h3>Transition rules</h3>
 * <ul>
 *   <li><b>Looping</b> states ({@code idle}, {@code walk}, {@code run}, {@code defend}, …) may go
 *       anywhere: they are the "movement" layer of the machine.</li>
 *   <li><b>One-shot</b> actions ({@code attack}, {@code jump}, {@code hurt}, …) block the
 *       locomotion layer ({@code walk}, {@code run}, {@code idle}) until the clip finishes
 *       ({@code localTime >= duration}); afterwards the machine returns to {@code idle} or to the
 *       locomotion state it interrupted.</li>
 *   <li><b>Terminal</b> states ({@code die}) hold their last frame and reject <em>every</em> exit
 *       unless {@link #setForceBlend(boolean)} is on or the machine is {@link #reset() reset}.</li>
 * </ul>
 * A blocked request is recorded in {@link #lastRejectedIndex()} together with a
 * {@value #REJECT_FLASH_SECONDS}&nbsp;s flash timer, which is what the panel and the graph use to
 * blink the node red.
 *
 * <h3>How the player consumes it</h3>
 * The machine owns no GPU resource and never touches the VAT batches. It produces the three values
 * {@code MeshPlayer} already had — current clip index, blend target and blend weight:
 *
 * <pre>
 *   fsm.update(delta);
 *   currentAnim = fsm.currentIndex();
 *   blendTarget = fsm.blendTarget();
 *   blendWeight = fsm.blendWeight();
 *   time        = fsm.localTime();     // already wrapped, or clamped for a terminal clip
 * </pre>
 *
 * so the existing {@link AnimationBlend} crossfade and the single instanced draw call stay exactly
 * as they are. Nothing is allocated per frame.
 */
public final class PlayerAnimationFsm
        extends DefaultStateMachine<PlayerAnimationFsm, DynamicAnimState> {

    /** Crossfade length used until the user moves the fade slider. */
    public static final float DEFAULT_FADE_SECONDS = 0.18f;

    /** Longest accepted crossfade — the panel's slider ends here. */
    public static final float MAX_FADE_SECONDS = 0.6f;

    /** How long a rejected transition keeps the target node flashing red, in seconds. */
    public static final float REJECT_FLASH_SECONDS = 0.35f;

    /** Name of the state a finished one-shot returns to by default. */
    public static final String IDLE_NAME = "idle";

    /** One state node per loaded animation, in the player's animation order. */
    private final DynamicAnimState[] states;

    /** Rule in force per state (the inferred one unless a live override replaced it). */
    private final AnimationRule[] rules;

    /** Inferred rule per state, so {@link #clearRuleOverrides()} can restore it. */
    private final AnimationRule[] inferredRules;

    /** Clip length per state in seconds ({@code 0} = unknown), for completion detection. */
    private final float[] durations;

    /** Playback clock per state; survives a crossfade so re-entering {@code walk} keeps its phase. */
    private final float[] localTimes;

    /** Crossfade time for the next transition, in seconds ({@code 0} = hard cut). */
    private float fadeSeconds = DEFAULT_FADE_SECONDS;

    /** When {@code true}, every rule is ignored and any state may interrupt any other. */
    private boolean forceBlend;

    /** The state being faded <b>out</b>, or {@code null} while no fade runs. */
    private DynamicAnimState outgoing;

    /** Crossfade progress in {@code 0..1} ({@code 0} = outgoing, {@code 1} = current). */
    private float crossfade = 1f;

    /** Fade length of the running crossfade (kept even if the slider moves mid-fade). */
    private float activeFadeSeconds;

    /** {@code true} until the incoming clip is restarted, i.e. until the fade commits. */
    private boolean restartPending;

    /** Index of the last state that was refused while blocked, or {@code -1}. */
    private int lastRejectedIndex = -1;

    /** Remaining flash time for {@link #lastRejectedIndex}, in seconds. */
    private float rejectFlash;

    /** Where a finished one-shot returns to ({@code -1} = to idle). */
    private int returnIndex = -1;

    /** True while the clip clocks are frozen. */
    private boolean paused;

    /** Global playback speed multiplier ({@code 1} = authored speed). */
    private float timeScale = 1f;

    // ------------------------------------------------------------------ per-frame

    /**
     * Advances the running crossfade and the clip clocks, and completes a finished one-shot action.
     * Call it once per frame, before reading the values the renderer needs.
     *
     * <p>The incoming clip's clock is <b>not</b> restarted when the crossfade begins; that happens
     * when the fade {@link #commit() commits}, so the incoming pose is sampled from its first frame
     * for the whole fade and then plays on from there. While {@link #setPaused(boolean) paused} the
     * clocks stand still, which is what makes the {@code +1 Frame} step visible.</p>
     *
     * @param delta seconds since the last frame (negative values are ignored)
     */
    public void update(float delta) {
        if (delta > 0f) {
            if (rejectFlash > 0f) rejectFlash = Math.max(0f, rejectFlash - delta);
            advanceClocks(paused ? 0f : delta * Math.max(0f, timeScale));
            advanceCrossfade(delta);
        }
        completeFinishedAction();
    }

    /** Advances every clock that is on screen: the incoming clip and the outgoing one. */
    private void advanceClocks(float step) {
        if (step <= 0f) return;
        int index = currentIndex();
        if (index >= 0) localTimes[index] += step;
        if (outgoing != null) localTimes[outgoing.index()] += step;
    }

    /** Moves the crossfade towards 1 and commits the transition when it arrives. */
    private void advanceCrossfade(float delta) {
        if (outgoing == null) return;
        if (activeFadeSeconds <= 0f) {
            commit();
            return;
        }
        crossfade += delta / activeFadeSeconds;
        if (crossfade >= 1f) commit();
    }

    /**
     * Finishes a one-shot action whose clip reached its end: the machine returns to the state it
     * interrupted (or {@code idle}). A terminal state never leaves by itself.
     */
    private void completeFinishedAction() {
        DynamicAnimState state = currentState;
        if (state == null || paused) return;
        int index = state.index();
        if (!rules[index].isAction()) return;
        float duration = durations[index];
        // Without authored timing there is no end to detect; the viewer can step or force it.
        if (duration <= 1e-6f) return;
        if (localTimes[index] < duration) return;
        requestState(returnIndex >= 0 ? returnIndex : idleIndex(), true);
        returnIndex = -1;
    }

    /**
     * Advances the current clip by exactly one frame while paused — the {@code +1 Frame} button.
     *
     * @return {@code true} when the clock moved ({@code false} while the machine is playing)
     */
    public boolean stepOneFrame() {
        if (!paused) return false;
        advanceClocks(1f / 60f);
        completeFinishedAction();
        return true;
    }

    // ------------------------------------------------------------------ transitions

    /**
     * Asks the machine to move to state {@code targetIndex} — the single entry point for panel
     * buttons, graph clicks and hotkeys.
     *
     * <p>A request blocked by the current state's rule is refused: the index is remembered in
     * {@link #lastRejectedIndex()} and its flash timer starts, but the machine keeps playing.</p>
     *
     * @param targetIndex index into the loaded animation list
     * @return {@code true} when the transition started
     */
    public boolean requestState(int targetIndex) {
        return requestState(targetIndex, false);
    }

    /**
     * Same as {@link #requestState(int)}, with an option to bypass the rule check.
     *
     * @param targetIndex index into the loaded animation list
     * @param internal    {@code true} for a machine-driven transition (finished action, reset),
     *                    which is never recorded as a user rejection
     * @return {@code true} when the transition started
     */
    public boolean requestState(int targetIndex, boolean internal) {
        if (targetIndex < 0 || targetIndex >= states.length) {
            if (!internal) reject(targetIndex);
            return false;
        }
        if (!internal && !isTransitionAllowed(targetIndex)) {
            reject(targetIndex);
            return false;
        }
        if (targetIndex == currentIndex()) {
            // Re-playing the state on screen is a no-op: it keeps its phase (walk stays walk).
            if (!internal) clearReject();
            return true;
        }
        changeState(states[targetIndex]);
        return true;
    }

    /**
     * Starts the crossfade to {@code next}, or a hard cut when the fade time is {@code 0}.
     *
     * <p>Overrides gdx-ai's switch so the bookkeeping happens <b>before</b> the machine's current
     * state changes: the outgoing node, the fade length and the clock reset are captured here, then
     * the super call performs the switch and the {@code exit}/{@code enter} hooks.</p>
     */
    @Override
    public void changeState(DynamicAnimState next) {
        if (next == null || next == currentState) return;
        DynamicAnimState from = currentState;

        // Remember where a one-shot should return to, before the switch replaces the current state.
        if (from != null && rules[from.index()].loops()) {
            returnIndex = from.index();
        }

        boolean fade = fadeSeconds > 0f && from != null;
        outgoing = fade ? from : null;
        crossfade = fade ? 0f : 1f;
        activeFadeSeconds = fade ? fadeSeconds : 0f;
        restartPending = true;

        super.changeState(next);

        if (!fade) {
            // Hard cut: nothing to fade from, so the new clip starts immediately.
            localTimes[next.index()] = 0f;
            restartPending = false;
        }
        clearReject();
    }

    /**
     * Ends the running crossfade: the incoming clip becomes the only one on screen and restarts
     * from its first frame.
     *
     * <p>{@link #update(float)} commits automatically when the fade completes; this method exists
     * for the panel's "commit now" action and for tests that jump to the end of a transition.</p>
     */
    public void commit() {
        if (outgoing == null) return;
        outgoing = null;
        crossfade = 1f;
        activeFadeSeconds = 0f;
        int index = currentIndex();
        if (index >= 0 && restartPending) {
            localTimes[index] = 0f;
            restartPending = false;
        }
    }

    /**
     * Whether the current state's rule lets the machine go to {@code targetIndex} right now.
     *
     * <ul>
     *   <li>the current state itself: allowed (it is a no-op);</li>
     *   <li>a <b>terminal</b> state: nothing is allowed unless {@link #isForceBlend()};</li>
     *   <li>a <b>one-shot action</b>: another action or a non-locomotion loop ({@code defend},
     *       {@code rest}) may take over, but never {@code walk}/{@code run}/{@code idle} before the
     *       clip finished;</li>
     *   <li>a <b>looping</b> state: everything.</li>
     * </ul>
     *
     * @param targetIndex index into the loaded animation list
     * @return {@code true} when {@link #requestState(int)} would start the transition
     */
    public boolean isTransitionAllowed(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= states.length) return false;
        DynamicAnimState state = currentState;
        if (state == null) return true;
        if (targetIndex == state.index()) return true;
        if (forceBlend) return true;
        AnimationRule rule = rules[state.index()];
        if (rule.isTerminal()) return false;
        if (!rule.isAction()) return true;
        // A one-shot action is running: locomotion waits, another action may interrupt it.
        return !isLocomotion(targetIndex);
    }

    /**
     * Fills {@code out} with {@link #isTransitionAllowed(int)} for every state.
     *
     * @param out reusable array of at least {@link #stateCount()} entries, or {@code null}
     * @return the array that was filled (never {@code null})
     */
    public boolean[] allowedTargets(boolean[] out) {
        boolean[] target = out != null && out.length >= states.length
                ? out : new boolean[states.length];
        for (int i = 0; i < states.length; i++) target[i] = isTransitionAllowed(i);
        return target;
    }

    /** Records a blocked request: the node flashes red for {@link #REJECT_FLASH_SECONDS}. */
    private void reject(int targetIndex) {
        lastRejectedIndex = targetIndex;
        rejectFlash = REJECT_FLASH_SECONDS;
    }

    private void clearReject() {
        lastRejectedIndex = -1;
        rejectFlash = 0f;
    }

    /** True for the states an action blocks: {@code idle}, {@code walk}, {@code run}, {@code sprint}. */
    private boolean isLocomotion(int index) {
        String key = AnimationRule.normalize(states[index].name());
        return key.equals(IDLE_NAME) || key.equals("walk") || key.equals("run")
                || key.equals("sprint");
    }

    /** Index of the {@code idle} state, or the machine's start state. */
    private int idleIndex() {
        for (int i = 0; i < states.length; i++) {
            if (AnimationRule.normalize(states[i].name()).equals(IDLE_NAME)) return i;
        }
        return startIndex();
    }

    /**
     * Returns the machine to its initial state with every clock at zero and no crossfade — the
     * <b>Reset</b> action, and the way out of a terminal state without forcing a blend.
     */
    public void reset() {
        Arrays.fill(localTimes, 0f);
        outgoing = null;
        crossfade = 1f;
        activeFadeSeconds = 0f;
        restartPending = false;
        returnIndex = -1;
        clearReject();
        changeState(states[startIndex()]);
        // changeState is a no-op when the start state is already current; make sure the clock is 0.
        dynamicLocalTimeReset();
    }

    /**
     * Makes the machine show {@code targetIndex} <b>without asking its rules</b>: a hard cut, no
     * crossfade, with that clip's clock restarted from zero.
     *
     * <p>This is the door for the player's <em>manual</em> animation selection (Prev/Next, the panel's
     * animation list, hotkeys). Those widgets move {@code currentAnim} directly — they are not
     * transitions and must never flash red — but the graph has to follow, otherwise it keeps
     * highlighting a node that is no longer on screen and a click on the visible clip turns into a
     * silent no-op (the machine would think it is already there).</p>
     *
     * <p>The rule that <em>is</em> in force stays untouched: auto-return bookkeeping starts fresh
     * from here, exactly as it would after a user transition.</p>
     *
     * @param targetIndex index into the loaded animation list; out of range is ignored
     * @return {@code true} when the machine now shows {@code targetIndex}
     */
    public boolean follow(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= states.length) return false;
        returnIndex = -1;
        clearReject();
        if (targetIndex != currentIndex()) changeState(states[targetIndex]);
        // changeState starts a crossfade when the fade time is not 0; a manual pick is a hard cut.
        outgoing = null;
        crossfade = 1f;
        activeFadeSeconds = 0f;
        restartPending = false;
        localTimes[targetIndex] = 0f;
        return true;
    }

    /** Ensures the current clock really is at zero after {@link #reset()}. */
    private void dynamicLocalTimeReset() {
        int index = currentIndex();
        if (index >= 0) localTimes[index] = 0f;
    }

    // ------------------------------------------------------------------ values for the player

    /** Index of the state being faded <b>in</b>, or {@code -1} when nothing is loaded. */
    public int currentIndex() {
        return currentState != null ? currentState.index() : -1;
    }

    /** The state being faded in — the machine's current gdx-ai state, or {@code null}. */
    public DynamicAnimState current() {
        return currentState;
    }

    /** The state being faded out, or {@code null} when no crossfade runs. */
    public DynamicAnimState outgoing() {
        return outgoing;
    }

    /** What {@code MeshPlayer.blendTarget} must be: the outgoing index, or {@code -1} when idle. */
    public int blendTarget() {
        return outgoing != null ? outgoing.index() : -1;
    }

    /** What {@code MeshPlayer.blendWeight} must be: the crossfade progress ({@code 0..1}). */
    public float blendWeight() {
        return outgoing != null ? crossfade : 0f;
    }

    /** Crossfade progress in {@code 0..1} ({@code 0} = outgoing, {@code 1} = incoming). */
    public float crossfadeProgress() {
        return outgoing != null ? crossfade : 1f;
    }

    /** True while a crossfade is running. */
    public boolean isCrossfading() {
        return outgoing != null;
    }

    /**
     * Playback time of the current clip in seconds — what {@code MeshPlayer.time} must be. Looping
     * clips are wrapped into one cycle, one-shot and terminal clips are clamped to the last frame.
     */
    public float localTime() {
        return localTime(currentIndex());
    }

    /**
     * Playback time of state {@code index}, wrapped or clamped by its rule.
     *
     * @param index index into the loaded animation list
     */
    public float localTime(int index) {
        if (index < 0 || index >= states.length) return 0f;
        float t = localTimes[index];
        float duration = durations[index];
        if (duration <= 1e-6f) return t;
        if (rules[index].loops()) {
            // Wrap into [0, duration): a looping clip never holds its last frame.
            t = t % duration;
            return t < 0f ? t + duration : t;
        }
        // One-shot and terminal clips clamp: the final pose is held instead of restarting.
        return Math.min(t, duration);
    }

    /** Length of the current clip in seconds ({@code 0} when the export carries no timing). */
    public float currentDuration() {
        return durations[currentIndex()];
    }

    /** {@code localTime / duration} of the current clip in {@code 0..1} ({@code 0} without timing). */
    public float clipProgress() {
        int index = currentIndex();
        if (index < 0 || durations[index] <= 1e-6f) return 0f;
        return Math.min(1f, Math.max(0f, localTimes[index] / durations[index]));
    }

    /** All state nodes, in animation order (treat as read-only). */
    public DynamicAnimState[] states() {
        return states;
    }

    /** Number of states (i.e. animations in the loaded file). */
    public int stateCount() {
        return states.length;
    }

    /**
     * The node at {@code index}.
     *
     * @param index index into the loaded animation list
     * @return the node, or {@code null} when out of range
     */
    public DynamicAnimState state(int index) {
        return index >= 0 && index < states.length ? states[index] : null;
    }

    /**
     * The rule currently <b>in force</b> for state {@code index} — the live override if there is
     * one, otherwise the inferred rule. This is what the graph and the tooltips display.
     *
     * @param index index into the loaded animation list
     */
    public AnimationRule rule(int index) {
        return index >= 0 && index < rules.length ? rules[index] : AnimationRule.LOOPING;
    }


    // ------------------------------------------------------------------ configuration

    /** Crossfade length in seconds used for the next transition. */
    public float fadeSeconds() {
        return fadeSeconds;
    }

    /** Sets the crossfade length for the <b>next</b> transition; a running fade keeps its length. */
    public void setFadeSeconds(float seconds) {
        this.fadeSeconds = Math.max(0f, Math.min(MAX_FADE_SECONDS, seconds));
    }

    /** True when every rule is bypassed (the panel's "Force Blend"). */
    public boolean isForceBlend() {
        return forceBlend;
    }

    /** Bypasses (or re-enables) every rule: any state may then interrupt any other. */
    public void setForceBlend(boolean forceBlend) {
        this.forceBlend = forceBlend;
    }

    /** True while the clip clocks are frozen ({@code Pause}). */
    public boolean isPaused() {
        return paused;
    }

    /** Freezes (or resumes) the clip clocks; the graph keeps animating. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Playback speed multiplier ({@code 0..2}, {@code 1} = authored speed). */
    public float timeScale() {
        return timeScale;
    }

    /** Sets the playback speed multiplier, clamped to {@code 0..2}. */
    public void setTimeScale(float scale) {
        this.timeScale = Math.max(0f, Math.min(2f, scale));
    }

    /**
     * Overrides the rule of one state at runtime (live UI editing).
     *
     * @param index index into the animation list
     * @param rule  the rule to use from now on ({@code null} restores the inferred rule)
     */
    public void setRuleOverride(int index, AnimationRule rule) {
        if (index < 0 || index >= rules.length) return;
        rules[index] = rule != null ? rule : inferredRules[index];
        states[index].ruleOverride(rule);
    }

    /** Drops every live override, restoring the inferred rules. */
    public void clearRuleOverrides() {
        for (int i = 0; i < rules.length; i++) {
            rules[i] = inferredRules[i];
            states[i].ruleOverride(null);
        }
    }

    /** True when state {@code index} uses a rule the user set by hand. */
    public boolean hasRuleOverride(int index) {
        return index >= 0 && index < rules.length && rules[index] != inferredRules[index];
    }

    // ------------------------------------------------------------------ feedback queries

    /** Index of the last blocked request while it still flashes, else {@code -1}. */
    public int lastRejectedIndex() {
        return rejectFlash > 0f ? lastRejectedIndex : -1;
    }

    /** Remaining flash time of the rejected node in seconds ({@code 0} when nothing flashes). */
    public float rejectFlash() {
        return rejectFlash;
    }

    /** {@code 0..1} intensity of the rejected node's red flash. */
    public float rejectFlashAlpha() {
        return Math.max(0f, Math.min(1f, rejectFlash / REJECT_FLASH_SECONDS));
    }

    @Override
    public String toString() {
        DynamicAnimState state = currentState;
        String name = state != null ? state.name() : "<none>";
        if (outgoing == null) return name + " (" + stateCount() + " states)";
        return name + " \u2190 " + outgoing.name() + " " + Math.round(crossfade * 100f) + "%";
    }

    // ------------------------------------------------------------------ construction

    /**
     * Builds the machine for the animations of one loaded file and starts it in the {@code idle}
     * state (or the first looping state, or the first state at all).
     *
     * @param names animation names in the player's order
     * @param clips the baked clips, same order/length as {@code names}; an entry may be
     *              {@code null} for an animation that failed to bake
     * @throws IllegalArgumentException when {@code names} is empty
     */
    public PlayerAnimationFsm(List<String> names, List<VatAnimationData> clips) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("The FSM needs at least one animation.");
        }
        int count = names.size();
        this.states = new DynamicAnimState[count];
        this.rules = new AnimationRule[count];
        this.inferredRules = new AnimationRule[count];
        this.durations = new float[count];
        this.localTimes = new float[count];

        for (int i = 0; i < count; i++) {
            VatAnimationData clip = clips != null && i < clips.size() ? clips.get(i) : null;
            AnimationRule rule = AnimationRule.infer(names.get(i), clip);
            rules[i] = rule;
            inferredRules[i] = rule;
            durations[i] = clip != null ? clip.duration() : 0f;
            states[i] = new DynamicAnimState(i, names.get(i), rule, durations[i]);
        }
        this.owner = this;
        this.currentState = states[startIndex()];
        this.previousState = null;
    }

    /**
     * Convenience factory that reads the names and clips straight from the player's VAT batches.
     *
     * @param names  one animation name per batch, in animation order
     * @param groups the VAT batches; an entry may be {@code null}
     */
    public static PlayerAnimationFsm fromGroups(List<String> names, List<VatInstancedMesh> groups) {
        List<VatAnimationData> clips = new java.util.ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            VatInstancedMesh group = groups != null && i < groups.size() ? groups.get(i) : null;
            clips.add(group != null ? group.animation() : null);
        }
        return new PlayerAnimationFsm(names, clips);
    }

    /** The {@code idle} state, else the first looping state, else {@code 0}. */
    private int startIndex() {
        for (int i = 0; i < states.length; i++) {
            if (AnimationRule.normalize(states[i].name()).equals(IDLE_NAME)) return i;
        }
        for (int i = 0; i < states.length; i++) {
            if (inferredRules[i].loops()) return i;
        }
        return 0;
    }
}
