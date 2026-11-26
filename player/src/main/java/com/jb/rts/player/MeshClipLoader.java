package com.jb.rts.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatFile;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.mesh.vat.VatMeshData;
import com.jb.rts.mesh.vat.VatPoseBaker;
import com.jb.rts.mesh.vat.VatSkinBaker;
import com.jb.rts.mesh.vat.VatVertexOffsetBaker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@code .json} mesh export or a baked {@code .vat} file into the player's clip list, and
 * computes the geometry bounds the camera needs.
 *
 * <p>This is the <b>loading and baking half</b> of the mesh player, split out so the screen only has
 * to deal with rendering, input and UI. Everything here runs on the render thread and owns native
 * resources through {@link #result()}:</p>
 *
 * <ul>
 *   <li>the <b>model texture</b> of an export (version 6) — one picture serves every clip;</li>
 *   <li>one {@link VatInstancedMesh} per animation, baked through {@link VatSkinBaker} (shared-skin
 *       path, the prerequisite for blending) or, for exports without usable clips, through
 *       {@link VatPoseBaker} (per-clip bake, no blending).</li>
 * </ul>
 *
 * <p>A loader instance is single-use: construct it, read {@link #result()}, then keep it around as
 * the owner of the texture and dispose it with {@link #dispose()} — after disposing every
 * {@link PlayerClip#group} of the result. A failed load throws and leaves no batches behind.</p>
 */
public final class MeshClipLoader {

    /**
     * Fallback cycle length in seconds for animations that carry <b>no timing of their own</b>
     * (single-frame {@code rest} poses, legacy pose bakes without keyframe times).
     */
    public static final float ANIM_SECONDS = 4f;

    /**
     * Everything a successful load produced: the parsed export (or {@code null} for {@code .vat}),
     * the static mesh of a {@code .vat} file ({@code null} for JSON), and the clips.
     */
    public static final class Loaded {
        /** The parsed JSON export, or {@code null} when a {@code .vat} file was loaded. */
        public final MeshExport export;
        /** The static mesh of a {@code .vat} file, or {@code null} for a JSON export. */
        public final VatMeshData vatMesh;
        /** One entry per animation, in export order. */
        public final List<PlayerClip> clips;

        Loaded(MeshExport export, VatMeshData vatMesh, List<PlayerClip> clips) {
            this.export = export;
            this.vatMesh = vatMesh;
            this.clips = clips;
        }
    }

    private final Texture exportTexture;
    private final MeshExport export;
    private final VatMeshData vatMesh;
    private final List<PlayerClip> clips;
    private final float[] bounds;
    private final float boundsMinZ;
    private final float boundsMaxZ;

    /**
     * Loads one file.
     *
     * @param path   the file to load; resolved with {@link MeshFileResolver#handleFor(String)}
     * @param copies instance capacity of every batch (e.g. {@code MeshPlayer.MAX_INSTANCE_COUNT})
     * @throws RuntimeException when the file cannot be read or baked
     */
    public MeshClipLoader(String path, int copies) {
        if (isVatPath(path)) {
            VatFile.FileContent content = VatFile.loadAll(MeshFileResolver.handleFor(path));
            this.exportTexture = null;
            this.export = null;
            this.vatMesh = content.mesh();
            List<PlayerClip> result = new ArrayList<>();
            for (VatAnimationData animation : content.animations()) {
                result.add(new PlayerClip(animation.name(), animation.frameCount(),
                    new VatInstancedMesh(vatMesh, animation, copies), null));
            }
            this.clips = result;
            Gdx.app.log("MeshClipLoader", "VAT file: " + vatMesh.vertexCount() + " vertices, "
                + content.animations().size() + " clip(s)");
        } else {
            MeshExport loaded = MeshExportLoader.load(MeshFileResolver.handleFor(path));
            if (loaded.skippedTriangles > 0) {
                // Damaged meshes must not stop the preview, but the user should know.
                Gdx.app.log("MeshClipLoader", "WARNING: '" + MeshFileResolver.shortName(path)
                    + "' skipped " + loaded.skippedTriangles
                    + " triangle(s) that reference non-existent vertices.");
            }
            convertExportToWorldYUp(loaded);
            this.exportTexture = loadExportTexture(path, loaded);
            this.export = loaded;
            this.vatMesh = null;
            this.clips = buildClips(loaded, copies);
            Gdx.app.log("MeshClipLoader", loaded.skin != null
                ? "JSON export: " + loaded.skin.boneCount() + " bone row(s), "
                    + loaded.skinClips.size() + " baked clip(s)"
                : "JSON export without bones: using the per-vertex pose fallback");
        }
        float[] box = new float[4];
        float[] z = new float[2];
        computeBounds(export, vatMesh, box, z);
        this.bounds = box;
        this.boundsMinZ = z[0];
        this.boundsMaxZ = z[1];
    }

    /** Everything the load produced. */
    public Loaded result() {
        return new Loaded(export, vatMesh, clips);
    }

    /** The loaded clips, in export order. */
    public List<PlayerClip> clips() {
        return clips;
    }

    /** The export's model texture, or {@code null} when it has none (owned by this loader). */
    public Texture texture() {
        return exportTexture;
    }

    /** {@code [minX, minY, maxX, maxY]} of the loaded geometry. */
    public float[] bounds() {
        return bounds;
    }

    /** Lowest z of the loaded geometry (the editor's draw-order coordinate). */
    public float boundsMinZ() {
        return boundsMinZ;
    }

    /** Highest z of the loaded geometry. */
    public float boundsMaxZ() {
        return boundsMaxZ;
    }

    /** True for baked {@code .vat} files (case-insensitive). */
    public static boolean isVatPath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".vat");
    }

    /** Releases the export texture; the clip batches are freed by whoever owns them. */
    public void dispose() {
        if (exportTexture != null) exportTexture.dispose();
    }

    // ------------------------------------------------------------------ texture

    /**
     * Loads the export's model texture (version 6) from the JSON file's directory, or returns
     * {@code null} when the export has none.
     *
     * <p>Failures never abort the load: an export that names a texture the checkout does not contain
     * still plays with its per-corner palette colours.</p>
     */
    private static Texture loadExportTexture(String exportPath, MeshExport e) {
        if (!e.hasTexture()) return null;
        if (!e.hasUvs()) {
            Gdx.app.log("MeshClipLoader", "WARNING: '" + MeshFileResolver.shortName(exportPath)
                + "' declares texture '" + e.textureFile + "' but carries no uvVertices"
                + " — drawing its palette colours.");
            return null;
        }
        // Same resolution as the export itself: next to the JSON on disk, or inside the jar.
        FileHandle textureFile = MeshFileResolver.handleFor(exportPath).parent().child(e.textureFile);
        if (!textureFile.exists()) {
            Gdx.app.error("MeshClipLoader", "Texture '" + e.textureFile + "' not found at "
                + textureFile.path() + " — drawing the palette colours instead.");
            return null;
        }
        Texture texture = new Texture(textureFile);
        // The atlas is authored at a fixed resolution and drawn at arbitrary scale.
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        Gdx.app.log("MeshClipLoader", "Model texture: " + textureFile.name() + " ("
            + texture.getWidth() + "x" + texture.getHeight() + ")");
        return texture;
    }

    /** The export's texture, but only for mesh data that actually carries UVs. */
    private Texture textureFor(VatMeshData meshData) {
        return exportTexture != null && meshData.hasUvs() ? exportTexture : null;
    }

    // ------------------------------------------------------------------ baking

    /** One clip per pose group: the shared-skin path if possible, else the per-pose fallback. */
    private List<PlayerClip> buildClips(MeshExport e, int copies) {
        Map<String, List<BlendableMesh>> byName = groupPoses(e);
        List<PlayerClip> skinned = buildSkinnedClips(e, byName, copies);
        return skinned != null ? skinned : buildPoseClips(e, byName, copies);
    }

    /** Groups the export's poses by animation name, preserving their export order. */
    private static Map<String, List<BlendableMesh>> groupPoses(MeshExport e) {
        Map<String, List<BlendableMesh>> byName = new LinkedHashMap<>();
        for (BlendableMesh m : e.meshes) {
            String anim = m.poseName();
            if (anim == null || anim.isEmpty()) anim = "rest";
            byName.computeIfAbsent(anim, k -> new ArrayList<>()).add(m);
        }
        Gdx.app.log("MeshClipLoader", "buildClips: " + byName.size() + " groups from "
            + e.meshes.size() + " meshes");
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            Gdx.app.log("MeshClipLoader", "  group '" + entry.getKey() + "': "
                + entry.getValue().size() + " frames");
        }
        return byName;
    }

    /**
     * Returns the baked clip called {@code name}, or {@code null}.
     *
     * @param clips the export's baked clips
     * @param name  the clip name to find
     */
    private static MeshSkinClip clipNamed(List<MeshSkinClip> clips, String name) {
        for (MeshSkinClip clip : clips) {
            if (clip.name.equals(name)) return clip;
        }
        return null;
    }

    /** Log suffix describing the per-frame colour strip of a group ({@code ""} when it has none). */
    private static String colorInfo(VatInstancedMesh group) {
        if (group == null || group.frameColorTexture() == null) return "";
        return ", per-frame colours "
            + group.frameColorTexture().width() + "x" + group.frameColorTexture().height();
    }

    /** Log suffix describing the hand-animated vertex rows restored by the offset baker. */
    private static String vertexOffsetInfo(VatVertexOffsetBaker.Result patched) {
        if (patched == null) return "";
        return ", " + patched.rowCount() + " hand-animated vertex row(s), max offset "
            + String.format(java.util.Locale.ROOT, "%.4f", patched.maxResidual());
    }

    /**
     * Nominal frame rate for the <b>pose-fallback</b> bake only (exports without a skin block).
     * Those poses carry no keyframe times, so choosing {@code fps} this way keeps the fallback at
     * {@link #ANIM_SECONDS} instead of an arbitrary length.
     */
    private static float frameRate(int frameCount) {
        return frameCount > 1 ? (frameCount - 1) / ANIM_SECONDS : 1f;
    }

    /**
     * Skinned path: bakes <b>all clips against one shared skin</b> and reuses one
     * {@link VatMeshData} for every animation.
     *
     * @return the clips, or {@code null} when the export cannot use one shared skin (then
     *         {@link #buildPoseClips} takes over; blending is impossible in that case)
     */
    private List<PlayerClip> buildSkinnedClips(MeshExport e,
                                               Map<String, List<BlendableMesh>> byName,
                                               int copies) {
        if (e.skin == null || e.meshes.isEmpty()) return null;
        BlendableMesh bindPose = e.meshes.get(0);

        // Collect the groups that have a baked clip; a hand-animated vertex adds a matrix row,
        // so baking each clip on its own gives every clip a different row count.
        List<MeshSkinClip> clipList = new ArrayList<>();
        List<List<BlendableMesh>> framesPerClip = new ArrayList<>();
        List<String> groupNames = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            MeshSkinClip clip = clipNamed(e.skinClips, entry.getKey());
            if (clip == null) continue;
            groupNames.add(entry.getKey());
            clipList.add(clip);
            framesPerClip.add(entry.getValue());
        }
        if (clipList.isEmpty()) return null;

        List<VatVertexOffsetBaker.Result> patched;
        try {
            patched = VatVertexOffsetBaker.bakeAll(bindPose, e.skin, clipList, framesPerClip,
                VatVertexOffsetBaker.DEFAULT_EPSILON);
        } catch (IllegalArgumentException ex) {
            Gdx.app.log("MeshClipLoader", "WARNING: cannot bake one shared skin ("
                + ex.getMessage() + ") - falling back to per-group bakes; blending is disabled.");
            return null;
        }

        MeshSkin sharedSkin = patched.get(0).skin();
        VatMeshData meshData = VatSkinBaker.meshData(bindPose, sharedSkin, e.palette,
            e.stepVertices, e.uvs);
        List<PlayerClip> result = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlendableMesh> frames = entry.getValue();
            int clipIndex = groupNames.indexOf(name);
            VatInstancedMesh group = null;
            String error = null;
            try {
                VatAnimationData animation;
                VatVertexOffsetBaker.Result patch = null;
                if (clipIndex >= 0) {
                    patch = patched.get(clipIndex);
                    animation = VatSkinBaker.animation(e.name + "_" + name, sharedSkin,
                        patch.clip(), frames, e.palette);
                } else if ("rest".equals(name)) {
                    animation = VatSkinBaker.restAnimation(sharedSkin, frameRate(1));
                } else {
                    // A group without a baked clip cannot share the skin: use the pose path.
                    return null;
                }
                group = new VatInstancedMesh(meshData, animation, copies, textureFor(meshData));
                Gdx.app.log("MeshClipLoader", "  -> VAT skin group '" + name + "' created ("
                    + sharedSkin.boneCount() + " matrix rows, " + animation.frameCount()
                    + " frames" + colorInfo(group) + vertexOffsetInfo(patch) + ")");
            } catch (RuntimeException ex) {
                error = ex.getMessage();
                Gdx.app.error("MeshClipLoader", "Cannot build VAT group for animation '"
                    + name + "': " + error);
            }
            result.add(new PlayerClip(name,
                group != null ? group.animation().frameCount() : frames.size(), group, error));
        }
        Gdx.app.log("MeshClipLoader", "Shared skin: " + sharedSkin.boneCount()
            + " matrix rows for " + clipList.size() + " clip(s) - animations can be blended.");
        return result;
    }

    /**
     * Legacy / fallback path: bakes every pose group on its own ({@link VatPoseBaker}). Each group
     * gets its own {@link VatMeshData} and bone table, so blending is only possible when the tables
     * happen to have the same size.
     */
    private List<PlayerClip> buildPoseClips(MeshExport e, Map<String, List<BlendableMesh>> byName,
                                           int copies) {
        List<PlayerClip> result = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlendableMesh> frames = entry.getValue();
            VatInstancedMesh group = null;
            String error = null;
            try {
                boolean[] attachmentSteps = e.animationStepFrames.get(name);
                if (attachmentSteps != null && attachmentSteps.length != frames.size()) {
                    attachmentSteps = null;
                }
                VatPoseBaker.Result baked = VatPoseBaker.bake(
                    e.name + "_" + name, frames, e.palette, frameRate(frames.size()),
                    e.stepVertices, attachmentSteps, e.uvs);
                group = new VatInstancedMesh(baked.mesh(), baked.animation(), copies,
                    textureFor(baked.mesh()));
                Gdx.app.log("MeshClipLoader", "  -> VAT pose group '" + name + "' created ("
                    + baked.mesh().vertexCount() + " vertices, "
                    + baked.animation().frameCount() + " frames" + colorInfo(group) + ")");
            } catch (RuntimeException ex) {
                error = ex.getMessage();
                Gdx.app.error("MeshClipLoader", "Cannot build VAT group for animation '"
                    + name + "': " + error);
            }
            result.add(new PlayerClip(name,
                group != null ? group.animation().frameCount() : frames.size(), group, error));
        }
        return result;
    }

    // ------------------------------------------------------------------ coordinates

    /**
     * Re-orients the loaded poses into the player's world space, the standard math system
     * (<b>+x right, +y up</b>).
     *
     * <p>The mesheditor is a JavaFX editor whose y axis grows <b>downward</b>, but its exporter
     * negates every written y value by default and records that in the export's {@code "yUp"} flag.
     * Such an export is <b>already</b> in the player's convention and must be used as-is —
     * mirroring it again makes the mesh appear upside-down. Only exports that explicitly kept the
     * editor's y-down space ({@code "yUp": false}) are mirrored, once.</p>
     *
     * <p>Every pose of one export shares the <b>same</b> {@code defaultPositions} array, so the base
     * array must be flipped exactly once instead of once per pose.</p>
     *
     * @param e the export to re-orient in place
     */
    public static void convertExportToWorldYUp(MeshExport e) {
        if (e.yUp) return;
        // Identity-based set: poses of one export share the same base array instance.
        java.util.Set<float[]> flippedBase =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlendableMesh m : e.meshes) {
            if (flippedBase.add(m.defaultPositions)) {
                flipY(m.defaultPositions);
            }
            flipY(m.positions);
        }
        // The baked skin must be mirrored the same way.
        if (e.skin != null) e.skin.flipY();
        for (MeshSkinClip clip : e.skinClips) clip.flipY();
    }

    /** Negates the y component of an interleaved {@code [x, y, z, …]} array. */
    private static void flipY(float[] xyz) {
        for (int i = 1; i < xyz.length; i += BlendableMesh.POSITION_FLOATS) {
            xyz[i] = -xyz[i];
        }
    }

    // ------------------------------------------------------------------ bounds

    /**
     * Computes the geometry bounds.
     *
     * @param export  the JSON export, or {@code null}
     * @param vatMesh the static {@code .vat} mesh, or {@code null}
     * @param box     receives {@code minX, minY, maxX, maxY}
     * @param z       receives {@code minZ, maxZ}
     */
    private static void computeBounds(MeshExport export, VatMeshData vatMesh, float[] box,
                                      float[] z) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        if (vatMesh != null) {
            float[] positions = vatMesh.positions();
            for (int i = 0; i + 2 < positions.length; i += 3) {
                float x = positions[i];
                float y = positions[i + 1];
                float zz = positions[i + 2];
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (zz < minZ) minZ = zz;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (zz > maxZ) maxZ = zz;
            }
        }
        if (export != null) {
            for (BlendableMesh m : export.meshes) {
                for (int i = 0; i < m.positions.length; i += BlendableMesh.POSITION_FLOATS) {
                    float x = m.defaultPositions[i] + m.positions[i];
                    float y = m.defaultPositions[i + 1] + m.positions[i + 1];
                    float zz = m.defaultPositions[i + 2] + m.positions[i + 2];
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (zz < minZ) minZ = zz;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (zz > maxZ) maxZ = zz;
                }
            }
        }
        if (minZ == Float.MAX_VALUE) minZ = 0f;
        if (maxZ == -Float.MAX_VALUE) maxZ = 0f;
        z[0] = minZ;
        z[1] = maxZ;
        // Guard against degenerate/empty bounds.
        if (maxX - minX < 1e-4f) maxX = minX + 1;
        if (maxY - minY < 1e-4f) maxY = minY + 1;
        box[0] = minX;
        box[1] = minY;
        box[2] = maxX;
        box[3] = maxY;
    }
}
