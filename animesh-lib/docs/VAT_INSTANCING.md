# GPU skinning with Vertex Animation Textures (VAT) + hardware instancing

`com.jb.rts.mesh.vat` replaces the per-pose VBO of `com.jb.rts.mesh.blend` for animated crowds.
Instead of streaming vertex displacements (which burns one vertex attribute per pose and caps a
group at ~12 poses), **all bone matrices of an animation live in one `RGBA32F` texture** and the
skinning happens entirely in the vertex shader. The CPU never multiplies a matrix per object — it
only writes *position* + *fractional frame index* into a per-instance buffer.

Measured on the dev machine (1280×720, uncapped): **100 000 instances → 1 draw call, ~250 fps**
(`./gradlew :player:runVatDemo -Pvat.benchmark=true`).

```
                 mesheditor (.json)                    runtime (animesh-lib)
  Mesh + Animation ──VatExporter──► VatMeshData  ──►  static VBO (uploaded once)
                                 └► VatAnimationData ─► RGBA32F texture (uploaded once)
                                                          │
  entity list (x, y, frame) ──► instance VBO (10 floats/instance, streamed per frame)
                                                          │
                             glDrawArraysInstanced ───────┴──► N instances, 1 draw call
```

---

## 1. Bone matrix texture (`VatFormat`, `VatBoneTexture`)

| axis | meaning |
|------|---------|
| `x` (width)  | `frame * 4 + column` — four texels per matrix, one per matrix column |
| `y` (height) | bone row |
| format       | `GL_RGBA32F`, `NEAREST`, `CLAMP_TO_EDGE` (interpolation is done manually) |

A texel `(frame*4 + c, bone)` holds **column `c`** of that bone's 4×4 matrix, i.e. the same order as
`Matrix4.val` (column-major). The 16 floats of a matrix are contiguous:

```
[m00,m10,m20,m30,  m01,m11,m21,m31,  m02,m12,m22,m32,  m03,m13,m23,m33]
 └── column 0 ──┘   └── column 1 ──┘   └── column 2 ──┘   └── column 3 ──┘ (translation = m03/m13)
```

Flat index: `VatFormat.matrixFloatOffset(bone, frame, frameCount) = (bone*frameCount + frame)*16`.

For the 2D affine matrices this editor produces, column 2 is always `(0,0,1,0)`; the shader skips it
(18 vertex texture fetches per vertex instead of 24).

Width = `frameCount * 4` must not exceed `GL_MAX_TEXTURE_SIZE` (4096 → 1024 frames, 16384 → 4096).

## 2. Static vertex buffer (uploaded once)

`VatMeshData.toInterleavedVertices()` — 10 floats / 40 bytes per vertex, triangles are implicit
(`3t, 3t+1, 3t+2`), so no index buffer is needed:

| offset | attribute | usage | type |
|-------:|-----------|-------|------|
| 0  | `a_position`   | `Usage.Position`    | `vec3` float |
| 12 | `a_color`      | `Usage.ColorPacked` | 4 × `GL_UNSIGNED_BYTE`, normalized |
| 16 | `a_boneIds`    | `Usage.Generic`, unit 0 | `vec3` float (bone rows) |
| 28 | `a_weights`    | `Usage.Generic`, unit 1 | `vec3` float (sum = 1) |

Vertices are **expanded per triangle corner** (the editor stores colours per corner), exactly like
`MeshExportLoader`. Weight resolution keeps the three strongest influences and renormalizes them.
Vertices without any bone weight are bound to a synthetic `<bind-pose>` row whose matrices are
identity for every frame, so they stay in their bind pose instead of collapsing to the origin.

**Optional UV buffer.** A textured mesh (`MeshExport.uvs`, one `{u, v}` pair per expanded vertex)
uploads a *second* static VBO with `a_texcoord0` (2 floats, divisor 0). Untextured meshes never
create it, so their vertex footprint stays exactly 40 bytes.

## 3. Instance stream (per frame)

`VatInstancedMesh.add(...)` writes 10 floats (40 bytes) per instance:

| offset | contents | attribute |
|-------:|----------|-----------|
| 0  | `x, y`            | `a_instancePos` (vec2) |
| 8  | fractional frame  | `a_instanceFrame` (float, e.g. `4.5`) |
| 12 | rotation (radians)| `a_instanceRot` (float) |
| 16 | `scaleX, scaleY`  | `a_instanceScale` (vec2) |
| 24 | `r, g, b, a` tint | `a_instanceTint` (vec4) |

The buffer is uploaded with a single `glBufferSubData` per frame and drawn with
`glDrawArraysInstanced(GL_TRIANGLES, 0, vertexCount, instanceCount)`.

## 3a. Per-frame colour strip (colour blending on the GPU)

The mesheditor can override the colours of individual animation frames
(`animations[].frames[].colors`, e.g. fire flicker or a damage flash). The static VBO holds only
one colour per vertex, so those overrides live in an optional **`GL_RGBA8` texture** owned by
`VatFrameColorTexture` (built by `VatPalette.resolveFrames`):

| axis | meaning |
|------|---------|
| texel | `frame * vertexCount + vertex` (`VatFormat.frameColorTexel`) |
| `x`   | `texel % rowLength`, `rowLength = min(vertexCount, 2048)` |
| `y`   | `texel / rowLength` |
| format | `GL_RGBA8`, `NEAREST`, `CLAMP_TO_EDGE` — 4 bytes per vertex and frame |

The strip wraps at `VatFormat.COLOR_TEXTURE_MAX_WIDTH` texels, so a large mesh never exceeds
`GL_MAX_TEXTURE_SIZE` (the bone texture could not be larger anyway). The vertex shader fetches the
colours of frames `f0`/`f1` with `texelFetch` (row = `gl_VertexID`, no extra attribute needed
because the index buffer is trivial) and `mix()`es them with the same `t` it uses for the
positions — exactly the CPU blend renderer's `Σ colour(slot_i)·w_i / Σ w_i` for two slots.

The strip is `null`/`u_hasFrameColors = 0` when no frame deviates from the bind pose, so ordinary
meshes pay nothing at all. A binary `.vat` file does not carry the strip (yet) and falls back to
the static `a_color`; the mesheditor **JSON** export does.

### Colour changes are cross-faded, not snapped

The mesheditor stores colours **per keyframe** (palette indexes) and the exporter subdivides every
keyframe interval into many sampled frames for the bone rotation — the intermediate frames inherit
the *previous* keyframe's colours. A colour change would therefore land on a single frame boundary
and snap.

Colour *indexes* cannot be interpolated, so `VatPalette.resolveFrames` resolves them to RGBA first
and then rewrites each run of identical colours as
`mix(previousColour, newColour, k / runLength)` (`VatPalette.smoothColorChanges`):

* every run start keeps its authored keyframe colour exactly,
* a run of a single frame is left alone (sparse exports already blend in the shader),
* a colour that changes on every frame (flicker) keeps its hard per-frame switch.

For a subdivided export this reproduces exactly the cross-fade the CPU blend renderer produced
before the export was densified.

## 3b. Attachment step mask (`VatStepTexture`)

Attachment geometry (bones of type `ATTACHMENT` and their weighted vertices) is either **placed on
a slot** or **collapsed to size 0** in a frame. Blending those two states would make the attachment
grow/shrink during the transition, so attachment vertices must switch **instantly** at a keyframe.

The mesheditor writes the attachment's shared vertex indices as the JSON array `stepVertices`.
`MeshExportLoader` expands them to the per-corner layout and `VatMeshData` carries them into
`VatStepTexture`, a one-texel-per-vertex `GL_RGBA8` texture (red channel = 1 for attachment
vertices, same row folding as the colour strip, bound to **texture unit 2**):

| axis | meaning |
|------|---------|
| texel | `vertex` |
| `x`   | `vertex % rowLength`, `rowLength = min(vertexCount, 2048)` |
| `y`   | `vertex / rowLength` |
| format | `GL_RGBA8`, `NEAREST`, `CLAMP_TO_EDGE` |

The vertex shader samples it with `gl_VertexID` to identify attachment vertices. For those vertices it
also samples the optional **per-frame step texture** (`VatFrameStepTexture`, texture unit 3): a frame
is flagged when its effective attachment state differs from the previous frame. Only an interval
that ends at a flagged frame is snapped (`t = 0`, the attachment holds the previous frame); in every
other interval it interpolates normally, so an attached object follows a moving slot smoothly while
a placement/removal is still instantaneous. The exporter gives every in-between frame the state of
the interval's **start** keyframe for the same reason (`FrameSampler`).

A mesh without attachments does not create the per-vertex texture (`u_hasStep = 0`) and a clip
without state changes does not create the per-frame texture (`u_hasStepFrames = 0`); both pay
nothing. Both VAT bake paths use the masks: `VatSkinBaker.meshData(..., stepVertices)` /
`MeshSkinClip.stepFrames` for real skinning and `VatPoseBaker.bake(..., stepVertices, stepFrames)`
for the pose fallback.

**Mask contract:** "no attachments" is an **empty** mask (`MeshExport#stepVertices` is empty, never
`null`, and `VatMeshData.stepVertices()` returns an empty array in that case). `VatMeshData`
accepts `null` *and* an empty array; any other length than `0` or `vertexCount` is rejected. Only
treating the empty array as a mismatch made every MeshPlayer group of a normal (attachment-free)
export fail to build, so the player drew nothing at all (`VatMeshDataStepMaskTest`).

> **Binary `.vat` files** do not carry the step mask yet (the container format has no block for
> it); attachments exported through `VatExporter` therefore still blend between frames. Use the
> JSON export path (`MeshPlayer` / `MeshExportLoader`) for attachment switching.

## 3c. Model texture (optional)

A mesh painted with the editor's texture atlas (`ColorsPanel` → *Generate Texture*) stores the
picture name in `mesh.textureFile` and one normalised UV per vertex in `mesh.uvVertices`. The
JSON export (version 6) carries both as `"textureFile"` and `"uvVertices"`, and
`JsonMeshExporter#export` copies the picture next to the JSON, so the pair can be moved/loaded
from any directory.

Runtime:

| piece | where |
|-------|-------|
| UV attribute | `a_texcoord0` (vec2), separate static VBO, per expanded vertex |
| sampler | `u_texture` on **texture unit 4** |
| switch | `u_hasTexture` (`1.0` when the batch was built with a `Texture`) |
| owner | the caller (`MeshPlayer`) — all animation batches of one export share one `Texture` |

The vertex shader forwards `v_uv` and, when `u_hasTexture` is set, replaces the vertex colour with
the per-instance tint; the fragment shader samples the atlas and multiplies it by that tint, and
discards texels with `alpha <= 0.0039` (the atlas background). Blending
(`GL_SRC_ALPHA/GL_ONE_MINUS_SRC_ALPHA`) is enabled only for those draws. `u_hasTexture = 0`
restores the exact previous Gouraud behaviour, so untextured meshes are unaffected — including
their per-frame colour strip.

`MeshPlayer` loads the picture from the JSON's directory (`textureFile`), falls back to the corner
colours when it is missing, and logs one line per loaded texture. Reference artefact:
`assets/meshes/fire-export.json` + `assets/meshes/fire_texture.png`.

> `.vat` files do not carry UVs (the container has no block for them), so a texture cannot be
> restored from a baked file yet. Use the JSON export for textured meshes.

## 4. Vertex shader (`VatShaders`)

One body, two dialects — `#version 150` on desktop, `#version 300 es` on Android/iOS/WebGL
(`texelFetch` exists in both):

1. `a_instanceFrame` → `f0 = floor(f)`, `f1 = f0+1`, `t = fract(f)`; `mod` for looping clips,
   `clamp` otherwise.
2. Per influence (3×): fetch columns `0`, `1`, `3` for `f0` and `f1`, `mix` them with `t`, apply the
   affine 2D matrix to the bind-pose position and multiply by the weight
   (`x' = m00·x + m01·y + m03`, `y' = m10·x + m11·y + m13`).
3. Per instance: `scale → rotate → translate`, tint the colour, `z` passes through untouched
   (it is the editor's draw-order coordinate, never skinned).
4. Colour: `a_color`, or — when the animation carries a strip — `mix(frameColor(f0, gl_VertexID),
   frameColor(f1, gl_VertexID), t)`, then `× a_instanceTint`.
5. Texture (optional, §3c): with `u_hasTexture = 1` the colour above is replaced by
   `a_instanceTint` alone, `v_uv = a_texcoord0` is forwarded, and the fragment shader samples
   `u_texture` and discards fully transparent texels.

## 5. Authoring / export (mesheditor)

```java
Mesh mesh = MeshSingleton.getInstance().getMesh();
VatExportOptions options = new VatExportOptions();      // y-up, origin-relative, px

VatExporter.Prepared prepared = VatExporter.prepare(mesh, options);   // static mesh + bone table
for (Animation anim : mesh.animations) {
    VatExporter.Export result = VatExporter.bake(prepared, anim, options);
    if (!result.report().isExact(0.5f)) {
        LOG.warn("{}: {}", anim.name, result.report().summary());
    }
    VatFile.write(new FileHandle(new File(outDir, anim.name + ".vat")),
                  result.mesh(), result.animation());
}
```

The per-bone matrix is

```
M(bone, frame) = T(centre_frame) · R(angle_frame − angle_bind) · S(len_frame / len_bind) · T(−centre_bind)
```

which is exactly what `AnimationTransformUtil.deformVertices` does when a bone is dragged, so the
GPU reproduces the authored frames (verified by `VatExporter.validate`, which reconstructs every
frame on the CPU with the shader's maths).

## 6. Runtime loading

```java
VatFile.Content content = VatFile.load(Gdx.files.internal("meshes/dino_walk.vat"));
VatInstancedMesh batch = new VatInstancedMesh(content.mesh(), content.animation(), 200_000);

float baseFrame = content.animation().frameAt(stateTime);   // keyframe times are baked in
batch.begin();
for (Unit u : units) {
    batch.add(u.x, u.y, content.animation().wrapFrame(baseFrame + u.phase),
              u.facing, u.scale, u.scale, 1f, 1f, 1f, 1f);
}
batch.render(camera.combined);                              // ONE draw call
```

### Multi-clip container (`VAT2`)

`VatFile.writeAll(file, mesh, clips)` stores the static mesh **once** plus every animation
(`VatFile.loadAll` reads them back; version-1 single-clip files still load). `VatExportCli`
writes one `<mesh>.vat` per model this way.

### JSON exports (version 6)

`JsonMeshExporter` writes a `"skin"` block — bone table, bind transforms (from
`mesh.defaultPose`), expanded `boneIds`/`weights` (top-3, normalized) and `sourceVertex` — plus
per-frame `boneMatrices` and `frameTimes`/`looping`. `MeshExportLoader` parses it into
`MeshSkin` + `MeshSkinClip`, and `VatSkinBaker` repacks the 6-float matrices into the
16-float texture layout (no trigonometry at runtime). `MeshPlayer` uses this path when the
export has bones and falls back to `VatPoseBaker` (one translation bone per vertex) otherwise.

**Version 5** adds the attachment data: the top-level `stepVertices` array is expanded
into the per-vertex step mask described in §3b, and every frame may carry an informational
`attachments` map (`slotId → attachment name`). The baked bones and vertices already
contain the resolved attachment state (placed on the slot or collapsed to size 0); both VAT bake
paths receive the step mask.

**Version 6** adds the model texture (§3c): the top-level `textureFile` (bare file name, next to
the JSON) and `uvVertices` (one `{u, v}` per **shared** vertex, `-1` = unset). The loader expands
them to the per-corner layout and both VAT bake paths (`VatSkinBaker.meshData(...)`,
`VatPoseBaker.bake(...)`) carry them into `VatMeshData.uvs()`. Exports without a texture are
unchanged apart from the version number.

### Baked frame density (rotations!)

The shader only blends the two **baked** frames around the current time. With two authored
keyframes 90° apart that blend is a straight line between the two poses — vertices would slide
along a chord instead of rotating around their bone. The exporters therefore subdivide every
keyframe interval so that no bone rotates more than `FrameSampler.MAX_BONE_STEP_DEGREES` (8°):

* root-bone centres, lengths and scales are interpolated linearly,
* directions are interpolated by **angle** (shortest path), so the bone really rotates,
* a child bone's centre keeps its offset in the parent's interpolated frame, so a rotating chain
  swings around its parent instead of sliding along the chord,
* the vertex snapshot of an intermediate frame is deformed from the bind pose with those
  interpolated bones (`AnimationTransformUtil` maths), so validation and the legacy blend
  renderer see the same pose as the GPU.

A 90° turn between two keyframes becomes 12 baked steps (13 frames, texture `52x26` for 26 bones)
instead of a single chord. Clips are capped at `FrameSampler.MAX_FRAMES` (1000) frames.

### Editor preview (same maths)

The animation panel displays interpolated poses through
`Position.createInterpolated(Mesh, pos1, pos2, weight)`, which uses the **same** bone maths as the
exporter: bone directions are blended by angle, child-bone centres follow their interpolated parent
(`AnimationTransformUtil.interpolateBoneTransforms`), and the skinned vertices are deformed from
`mesh.defaultPose` with those interpolated bones (`AnimationTransformUtil.deformFromDefaultPose`).
On top of that the editor adds the linearly blended *residual* between a keyframe's stored vertices
and the pose its bones describe, so keyframes with directly edited vertices are reproduced exactly
(a shader driven only by bone matrices cannot represent such residuals). The editor therefore shows
the arc, not the chord — previously it lerped bone directions and vertices component-wise, so a 90°
turn collapsed the bone to ~70.7 % of its length and the vertices slid along the chord while the
exported shader showed the correct rotation. Vertices without a bone weight (and meshes without a
captured default pose) keep the linear blend as a fallback. The 3-argument
`Position.createInterpolated(pos1, pos2, weight)` remains the geometry-only blend for callers that
have no mesh/skin context.

## 7. Limits & troubleshooting

* **OpenGL ES 3.0 / OpenGL 3.2 core required** (`Gdx.gl30 != null`) — instancing, vertex texture
  fetch and `RGBA32F`. The LWJGL3 launcher already requests `GLEmulation.GL30, 3, 3`.
* **The camera's near/far plane must cover the mesh `z`.** The shader forwards `a_position.z`
  (the editor's draw-order coordinate) unchanged, and exported `z` values are often tiny
  non-zero floats (e.g. `1.26e-5`). With an `OrthographicCamera` default `near = 0` those
  vertices land just outside the clip volume and their triangles vanish — the mesh appears as a
  few slivers. Set a symmetric range (e.g. `near = -zLimit`, `far = zLimit`, `MeshPlayer` and
  `VatDemoApplication` do this) or zero the `z` when baking if draw order is irrelevant.
* **Never create the geometry `Mesh` with the default constructor on GL3**: libgdx then uses a
  `VertexBufferObjectWithVAO`, and `Mesh.bind()` silently binds *its* VAO instead of the instanced
  one (nothing is drawn). Pass `Mesh.VertexDataType.VertexBufferObject` — `VatInstancedMesh` does.
  `VatInstancedMesh.logPipelineState()` prints enabled flags/size/stride/VBO per attribute for such
  cases.
* **Three influences per vertex.** Change `VatFormat.INFLUENCES` to 4 *and* the two vertex
  attributes, the shader `vec3` → `vec4` and the exporter's `MAX_INFLUENCES`.
* **Per-frame colour overrides are baked into a colour strip** (see §3a). Only the mesheditor
  **JSON** export carries them; a binary `.vat` file still stores one colour per vertex
  (`VatFile` has no colour block), so playing a `.vat` shows the bind-pose colours.
* **Frames edited without bones** (or produced by blending two poses) can deviate slightly; that is
  what `VatExporter.ValidationReport` reports (`maxError`/`meanError` in export units).
* Instances inside one call are not depth/alpha sorted — keep one VAT group per draw layer. The
  mesh `z` coordinate still works because it is passed through unchanged.
