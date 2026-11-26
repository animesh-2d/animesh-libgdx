com.jb.rts.mesh.blend — Animation control (`MeshAnimationClip`, `MeshBlendAnimator`, `UnitEntityBehaviour`)

This document is the black-box reference for the **animation controller** of the CPU pose-blend
pipeline. You do not need to read the sources: everything needed to play an export's animations and
to drive a unit from a gdx-ai state machine is described here.

Pipeline position
-----------------
```
MeshExport (export JSON)  ──►  MeshAnimationClip      (timeline: keyframes, times, easing)
                                     │
                                     ▼
                              MeshBlendAnimator        (which clips are visible, at what weight)
                                     │  command()
                                     ▼
                       MeshBlenderGroup.setBlend(cmd)  ──►  render(camera)
```
`MeshAnimationClip` knows *time inside one animation*; `MeshBlendAnimator` knows *which animations
are visible right now*; `MeshBlenderGroup` only knows *how to draw a pose mix*. All three are
zero-allocation per frame: after construction nothing is allocated in `update()`.

### Keyframe timing (`MeshExport.animationFrameTimes` / `animationFps`)

`MeshExportLoader` stores, per animation, the keyframe times in seconds (`animationFrameTimes`,
aligned 1:1 with the kept poses) and the declared `"fps"` (`animationFps`). `MeshAnimationClip`
uses exactly these times, so a clip plays as fast as it does in the editor. Zero-length keyframe
intervals are merged the same way the loader merges poses (the later pose wins), so clip keyframes
always line up with pose names `"<exportName>_<animName>_<keptIndex>"`.

`MeshAnimationClip.totalDuration` is the **start time of the last keyframe**, matching
`MeshSkinClip.duration()` and the VAT timeline.

Quick start
-----------
```java
MeshExport export = MeshExportLoader.load(Gdx.files.internal("meshes/dino-export.json"));

// Build every clip of the export at once (the Set lists the animations that loop)
Map<String, MeshAnimationClip> clips =
        MeshAnimationClip.loadAllFromExport(export, Set.of("idle", "walk", "run"));

MeshBlendAnimator animator = new MeshBlendAnimator(clips.get("idle"));
animator.play(clips.get("walk"), 0.2f);      // crossfade over 0.2 s (restarts walk)

// per frame
animator.update(Gdx.graphics.getDeltaTime());
group.setBlend(animator.command());
group.render(camera.combined);
```

`MeshAnimationClip`
-------------------
| member | meaning |
|--------|---------|
| `clipName` | animation name (e.g. `"walk"`) |
| `frames` | `Array<MeshKeyFrame>`, ordered by `startTime` |
| `totalDuration` | seconds; start time of the last keyframe |
| `frameDuration` | nominal `1/fps` of the animation (metadata) |
| `looping` | whether `sample` wraps |
| `loopPose()` | pose the clip wraps to (first keyframe's pose) |
| `fromExport(export, animName, looping)` | build one clip (linear easing) |
| `fromExport(export, animName, looping, Interpolation)` | same, with easing for ordinary frames |
| `loadAllFromExport(export)` | build every animation, none looping |
| `loadAllFromExport(export, Set<String> loopingNames)` | build every animation, listed ones loop |
| `loadAllFromExport(export, loopingNames, Map<String, Interpolation>)` | per-animation easing |
| `applyEasing(Interpolation, overrideExisting)` | set easing on ordinary keyframes (chainable) |
| `sample(stateTime, SampleResult)` | fill a reusable result (`poseA`, `poseB`, `alpha`) |
| `poseAt(stateTime, scratch)` | the dominant pose name |
| `at(index)` / `at(index, alpha, out)` | direct keyframe access |
| `indexOf(t)` | keyframe interval containing `t` (binary search) |

`MeshKeyFrame` holds `poseName`, `startTime`, `duration`, `stepFrame` and a mutable
`interpolation` (written by `applyEasing`). `endTime()` is `startTime + duration`.
`loadAllFromExport` skips an animation whose poses cannot be paired with its times, so one broken
animation never prevents the others from loading.

### Sampling rules

* `sample` maps `stateTime` to the keyframe interval and returns the two bracketing poses plus the
  blend factor `alpha` (`0` = `poseA`, `1` = `poseB`). A keyframe starts on its own pose at full
  weight (`alpha = 0`).
* **Looping** clips wrap `stateTime` into `[0, totalDuration)`; negative times wrap too.
* **Non-looping** clips clamp: before `0` they show the first pose, at or after `totalDuration` the
  last pose, which is held forever.
* **Step frames** (`MeshKeyFrame.stepFrame`, from the export's `attachmentSteps`) are never blended:
  * a step keyframe holds its pose at `alpha = 0` for its whole interval, then snaps to the next
    pose at exactly that pose's `startTime`;
  * when the *next* keyframe is a step frame, the interval snaps to it once the raw progress
    reaches `0.5`.

`SampleResult` is a plain mutable holder (`poseA`, `poseB`, `alpha`, `frameIndexA/B`) that you
allocate **once** and pass in; `sample` returns that same instance.

`MeshBlendAnimator`
-------------------
| member | meaning |
|--------|---------|
| `MeshBlendAnimator(clip)` | starts playing `clip` immediately |
| `play(clip, fadeDuration)` | crossfade to `clip`, **always restarting** it (clip triggers) |
| `play(clip, fadeDuration, forceRestart)` | `false` is a no-op when `clip` is already current |
| `update(delta)` | advance and rewrite `command()`; negative deltas are ignored |
| `command()` | the reusable `MeshBlendCommand` for `setBlend(...)` |
| `currentClip()` / `outgoingClip()` | incoming clip / clip being faded out (`null` when idle) |
| `stateTime()` / `fade()` | clip-local time / crossfade progress (`0` = old, `1` = new) |
| `isCrossfading()` | a fade is running |
| `isFinished()` | a non-looping current clip reached its last pose |

### Slot layout of the command

`MeshBlendCommand` has 4 slots (`names` / `weights` arrays, rewritten in place):

| situation | slot 0/1 | slot 2/3 |
|-----------|----------|----------|
| normal playback | the current clip's two poses (`1-alpha`, `alpha`) | unused |
| crossfade | the outgoing clip's poses, normalised to 1 and scaled by `1 - fade` | the incoming clip's poses, scaled by `fade` |

A side with weight `0` is left out, so `activeSlots()` is 2 right after a fade starts (only the old
clip is visible) and 4 while it runs. Weights always sum to `1` (`totalWeight()`).

### Snap instead of crossfade

The crossfade is replaced by a hard cut when

* `fadeDuration <= 0`, or
* the outgoing or the incoming clip currently shows a **step frame** — attachment geometry must never
  be stretched between two poses.

### Rapid interruptions

A new `play` during a running fade keeps the progress already reached instead of jumping back to
`0`, so a third clip arriving mid-fade does not make the pose pop.

`UnitEntityBehaviour` (gdx-ai state machine)
--------------------------------------------
`UnitEntityBehaviour` is a ready-made gdx-ai `DefaultStateMachine` integration. Implement the tiny
`UnitEntity` interface on your unit class and let the behaviour choose the clip:

```java
class MyUnit implements UnitEntityBehaviour.UnitEntity {
    boolean moving, attacking, dead;
    public boolean isMoving()    { return moving; }
    public boolean isAttacking() { return attacking; }
    public boolean isDead()      { return dead; }
}

Map<String, MeshAnimationClip> clips =
        MeshAnimationClip.loadAllFromExport(export, Set.of("idle", "walk"));
UnitEntityBehaviour behaviour = new UnitEntityBehaviour(unit, clips);

// per frame
behaviour.update(Gdx.graphics.getDeltaTime());
group.setBlend(behaviour.command());
group.render(camera.combined);
```

| state | clip (animation name) | fade | leaves when |
|-------|-----------------------|------|-------------|
| `IDLE` | `"idle"` (loops) | – | moving → `WALK`, attacking → `ATTACK`, dead → `DIE` |
| `WALK` | `"walk"` (loops) | `FADE_CALM` | not moving → `IDLE`, attacking → `ATTACK`, dead → `DIE` |
| `ATTACK` | `"attack"` (one-shot) | `FADE_FAST` | clip finished → `previousState()` |
| `DIE` | `"die"` (one-shot) | `FADE_FAST` | never (terminal, holds the last pose) |

* `FADE_CALM = 0.2 s` for locomotion, `FADE_FAST = 0.08 s` for attack and death.
* **Attack is not interruptible**: a repeated `isAttacking()` does not restart the clip; it plays to
  its last pose and then returns to the state it started from (`previousState()`), except that a
  return to `WALK` becomes `IDLE` when the unit stopped moving meanwhile. Death outranks attack —
  `isDead()` always wins on the next update.
* A missing animation is tolerated: without `"attack"` the unit simply stays in its current state;
  a missing `"walk"` or `"die"` keeps the previous animation (only `"idle"` is required, it is
  rejected in the constructor). `CLIP_IDLE`…`CLIP_DIE` hold the animation names.
* `state()`, `machine()`, `animator()`, `previousState()`, `command()` and `unit()` are exposed if
  you need to react to a transition yourself. `UnitState` implements gdx-ai's `State<UnitEntity>`,
  so messages can be routed to it later without changing the class.

Operational requirements
------------------------
* **gdx-ai is bundled** into the `animesh-lib` jar as an `implementation` dependency
  (`gdxAiVersion` in the root `gradle.properties`, currently `1.8.2`). It is pure Java — no natives —
  so consumers need no extra artifact unless they also use gdx-ai directly (then declare it
  themselves).
* Everything above is plain Java + libGDX types and is **GL-free**: it is unit-testable without a
  window (see `MeshAnimationClipTest`, `MeshBlendAnimatorTest`, `UnitEntityBehaviourTest`).
* Call `update()` once per frame in your update step, never from `render()`, and hand the resulting
  `command()` to the renderer.

See also
--------
* `MESH_BLEND.md` — export format and the CPU pose blenders.
* `VAT_INSTANCING.md` — the GPU instancing pipeline (for thousands of copies).
