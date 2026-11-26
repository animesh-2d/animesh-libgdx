package com.jb.rts.player;

import com.badlogic.gdx.ai.fsm.State;
import com.badlogic.gdx.ai.msg.Telegram;

/**
 * One <b>state node</b> of {@link PlayerAnimationFsm}: the animation clip at a fixed index of the
 * loaded file's animation list.
 *
 * <p>{@link PlayerAnimationFsm} creates one instance per loaded {@code Anim}, because the player
 * opens arbitrary exports at runtime — the state set is <em>data</em>, not a compile-time enum.
 * The node itself is a thin identity object (index, name, rule); every rule lives in the FSM, which
 * is also the machine's owner, so {@code enter}/{@code update}/{@code exit} simply delegate back to
 * it. That keeps the transition policy in exactly one place and lets the visualiser reason about
 * the whole graph without walking the nodes.</p>
 *
 * <p>Equality is by {@link #index()} and identity is stable for the lifetime of one loaded file, so
 * nodes can be used as map keys and compared with {@code ==}.
 */
public final class DynamicAnimState implements State<PlayerAnimationFsm> {

    private final int index;
    private final String name;
    private final AnimationRule rule;
    /** Rule the user set by hand in the live UI, or {@code null} while the inferred rule is used. */
    private AnimationRule override;
    /** Clip length in seconds ({@code duration()} of the baked animation); {@code 0} if unknown. */
    private final float duration;

    /**
     * @param index    position of the animation in the player's animation list
     * @param name     the animation name as the export wrote it
     * @param rule     inferred playback rule
     * @param duration clip length in seconds (time of the last keyframe), or {@code 0}
     */
    public DynamicAnimState(int index, String name, AnimationRule rule, float duration) {
        if (rule == null) throw new IllegalArgumentException("rule cannot be null.");
        this.index = index;
        this.name = name != null ? name : "";
        this.rule = rule;
        this.duration = Math.max(0f, duration);
    }

    /** Position of this animation in the player's animation list. */
    public int index() {
        return index;
    }

    /** The animation's name, e.g. {@code "walk"}. */
    public String name() {
        return name;
    }

    /** The inferred playback rule ({@link AnimationRule}). */
    public AnimationRule rule() {
        return rule;
    }

    /**
     * The rule in force: the live override when the user set one, otherwise the inferred rule.
     * This is what the graph and the tooltips display.
     */
    public AnimationRule effectiveRule() {
        return override != null ? override : rule;
    }

    /**
     * Sets (or clears) the live override of this node.
     *
     * @param rule the rule the user chose, or {@code null} to fall back to the inferred one
     */
    public void ruleOverride(AnimationRule rule) {
        this.override = rule;
    }

    /** The clip's authored length in seconds ({@code 0} when the export carries no timing). */
    public float duration() {
        return duration;
    }

    // ---------------------------------------------------------------- gdx-ai State

    /**
     * The FSM performs all work in {@link PlayerAnimationFsm#changeState}, before gdx-ai switches
     * the node, so entering a state has nothing left to do.
     */
    @Override
    public void enter(PlayerAnimationFsm fsm) {
        // Handled by PlayerAnimationFsm#changeState (it decides the crossfade before the switch).
    }

    /** Per-frame work (completion detection) is driven by {@link PlayerAnimationFsm#update(float)}. */
    @Override
    public void update(PlayerAnimationFsm fsm) {
        // See PlayerAnimationFsm#update(float).
    }

    @Override
    public void exit(PlayerAnimationFsm fsm) {
        // Nothing to release: the node holds no resources.
    }

    /**
     * Messages are not used yet. Returning {@code false} leaves the event unhandled, which is what
     * gdx-ai's dispatcher expects from a machine that does not subscribe to anything.
     */
    @Override
    public boolean onMessage(PlayerAnimationFsm fsm, Telegram telegram) {
        return false;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DynamicAnimState)) return false;
        return index == ((DynamicAnimState) other).index;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return name + " #" + index + " [" + rule.label() + "]";
    }
}
