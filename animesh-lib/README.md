# animesh-lib

The Animesh **playback library**: load a 2D mesh export, bake an animation once, draw any number
of animated copies with a single instanced draw call.

* Published as `com.jb.rts:animesh-lib:<version>` (`animeshVersion` in the root
  `gradle.properties`, currently **1.0**).
* Depends on **libGDX core only** (`api com.badlogicgames.gdx:gdx`), which consumers therefore
  inherit automatically. No natives, no JSON library, no UI toolkit, no assets.
* Needs **Java 17+** and **OpenGL ES 3.0 / OpenGL 3.x** at runtime.

## What it does

```
mesh exports (.json)  ──►  MeshExportLoader  ──►  MeshExport      (poses, palette, bones, clips,
    or baked .vat                                            texture + UVs, attachment mask)
                                                   │
                       VatSkinBaker / VatPoseBaker ┤ (bake ONCE per animation)
                                                   ▼
                                     VatMeshData + VatAnimationData
                                                   │
                                        VatInstancedMesh  (upload once)
                                                   ▼
                        begin() → add(x, y, frame) … → render(camera)   = 1 draw call
```

## Usage

```java
// 1 — load the export (JSON) or a pre-baked .vat file
MeshExport export = MeshExportLoader.load(Gdx.files.internal("meshes/fire-export.json"));
//    or: VatFile.FileContent baked = VatFile.loadAll(Gdx.files.internal("meshes/fire.vat"));

// 2 — bake the animation you want to play (once, at load time)
BlendableMesh bindPose = export.meshes.get(0);          // rest pose, shared topology
MeshSkinClip clip      = export.skinClips.get(0);       // e.g. "idle"
VatSkinBaker.Result baked =
        VatSkinBaker.bake(export.name + "_" + clip.name, bindPose, export.skin, clip, export.palette);

// 3 — upload and draw: every copy shares ONE mesh and ONE bone texture
VatInstancedMesh batch = new VatInstancedMesh(baked.mesh(), baked.animation(), 10_000);
batch.begin();
for (Unit unit : units) {
    batch.add(unit.x, unit.y, baked.animation().frameAt(unit.lifeTime));  // fractional frame
}
batch.render(camera.combined);   // glDrawArraysInstanced — one call for all units
```

Exports **without** a bone table are baked from their poses with `VatPoseBaker` instead; textured
exports pass the picture to the batch:
`new VatInstancedMesh(mesh, animation, maxInstances, texture)`.

### Frame timing

`VatAnimationData.frameAt(seconds)` maps playback time onto the export's own keyframe times
(`"frameTimes"`), so a clip plays exactly as fast as it does in the editor.
`wrapFrame(frame)` loops a fractional frame index without interpolating from the last pose back
to the first.

### Memory ownership

| object | who disposes it |
|--------|-----------------|
| `VatInstancedMesh` (VBO, VAO, shader, bone/colour/step textures) | **you** — `dispose()` |
| the model `Texture` passed to the constructor | **you** — one picture serves every batch of the export |
| `MeshExport`, `VatMeshData`, `VatAnimationData` | nobody — plain Java data, garbage collected |

Never create these objects inside `render()`: bake and upload once, then only call
`begin()` / `add(...)` / `render(...)` per frame.

## Packages

| package | content |
|---------|---------|
| `com.jb.rts.mesh` | `MetricUnit` — the export's `measurement` unit (mm/cm/m/km ⇄ metres) |
| `com.jb.rts.mesh.blend` | export loading (`MeshExportLoader`, `SkinExportParser`), the model (`MeshExport`, `BlendableMesh`, `ColorPalette`, `MeshSkin`, `MeshSkinClip`), and the classic CPU pose blenders (`MeshBlender`, `MeshBlenderGroup`, `InstancedMeshBlenderGroup`) |
| `com.jb.rts.mesh.vat` | the GPU pipeline: `VatFormat`, `VatMeshData`, `VatAnimationData`, `VatSkinBaker`, `VatPoseBaker`, `VatVertexOffsetBaker`, `VatAnimationBlender`, `VatInstancedMesh`, `VatFile` (+ the textures `VatBoneTexture`, `VatFrameColorTexture`, `VatStepTexture`, `VatFrameStepTexture`) |

## Build & publish

```bash
./gradlew :animesh-lib:build                # jar, sources-jar, javadoc-jar + tests
./gradlew :animesh-lib:test                 # tests only (GL-free, no window)
./gradlew :animesh-lib:publishToMavenLocal  # -> ~/.m2/repository/com/jb/rts/animesh-lib/
```

Artifacts: `animesh-lib/build/libs/animesh-lib-<version>.jar` (plus `-sources` and `-javadoc`).
CI publishes the same artifacts to GitHub Packages for `v*` tags.

## Tests

`src/test/java` covers the loader, the palette model, the skin/pose bakers, the VAT formats and
the binary `.vat` container. The real-export tests read `../test-assets/meshes/*` (shipped in the
repository, not in the library jar — the player bundles them for its example selector); they are
skipped only if that directory is missing.

All of them are GL-free. The one behaviour that only a GPU can verify — a palette edit reaching the
uploaded vertex buffer (`VatInstancedMesh.refreshPalette`) — is covered by the opt-in live-GL test
`VatPaletteGlTest` in the `:player` module (`./gradlew :player:test -Panimesh.gl=true`).

See [`docs/MESH_BLEND.md`](docs/MESH_BLEND.md) and [`docs/VAT_INSTANCING.md`](docs/VAT_INSTANCING.md)
for the export format and the pipeline internals.
