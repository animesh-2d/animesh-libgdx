com.jb.rts.mesh.blend — Documentation

Overview
--------
This document describes the `com.jb.rts.mesh.blend` package of the **animesh-lib** module: its
classes, usage patterns, the mesh export file format it consumes, and its operational
requirements.

Colour model (important)
------------------------
Colours are **indexes**, not per-frame RGBA.

* A `ColorPalette` holds the actual colours (named, RGBA in `0..1`).
* Each triangle-corner references a colour by integer index into the palette.
* The palette is **mutable** — replace a colour via `ColorPalette.setColor(...)`, then call
  `refreshPalette()` on the renderer. Colours are resolved to per-vertex RGBA when the GPU VBO
  is built, so a palette edit needs only a cheap VBO rebuild.
* Poses are blended on **position only**. All poses of a mesh family share the same colour
  indexes, so frame 1 always uses the same colours as frame 2 — colours are never blended
  over time.
* Gouraud shading *inside* a triangle is preserved: if the three corners carry different
  colours, the GPU interpolates between them.

Because a shared source vertex may carry a different colour index in different triangles,
`MeshExportLoader` **expands** the mesh so every triangle-corner becomes its own vertex
with its own colour index.

Quick start
-----------
1. Build the jar:   `./gradlew :animesh-lib:build`
2. Run the tests:   `./gradlew :animesh-lib:test`
3. Play a mesh:     `./gradlew :player:run` (bundled example export)

Depend on it from any Gradle/Maven project (after `publishToMavenLocal` or from GitHub Packages):

```groovy
dependencies {
    implementation 'com.jb.rts:animesh-lib:1.0'
}
```

```java
MeshBlendTable  table  = new MeshBlendTable();
MeshExport      export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
MeshBlenderGroup group = new MeshBlenderGroup(table, export.name, export.palette);

group.setBlend(new MeshBlendCommand("tree_redwood_idle_0", 0.6f, "tree_redwood_idle_1", 0.4f));
group.render(camera.combined);

export.palette.setColor(1, 0.1f, 0.9f, 0.2f, 1f); // replace colour index 1
group.refreshPalette();
```

Module highlights
-----------------
* `ColorPalette` — indexed, mutable colour palette (max 64). `fromJson(JsonValue)`.
* `MeshExportLoader` — loads a mesheditor export JSON into a `MeshExport` (palette + all poses).
  Bones are ignored.
* `MeshExport` — bundle: `name`, `palette`, `meshes` (all poses), `sharedVertexCount`.
* `BlendableMesh` — one pose: `positions[]` (x,y), `colorIndices[]`, `indices[]`.
* `MeshBlendTable` — registry; `loadExport(FileHandle)`, `byPrefix(...)`, etc.
* `MeshBlender` — single mesh, up to 4 blended poses in one draw call.
* `MeshBlenderGroup` — pre-built static VBO for a whole pose family, uniform-only updates.
* `InstancedMeshBlenderGroup` — many instances of a family in one draw call (needs GL 3.x).
* `MeshBlendCommand` — up to 4 pose names + weights.
* `MeshBlendInstance` — per-instance slots/weights/position.
* `MeshAnimationClip` — an animation as a playable timeline (keyframe times, looping, easing).
* `MeshBlendAnimator` — plays/crossfades clips and writes a reusable `MeshBlendCommand`.
* `UnitEntityBehaviour` — gdx-ai `DefaultStateMachine` (idle/walk/attack/die) on top of the animator.
  See `MESH_ANIMATION.md`.

Input file format (mesheditor export JSON)
------------------------------------------
The format produced by `JsonMeshExporter` (see `beispiel.json` and
`assets/meshes/tree_redwood-export.json`). Bones are ignored.

```json
{
  "name": "tree_redwood",
  "colors":  [ { "name": "fur", "red": 151, "green": 95, "blue": 49, "alpha": 1 } ],
  "vertices":[ { "x": 0.0, "y": 0.0, "z": 0.0 }, ... ],
  "objects": [ { "name": "head", "triangles": [
      { "vertices": [60,61,62], "colors": [2,2,2] } ] } ],
  "animations":[ { "name": "walk", "looping": true, "fps": 30,
      "frameTimes": [ 0, 0.027875, 0.05575 ],
      "attachmentSteps": [ false, false, false ],
      "frames": [ { "name": "...", "offsets": [ { "x":..., "y":..., "z":... } ] } ] } ]
}
```

* `colors[].red/green/blue` are `0..255`, `alpha` is `0..1`.
* `vertices` is the shared position list — the mesh's **default pose**. Every vertex carries `x`,
  `y` and `z` (z = depth/sort coordinate, defaults to `0` when absent).
* `objects[].triangles[].vertices` = 3 vertex indexes (into the shared list);
  `objects[].triangles[].colors` = 3 colour indexes.
* Every animation frame's `offsets` is the same-length list of **increments over the default pose**
  (`x`, `y`, `z`): `absoluteVertex[i] = vertices[i] + offsets[i]`. Export files before version 2
  wrote absolute per-frame `vertices` instead — those still parse, as do frames without any
  position data (they equal the default pose).
* Each animation may also carry `fps` (default `30`), `frameTimes` (seconds per authored frame) and
  `attachmentSteps` (one boolean per baked frame: frames whose attachment state changes and which
  therefore must never be blended). `MeshExportLoader` exposes them as `animationFps`,
  `animationFrameTimes` and `animationStepFrames`; `MeshAnimationClip` plays exactly on
  `frameTimes` and honours the step mask (see `MESH_ANIMATION.md`).
* `yUp` (optional, default `true`) records the y orientation: the mesheditor's exporter negates
  the editor's y-down values (cartesian y-up) and writes `"yUp": true`; only exports that kept
  the y-down screen space write `"yUp": false`. Loaders leave the coordinates untouched and
  expose the flag as `MeshExport.yUp`; the renderer must mirror y **exactly once** for y-down
  files and must not touch y-up files. Note that all poses of one export share the *same*
  `defaultPositions` array, so a mirror must flip that array once, not once per pose.
* `textureFile` + `uvVertices` (optional, export **version 6**) describe a painted model:
  the bare picture file name (written next to the export by `JsonMeshExporter#export`) and one
  normalised `{u, v}` pair per **shared** vertex (`-1` = unset). `MeshExport#uvs` exposes them
  expanded per triangle corner (`MeshExport#hasTexture()`), which the VAT renderer samples
  instead of the palette colours. The CPU blend renderers ignore both fields.

Naming
------
Each pose is named `<name>_<poseName>_<poseIndex>` (e.g. `tree_redwood_idle_0`). All poses of one
export share the same `<name>` prefix, so `MeshBlenderGroup(table, <name>, palette)` groups them.

Notes for agents / gotchas
--------------------------
* Only poses of the *same* mesh (same prefix, identical expanded topology) may be blended.
  `BlendableMesh.sameTopology(...)` enforces this.
* GPU shaders are deliberately kept in simple GLSL 1.20 style (attributes + varying, per-vertex
  colour resolved from the palette at VBO build time) for maximum driver compatibility.
* The export's `measurement` (e.g. `"mm"`) is exposed as `MeshExport.measurement`; unspecified
  metrics default to `"mm"`.
* `InstancedMeshBlenderGroup` needs `Gdx.gl30` (OpenGL ES 3.0).
* Adding a NEW dependency requires a one-time network Gradle build; afterwards `--offline` works.
* If Gradle hits the ChecksumService lock error, clear locks in `~/.gradle` and the project
  `.gradle`, or build with `--project-cache-dir /tmp/pgc`.

Change log
----------
* 2026-08-14 — migrated from the legacy `.mesh.json` (per-vertex RGBA) format to the mesheditor
  **export** format: indexed colours (`ColorPalette`), pose-only blending, no colour blending
  over time, per-triangle-corner colours via vertex expansion.
* 2026-08-15 — GPU shaders simplified to the proven per-vertex-colour pattern (palette colours
  resolved to RGBA at VBO build time); palette edits refresh via VBO rebuild; VAO-backed VBO on
  GL3+ so drawing works on core profiles.
* 2026-08-16 — expose `MeshExport.measurement` (default `"mm"`); `MeshPlayer` now renders with
  a correct aspect ratio (window aspect == camera viewport aspect, object bounding box fitted).
* 2026-11-24 — animation control: `MeshExport.animationFrameTimes` / `animationFps` keep the
  export's own keyframe times and `fps`; `MeshAnimationClip`, `MeshBlendAnimator` and
  `UnitEntityBehaviour` (gdx-ai `DefaultStateMachine`, bundled) play and crossfade them without
  allocating per frame. See `MESH_ANIMATION.md`.
