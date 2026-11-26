package com.jb.rts.mesh.blend;

import java.util.Collections;
import java.util.List;

/**
 * The result of loading one mesheditor export file.
 *
 * <p>A single mesheditor export (e.g. {@code tree_redwood-export.json}) holds one shared topology
 * and one {@link ColorPalette}, plus any number of animation <em>poses</em>. {@link MeshExport}
 * bundles all of that together:
 * <ul>
 *   <li>{@link #palette} — the indexed colours; mutable so the user can replace colours.</li>
 *   <li>{@link #meshes} — one {@link BlendableMesh} per pose (rest pose + every animation frame),
 *       all sharing the identical topology so they can be blended by {@link MeshBlenderGroup}.</li>
 *   <li>{@link #name} — the mesh name (used as the group prefix).</li>
 *   <li>{@link #textureFile} / {@link #uvs} — optional model texture and its per-vertex
 *       coordinates (export version 6). Exports without them keep the per-corner palette
 *       colours and pay nothing.</li>
 * </ul>
 *
 * <p>Bones are deliberately <b>ignored</b> – they are irrelevant to the image-space blend.
 */
public final class MeshExport {

    /** Mesh name from the JSON (used as the blend-group prefix). */
    public final String name;

    /**
     * Measurement unit of the coordinates as declared by the exporter (e.g. {@code "mm"}).
     * Defaults to {@code "mm"} when the export does not declare a metric.
     */
    public final String measurement;

    /**
     * Orientation of the y coordinates: {@code true} when they are cartesian (positive y points
     * <b>up</b>), {@code false} when the file kept the mesheditor's y-down screen space.
     *
     * <p>The exporter writes this as the top-level {@code "yUp"} flag
     * ({@code JsonExportOptions.cartesianYUp}, on by default). Exports without the flag are
     * assumed to be y-up — see {@link MeshExportLoader} for the exact fallback. A y-down export
     * has to be mirrored once on y before it is drawn in a y-up world (e.g. by
     * {@code MeshPlayer}); a y-up export must be used <b>as-is</b>.
     */
    public final boolean yUp;

    /** The indexed colour palette (mutable). */
    public final ColorPalette palette;

    /** All poses of the mesh; every entry shares an identical topology (unmodifiable). */
    public final List<BlendableMesh> meshes;

    /**
     * Skinning data of the export (bone table + resolved per-vertex influences), or {@code null}
     * when the export carries no bones/weights. See {@link MeshSkin}.
     */
    public final MeshSkin skin;

    /** One baked matrix clip per animation (empty when {@link #skin} is {@code null}). */
    public final List<MeshSkinClip> skinClips;

    /** Per animation name, the per-frame attachment step flags (empty when none). */
    public final java.util.Map<String, boolean[]> animationStepFrames;

    /**
     * Per animation name, the <b>deduplicated</b> keyframe times in seconds of the poses that were
     * kept on {@link #meshes}, aligned 1:1 with {@link BlendableMesh#poseIndex} (empty when the
     * export has no animations with frames).
     *
     * <p>Zero-length keyframe intervals were already resolved by {@link MeshExportLoader} (the
     * later pose wins), so {@code animationFrameTimes.get(anim).length} equals the number of
     * {@code BlendableMesh} poses whose {@link BlendableMesh#poseName()} is {@code anim}. This is
     * exactly what {@link MeshAnimationClip#fromExport} needs to build a clip without touching the
     * JSON again.
     */
    public final java.util.Map<String, float[]> animationFrameTimes;

    /**
     * Per animation name, the nominal frame rate declared by the export ({@code "fps"}, default
     * {@link SkinExportParser#DEFAULT_FPS}). It is pure metadata — {@link #animationFrameTimes} is
     * authoritative for playback — but it lets clip tooling describe an animation before its first
     * keyframe.
     */
    public final java.util.Map<String, Float> animationFps;

    /** Number of vertices in the source JSON (before per-triangle expansion). */
    public final int sharedVertexCount;

    /**
     * Per <b>expanded</b> vertex: {@code true} when the vertex belongs to an
     * attachment and must <b>not</b> be blended between animation frames.
     *
     * <p>The mesheditor writes the attachment vertices as the top-level
     * {@code "stepVertices"} array of shared vertex indices. An attachment is
     * either fully placed on its slot or collapsed to size&nbsp;0 in a frame;
     * interpolating the two states would make it grow/shrink, so runtime
     * renderers snap the frame index for these vertices instead (see
     * {@code VatInstancedMesh}). Empty (never {@code null}) when the export has
     * no attachments — ordinary meshes pay nothing.
     */
    public final boolean[] stepVertices;

    /** True when {@code vertex} (expanded index) is an attachment vertex. */
    public boolean isStepVertex(int vertex) {
        return stepVertices != null && vertex >= 0 && vertex < stepVertices.length
                && stepVertices[vertex];
    }

    /** True when this export contains attachment geometry. */
    public boolean hasStepVertices() {
        if (stepVertices == null) return false;
        for (boolean step : stepVertices) {
            if (step) return true;
        }
        return false;
    }

    /**
     * Number of floats per vertex in {@link #uvs}: one {@code u} and one {@code v} component.
     */
    public static final int UV_FLOATS = 2;

    /**
     * Number of triangles that were <b>skipped</b> while loading because they referenced
     * vertex indexes outside the export's {@code vertices} array. Such triangles cannot
     * contribute any geometry; the mesheditor's own renderer ignores them as well. A non-zero
     * value therefore points at a damaged source mesh and should be reported to the user.
     */
    public final int skippedTriangles;

    /**
     * Model texture of the export — the bare file name the mesheditor wrote
     * ({@code "textureFile"}, version 6), resolved relative to the export file. {@code null}
     * when the mesh has none: such exports are drawn with their per-corner palette colours.
     */
    public final String textureFile;

    /**
     * Per <b>expanded</b> vertex normalised texture coordinate ({@value #UV_FLOATS} floats,
     * {@code u, v}), or an empty array when the export carries no UV data. The mesheditor keeps
     * UVs per <em>shared</em> vertex; {@link MeshExportLoader} expands them through the same
     * triangle-corner mapping as the positions, so renderers can index them with the vertex
     * they already draw. Unset coordinates are {@code -1} (implementations clamp them to the
     * first texel).
     */
    public final float[] uvs;

    /** True when the export names a model texture. */
    public boolean hasTexture() {
        return textureFile != null && !textureFile.isEmpty();
    }

    /** True when the export carries per-expanded-vertex UV data. */
    public boolean hasUvs() {
        return uvs != null && uvs.length >= UV_FLOATS;
    }

    /** {@code u} of an expanded vertex ({@code -1} when the export has no UVs). */
    public float u(int vertex) {
        return hasUvs() && vertex >= 0 && vertex * UV_FLOATS < uvs.length
                ? uvs[vertex * UV_FLOATS] : -1f;
    }

    /** {@code v} of an expanded vertex ({@code -1} when the export has no UVs). */
    public float v(int vertex) {
        return hasUvs() && vertex >= 0 && vertex * UV_FLOATS + 1 < uvs.length
                ? uvs[vertex * UV_FLOATS + 1] : -1f;
    }

    MeshExport(String name, String measurement, boolean yUp, ColorPalette palette,
               List<BlendableMesh> meshes, MeshSkin skin, List<MeshSkinClip> skinClips,
               int sharedVertexCount, int skippedTriangles, boolean[] stepVertices,
               java.util.Map<String, boolean[]> animationStepFrames,
               java.util.Map<String, float[]> animationFrameTimes,
               java.util.Map<String, Float> animationFps,
               String textureFile, float[] uvs) {
        this.name = name;
        this.measurement = measurement;
        this.yUp = yUp;
        this.palette = palette;
        this.meshes = Collections.unmodifiableList(meshes);
        this.skin = skin;
        this.skinClips = skinClips != null
            ? Collections.unmodifiableList(skinClips) : Collections.emptyList();
        this.sharedVertexCount = sharedVertexCount;
        this.skippedTriangles = skippedTriangles;
        this.stepVertices = stepVertices != null ? stepVertices : new boolean[0];
        this.animationStepFrames = animationStepFrames != null
            ? Collections.unmodifiableMap(animationStepFrames) : Collections.emptyMap();
        this.animationFrameTimes = animationFrameTimes != null
            ? Collections.unmodifiableMap(animationFrameTimes) : Collections.emptyMap();
        this.animationFps = animationFps != null
            ? Collections.unmodifiableMap(animationFps) : Collections.emptyMap();
        this.textureFile = textureFile;
        this.uvs = uvs != null ? uvs : new float[0];
    }
}
