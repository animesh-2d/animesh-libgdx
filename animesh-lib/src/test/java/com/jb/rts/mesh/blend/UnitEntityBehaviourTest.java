package com.jb.rts.mesh.blend;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protects {@link UnitEntityBehaviour}: the gdx-ai state machine transitions, the fade times per
 * transition, the non-interruptible attack and the terminal death state.
 */
class UnitEntityBehaviourTest {

    /** Four zero offsets (the export's shared vertex count) for one animation frame. */
    private static final String Z = """
            [ { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 },
              { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 } ]""";

    /**
     * A unit animation set at 4 fps on a 0.25 s grid: {@code idle} and {@code walk} loop,
     * {@code attack} and {@code die} are one-shot (0.5 s long, so two frames of playback).
     */
    private static MeshExport unitExport(boolean withAttack) {
        String attack = withAttack
            ? """
              ,{ "name": "attack", "looping": false, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                 "frames": [ { "name": "a", "offsets": %s }, { "name": "b", "offsets": %s },
                             { "name": "c", "offsets": %s } ] }
              """.formatted(Z, Z, Z)
            : "";
        String json = """
                {
                  "name": "unit",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1.0 } ],
                  "vertices": [
                    { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 1.0, "y": 0.0, "z": 0.0 },
                    { "x": 0.0, "y": 1.0, "z": 0.0 }, { "x": 1.0, "y": 1.0, "z": 0.0 }
                  ],
                  "objects": [ { "name": "o", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                    { "vertices": [0, 2, 3], "colors": [0, 0, 0] } ] } ],
                  "animations": [
                    { "name": "idle", "looping": true, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [ { "name": "a", "offsets": %1$s }, { "name": "b", "offsets": %1$s },
                                  { "name": "c", "offsets": %1$s } ] },
                    { "name": "walk", "looping": true, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [ { "name": "a", "offsets": %1$s }, { "name": "b", "offsets": %1$s },
                                  { "name": "c", "offsets": %1$s } ] },
                    { "name": "die", "looping": false, "fps": 4, "frameTimes": [0.0, 0.25, 0.5],
                      "frames": [ { "name": "a", "offsets": %1$s }, { "name": "b", "offsets": %1$s },
                                  { "name": "c", "offsets": %1$s } ] }
                    %2$s
                  ]
                }
                """.formatted(Z, attack);
        return MeshExportLoader.loadFromString(json);
    }

    /** A scriptable {@link UnitEntityBehaviour.UnitEntity} for the tests. */
    private static final class TestUnit implements UnitEntityBehaviour.UnitEntity {
        boolean moving;
        boolean attacking;
        boolean dead;

        @Override
        public boolean isMoving() {
            return moving;
        }

        @Override
        public boolean isAttacking() {
            return attacking;
        }

        @Override
        public boolean isDead() {
            return dead;
        }
    }

    private static Map<String, MeshAnimationClip> clips(boolean withAttack) {
        return MeshAnimationClip.loadAllFromExport(unitExport(withAttack));
    }

    @Test
    void startsIdleAndRejectsMissingInput() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));

        assertEquals(UnitEntityBehaviour.UnitState.IDLE, behaviour.state());
        assertEquals("unit_idle_0", behaviour.command().names[0]);
        assertSame(unit, behaviour.unit());
        assertNotNull(behaviour.machine(), "the behaviour is backed by a real gdx-ai machine");

        assertThrows(IllegalArgumentException.class, () -> new UnitEntityBehaviour(null, clips(true)));
    }

    @Test
    void idleToWalkUsesTheCalmFade() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));

        unit.moving = true;
        behaviour.update(0.01f);

        assertEquals(UnitEntityBehaviour.UnitState.WALK, behaviour.state());
        assertTrue(behaviour.animator().isCrossfading(), "locomotion blends softly");
        assertEquals("unit_walk_0", behaviour.animator().currentClip().loopPose());
    }

    @Test
    void walkToIdleUsesTheCalmFadeAndCompletes() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));
        unit.moving = true;
        behaviour.update(0.01f);

        unit.moving = false;
        behaviour.update(UnitEntityBehaviour.FADE_CALM + 0.01f);

        assertEquals(UnitEntityBehaviour.UnitState.IDLE, behaviour.state());
        assertFalse(behaviour.animator().isCrossfading());
        assertEquals("unit_idle_0", behaviour.command().names[0]);
    }

    @Test
    void attackPlaysOnceAndReturnsToThePreviousState() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));
        unit.moving = true;
        behaviour.update(0.01f); // -> WALK

        unit.attacking = true;
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.ATTACK, behaviour.state());
        assertEquals(UnitEntityBehaviour.UnitState.WALK, behaviour.previousState(),
            "walking units return to WALK");
        assertEquals("unit_attack_0", behaviour.animator().currentClip().loopPose());

        // The attack clip must not be interrupted by a repeated attack request.
        behaviour.update(0.05f);
        assertEquals(UnitEntityBehaviour.UnitState.ATTACK, behaviour.state());
        assertFalse(behaviour.animator().isFinished());

        // It plays to its last pose and hands control back.
        behaviour.update(1f);
        assertEquals(UnitEntityBehaviour.UnitState.ATTACK, behaviour.state(),
            "the switch happens on the next update");
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.WALK, behaviour.state());
    }

    @Test
    void attackTwiceRestartsTheClipAndReturnsToIdleWhenRemainingStill() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));

        unit.attacking = true;
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.ATTACK, behaviour.state());
        assertEquals(UnitEntityBehaviour.UnitState.IDLE, behaviour.previousState());

        // Attack finishes while the unit does not move -> back to IDLE.
        behaviour.update(1f);
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.IDLE, behaviour.state());

        // A second attack must replay the clip from its first pose (the frame's own delta is the
        // only time that has advanced).
        unit.attacking = false;
        behaviour.update(0.01f);
        unit.attacking = true;
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.ATTACK, behaviour.state());
        assertEquals(0.01f, behaviour.animator().stateTime(), 1e-6f, "the clip restarted");
        assertFalse(behaviour.animator().isFinished());
    }

    @Test
    void attackIsSkippedWhenTheExportHasNoAttackClip() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(false));

        unit.attacking = true;
        behaviour.update(0.01f);

        assertEquals(UnitEntityBehaviour.UnitState.IDLE, behaviour.state(),
            "no attack animation: stay where we are");
        assertEquals("unit_idle_0", behaviour.command().names[0]);
    }

    @Test
    void deathIsTerminalAndHoldsTheLastPose() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));

        unit.dead = true;
        behaviour.update(0.01f);
        assertEquals(UnitEntityBehaviour.UnitState.DIE, behaviour.state());

        // Even a moving / attacking unit stays dead.
        unit.moving = true;
        unit.attacking = true;
        behaviour.update(1f);
        behaviour.update(1f);
        assertEquals(UnitEntityBehaviour.UnitState.DIE, behaviour.state());
        assertTrue(behaviour.animator().isFinished(), "the death clip played out");
        assertEquals("unit_die_2", behaviour.command().names[0], "the last pose is held");
    }

    @Test
    void commandIsTheAnimatorCommand() {
        TestUnit unit = new TestUnit();
        UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips(true));

        assertSame(behaviour.animator().command(), behaviour.command());
        assertEquals(1f, behaviour.command().totalWeight(), 1e-6f);
    }
}
