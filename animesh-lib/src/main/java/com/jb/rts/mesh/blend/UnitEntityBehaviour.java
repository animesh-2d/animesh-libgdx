package com.jb.rts.mesh.blend;

import com.badlogic.gdx.ai.fsm.DefaultStateMachine;
import com.badlogic.gdx.ai.fsm.State;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.utils.ObjectMap;

import java.util.Map;

/**
 * Example <b>gdx-ai state machine</b> for a unit that is drawn by the pose-blend pipeline: it plays
 * the right {@link MeshAnimationClip} on a {@link MeshBlendAnimator} for every state
 * ({@code IDLE}, {@code WALK}, {@code ATTACK}, {@code DIE}) and crossfades between them.
 *
 * <p>It is deliberately small and self-contained — copy it as the starting point for a game's own
 * unit behaviour instead of extending it. Everything a unit needs to provide is in
 * {@link UnitEntity}; the behaviour itself only maps states to clips and fade times.
 *
 * <h3>Transitions</h3>
 * <table border="1">
 *   <caption>State, clip and fade</caption>
 *   <tr><th>state</th><th>clip</th><th>fade</th><th>leaves when</th></tr>
 *   <tr><td>{@code IDLE}</td><td>{@code "idle"} (loops)</td><td>{@code 0.2 s}</td>
 *       <td>{@link UnitEntity#isMoving()} → {@code WALK} or {@link UnitEntity#isAttacking()} →
 *           {@code ATTACK}</td></tr>
 *   <tr><td>{@code WALK}</td><td>{@code "walk"} (loops)</td><td>{@code 0.2 s}</td>
 *       <td>not moving → {@code IDLE}</td></tr>
 *   <tr><td>{@code ATTACK}</td><td>{@code "attack"} (once)</td><td>{@code 0.08 s}</td>
 *       <td>{@link MeshBlendAnimator#isFinished()} → previous non-attack state</td></tr>
 *   <tr><td>{@code DIE}</td><td>{@code "die"} (holds last pose)</td><td>{@code 0.08 s}</td>
 *       <td>never — a dead unit ignores every request</td></tr>
 * </table>
 *
 * <p>A fast fade ({@code 0.08 s}) for the short, snappy states (attack, death) and a calm fade
 * ({@code 0.2 s}) for the locomotion states keeps the walk↔idle blend soft without making a
 * hit feel laggy.
 *
 * <h3>Blocked interruptions</h3>
 * {@code DIE} is terminal. {@code ATTACK} is not interrupted by movement or a new attack
 * request — it plays out, and only then does the unit return to what it was doing
 * ({@link #previousState()}). Everything else follows {@link #update()}, so call
 * {@link #update(float)} exactly once per frame, <b>before</b> drawing.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips); // Map<String, MeshAnimationClip>
 * behaviour.update(delta);                  // once per frame
 * group.setBlend(behaviour.command());
 * }</pre>
 * The behaviour owns the animator (created in its constructor); it does not own the clips, which
 * are shared by every unit (see {@link MeshAnimationClip#loadAllFromExport}).
 *
 * <h3>gdx-ai</h3>
 * The machine is a plain {@link DefaultStateMachine} with {@link UnitState} as its enum, so it can
 * be queried ({@link #state()}), driven manually ({@code changeState(...)}) or hooked into gdx-ai's
 * message dispatch later without touching this class. {@code gdx-ai} is bundled in the jar
 * ({@code implementation}, no natives), so consumers do not need to add it themselves unless they
 * use it directly.
 */
public final class UnitEntityBehaviour {

    /** Animation name played in the idle state. */
    public static final String CLIP_IDLE = "idle";

    /** Animation name played while walking. */
    public static final String CLIP_WALK = "walk";

    /** Animation name played for one attack. */
    public static final String CLIP_ATTACK = "attack";

    /** Animation name played on death. */
    public static final String CLIP_DIE = "die";

    /** Fade used when entering an attack or the death animation (short and snappy). */
    public static final float FADE_FAST = 0.08f;

    /** Fade used between the locomotion states (idle ↔ walk). */
    public static final float FADE_CALM = 0.2f;

    /** The unit this behaviour drives. */
    private final UnitEntity unit;

    /** Clip per {@link UnitState} (missing states keep their previous animation). */
    private final ObjectMap<UnitState, MeshAnimationClip> clipsByState;

    /** Writes the pose command for {@link #command()}; owned by this behaviour. */
    private final MeshBlendAnimator animator;

    /** The gdx-ai state machine; {@link UnitState} is both the enum and the {@link State}. */
    private final DefaultStateMachine<UnitEntity, UnitState> machine;

    /** State to return to once {@code ATTACK} has played out. */
    private UnitState previousState = UnitState.IDLE;

    /** {@code true} once {@code ATTACK} has been requested for the current attack. */
    private boolean attackQueued;

    /**
     * Everything a unit must offer to be driven by {@link UnitEntityBehaviour}. Implement it on
     * your own unit class; the behaviour only reads these flags.
     */
    public interface UnitEntity {

        /** {@code true} while the unit is walking (set by the game's movement code). */
        boolean isMoving();

        /** {@code true} while the unit wants to attack. */
        boolean isAttacking();

        /** {@code true} once the unit is dead; death is terminal. */
        boolean isDead();
    }

    /** States of a unit. Each constant is a gdx-ai {@link State} that delegates to the behaviour. */
    public enum UnitState implements State<UnitEntity> {
        /** Standing still, {@code "idle"} loops. */
        IDLE,
        /** Walking, {@code "walk"} loops. */
        WALK,
        /** Attacking once; the short fade makes the hit read as instant. */
        ATTACK,
        /** Dying once, holding the last pose; terminal. */
        DIE;

        @Override
        public void enter(UnitEntity entity) {
            // Transitions are decided by UnitEntityBehaviour, which owns the animator.
        }

        @Override
        public void update(UnitEntity entity) {
        }

        @Override
        public void exit(UnitEntity entity) {
        }

        @Override
        public boolean onMessage(UnitEntity entity, Telegram telegram) {
            return false;
        }
    }


    // =======================================================================
    // Construction
    // =======================================================================

    /**
     * Creates the behaviour and starts it in {@code IDLE}.
     *
     * @param unit  the unit to drive (not {@code null})
     * @param clips clips by animation name, as returned by
     *              {@link MeshAnimationClip#loadAllFromExport}; must contain at least one clip
     * @throws IllegalArgumentException when {@code unit} is {@code null}, {@code clips} is empty
     *                                  or the idle clip is missing
     */
    public UnitEntityBehaviour(UnitEntity unit, Map<String, MeshAnimationClip> clips) {
        this(unit, clips.get(CLIP_IDLE), clips.get(CLIP_WALK), clips.get(CLIP_ATTACK),
            clips.get(CLIP_DIE));
    }

    /**
     * Creates the behaviour with explicit clips; every clip may be {@code null} (that state then
     * keeps the animation it came from).
     *
     * @param unit       the unit to drive (not {@code null})
     * @param idleClip   looping idle clip (required)
     * @param walkClip   looping walk clip
     * @param attackClip one-shot attack clip
     * @param dieClip    one-shot death clip that holds its last pose
     * @throws IllegalArgumentException when {@code unit} or {@code idleClip} is {@code null}
     */
    public UnitEntityBehaviour(UnitEntity unit, MeshAnimationClip idleClip,
                               MeshAnimationClip walkClip, MeshAnimationClip attackClip,
                               MeshAnimationClip dieClip) {
        if (unit == null) {
            throw new IllegalArgumentException("UnitEntityBehaviour needs a unit.");
        }
        if (idleClip == null) {
            throw new IllegalArgumentException(
                "The idle clip is required (export animation '" + CLIP_IDLE + "' is missing).");
        }
        this.unit = unit;
        this.clipsByState = new ObjectMap<>();
        putClip(UnitState.IDLE, idleClip);
        putClip(UnitState.WALK, walkClip);
        putClip(UnitState.ATTACK, attackClip);
        putClip(UnitState.DIE, dieClip);
        this.animator = new MeshBlendAnimator(idleClip);
        this.machine = new DefaultStateMachine<>(unit, UnitState.IDLE);
    }

    private void putClip(UnitState state, MeshAnimationClip clip) {
        if (clip != null) {
            clipsByState.put(state, clip);
        }
    }
    // =======================================================================
    // Per-frame update
    // =======================================================================

    /**
     * Advances the state machine and the animator by {@code delta} seconds. Call once per frame,
     * before drawing, then read {@link #command()}.
     *
     * @param delta seconds since the last frame
     */
    public void update(float delta) {
        if (state() != UnitState.DIE && unit.isDead()) {
            changeState(UnitState.DIE);
        }
        switch (state()) {
            case IDLE:
                if (unit.isAttacking()) {
                    beginAttack();
                } else if (unit.isMoving()) {
                    changeState(UnitState.WALK);
                }
                break;
            case WALK:
                if (unit.isAttacking()) {
                    beginAttack();
                } else if (!unit.isMoving()) {
                    changeState(UnitState.IDLE);
                }
                break;
            case ATTACK:
                // Attack is not interruptible: it plays to its last pose, then hands control back.
                if (animator.isFinished()) {
                    attackQueued = false;
                    changeState(previousState == UnitState.WALK && !unit.isMoving()
                        ? UnitState.IDLE : previousState);
                }
                break;
            case DIE:
            default:
                // Terminal — a dead unit plays its death animation once and keeps the last pose.
                break;
        }
        animator.update(delta);
    }

    /** Enters {@code ATTACK}, remembering where to return to and always restarting the clip. */
    private void beginAttack() {
        if (!clipsByState.containsKey(UnitState.ATTACK)) {
            return; // no attack animation exported: stay in the current state
        }
        if (!attackQueued) {
            previousState = state() == UnitState.WALK ? UnitState.WALK : UnitState.IDLE;
            attackQueued = true;
        }
        if (state() == UnitState.ATTACK) {
            return; // already attacking: a second request does not restart the clip
        }
        changeState(UnitState.ATTACK);
    }

    /**
     * Switches to {@code next}, starting its clip with the state's fade time. A state without a
     * clip is skipped (its animation would not change anyway), except {@code DIE}, which simply
     * holds the pose that is on screen.
     */
    private void changeState(UnitState next) {
        MeshAnimationClip clip = clipsByState.get(next);
        machine.changeState(next);
        if (clip == null) {
            return;
        }
        switch (next) {
            case ATTACK:
            case DIE:
                animator.play(clip, FADE_FAST, true);
                break;
            default:
                // Locomotion keeps its phase when re-entering the same clip (play() is a no-op).
                animator.play(clip, FADE_CALM, false);
                break;
        }
    }
    // =======================================================================
    // Queries
    // =======================================================================

    /** The current state of the unit ({@link UnitState#IDLE} until the first update). */
    public UnitState state() {
        return machine.getCurrentState();
    }

    /** The gdx-ai state machine driving this unit (read-only use). */
    public DefaultStateMachine<UnitEntity, UnitState> machine() {
        return machine;
    }

    /** The animator playing the current state's clip (shared with {@link #command()}). */
    public MeshBlendAnimator animator() {
        return animator;
    }

    /** The unit this behaviour drives. */
    public UnitEntity unit() {
        return unit;
    }

    /**
     * The state {@code ATTACK} returns to once it has played out ({@link UnitState#IDLE} outside an
     * attack).
     */
    public UnitState previousState() {
        return previousState;
    }

    /**
     * The pose command of the current frame: hand it to
     * {@code MeshBlenderGroup#setBlend(...)}. Same instance every call (see
     * {@link MeshBlendAnimator#command()}).
     */
    public MeshBlendCommand command() {
        return animator.command();
    }

    @Override
    public String toString() {
        return "UnitEntityBehaviour{" + machine.getCurrentState() + ", " + animator + "}";
    }
}



