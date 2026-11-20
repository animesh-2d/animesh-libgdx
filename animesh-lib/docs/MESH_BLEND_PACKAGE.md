com.jb.rts.mesh.blend — 2D mesh pose-blending (indexed colours)

The `com.jb.rts.mesh.blend` package loads the 2D **mesheditor export** JSON files
(e.g. `assets/meshes/tree_redwood-export.json`, or `beispiel.json` at the repo root)
and blends animation *poses* together on the GPU.

Key ideas
---------
* **One export file → one mesh family + many poses.** A single export contains one shared
  topology (vertices, objects, triangles, colour indexes) plus the rest pose and every
  animation frame. Each pose becomes a `BlendableMesh`; the bundle is a `MeshExport`.
* **Colours are indexes, not per-frame RGBA.** Every triangle-corner carries a colour *index*
  into a shared `ColorPalette`. The palette is fully mutable, so the user can replace colours
  ("replace the colours in the indexes") at any time — colours are resolved to per-vertex RGBA
  at VBO build time and a palette edit only needs a cheap `refreshPalette()` VBO rebuild.
* **No colour blending over time.** Pose blending interpolates *positions* only. All poses of a
  mesh family share the same colour indexes, so frame 1 always uses the same colours as
  frame 2. Gouraud shading *within* a triangle is preserved (three different corner colours
  still produce a smooth gradient).
* **Per-triangle colours.** A shared vertex may have a different colour in different triangles.
  `MeshExportLoader` therefore *expands* the mesh so every triangle-corner is its own vertex.
* **Bones are ignored.**

Quick start
-----------
Load an export and build a blend group:

```java
MeshBlendTable  table  = new MeshBlendTable();
MeshExport      export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
MeshBlenderGroup group = new MeshBlenderGroup(table, export.name, export.palette);

group.setBlend(new MeshBlendCommand("tree_redwood_idle_0", 0.6f, "tree_redwood_idle_1", 0.4f));
group.render(camera.combined);

// Replace a colour (index 1) – instant, cheap:
export.palette.setColor(1, 0.1f, 0.9f, 0.2f, 1f);
group.refreshPalette();
```

Blend up to four poses of the same mesh (`MeshBlenderGroup`), a single mesh (`MeshBlender`),
or many instances in one draw call (`InstancedMeshBlenderGroup`).

See `MESH_BLEND.md` and `VAT_INSTANCING.md` in this folder for the detailed documentation, and
`../README.md` for how the two modules are built and published.
