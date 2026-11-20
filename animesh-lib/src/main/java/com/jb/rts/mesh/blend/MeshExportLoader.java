package com.jb.rts.mesh.blend;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads the 2D mesheditor <em>export</em> JSON format into the blend package's model.
 *
 * <h3>Input format</h3>
 * This is the self-describing document produced by the editor's JSON exporter. The real-life
 * examples the tests use live in {@code test-assets/meshes} (e.g. {@code tree_redwood-export.json},
 * {@code dino_longneck-export.json}). Structure (all bones ignored):
 * <pre>{@code
 * {
 *   "name": "tree_redwood",
 *   "measurement": "mm",                                               // optional, default "mm"
 *   "yUp": true,                                                       // optional, default true
 *   "textureFile": "fire_texture.png",                                 // optional (v6), next to this file
 *   "colors": [ { "name": "fur", "red": 151, "green": 95, "blue": 49, "alpha": 1 }, ... ],
 *   "vertices": [ { "x": 0.0, "y": 0.0, "z": 0.0 }, ... ],             // default pose (shared positions)
 *   "uvVertices": [ { "u": 0.0, "v": 0.0 }, ... ],                     // optional (v6), one per shared vertex
 *   "objects": [
 *     { "name": "head", "triangles": [
 *       { "vertices": [60,61,62], "colors": [2,2,2] }, ...             // per-corner colour indexes
 *     ] }
 *   ],
 *   "animations": [ { "name": "walk", "frames": [
 *     { "name": "...", "offsets": [ { "x":..., "y":..., "z":... }, ... ] }  // one pose each: increments
 *   ] } ]                                                                   // over the default pose
 * }
 * }</pre>
 *
 * <p><b>Y orientation.</b> The exporter writes cartesian coordinates with positive y pointing
 * <b>upwards</b> by default and records that in the optional top-level {@code "yUp"} flag
 * ({@code JsonExportOptions.cartesianYUp}). Loading does <b>not</b> transform the coordinates —
 * {@link MeshExport#yUp} simply reports which convention the file used, so the renderer can
 * mirror y once when (and only when) the file kept the editor's y-down screen space. Files
 * without the flag are assumed to be y-up, because that is what the exporter has written since
 * cartesian coordinates were introduced (and what all current exports in
 * {@code assets/meshes} contain).
 *
 * <p><b>Textures (version 6).</b> A mesh that was painted with
 * {@code TextureAtlasExporter} names its picture in the optional top-level
 * {@code "textureFile"} (a bare file name, written next to the export by
 * {@code JsonMeshExporter#export}) and stores one normalised {@code {u, v}} pair per
 * <b>shared</b> vertex in {@code "uvVertices"}. The loader expands them to the per-corner
 * layout ({@link MeshExport#uvs}) so renderers can sample the texture instead of the
 * per-corner palette colours ({@link MeshExport#hasTexture()}). A {@code -1} component is
 * the editor's "unset" marker.
 *
 * <p><b>Attachments (version 5).</b> The top-level {@code "stepVertices"} array lists shared
 * vertex indices whose frame transitions must not be blended (attachment geometry). Each
 * animation may carry {@code "attachmentSteps"} (one boolean per baked frame) marking the frames
 * where the effective attachment state changes; {@link MeshExport#animationStepFrames} exposes
 * them for the pose fallback, while {@link SkinExportParser} stores them on every
 * {@link MeshSkinClip}. A missing array means "no attachments / no switches".
 *
 * <p><b>Positions are deltas, not snapshots.</b> Export version ≥ 2 stores every frame's
 * positions as {@code offsets} relative to the top-level {@code vertices} (which hold the mesh's
 * <em>default pose</em>): {@code absoluteVertex[i] = vertices[i] + offsets[i]} for x, y <em>and</em>
 * z. Complying loaders reconstruct the absolute pose. For backwards compatibility, frames with an
 * explicit absolute {@code vertices} list (legacy pre-version-2 format) are also
 * accepted, as are frames without any position data (they equal the default pose).
 * A missing {@code z} is treated as {@code 0}.
 *
 * <h3>What the loader produces</h3>
 * <ul>
 *   <li>A {@link ColorPalette} from {@code colors}.</li>
 *   <li>An <b>expanded</b> topology from {@code objects}-&gt;triangles. Because the same shared
 *       vertex may carry <em>different</em> colour indexes in different triangles, every
 *       triangle-corner becomes its own vertex (with its own colour index). This lets each
 *       triangle keep its corners' colours without bleeding into neighbours.</li>
 *   <li>One {@link BlendableMesh} <em>per pose</em>: the rest pose (top-level {@code vertices})
 *       and every animation frame. All poses share the same expanded topology &amp; colour
 *       indexes – only positions differ, so colours are identical across frames.</li>
 * </ul>
 *
 * <p><b>One pose per baked frame.</b> Two frames that share a keyframe time are a zero-length
 * interval: {@link SkinExportParser} keeps only the later pose's matrices, so the loader drops the
 * superseded pose as well and renumbers the remaining ones. That keeps the pose list index-aligned
 * with {@link MeshExport#skinClips} — the per-frame colour overrides and
 * {@code com.jb.rts.mesh.vat.VatVertexOffsetBaker}'s hand-animated vertices rely on it.
 *
 * <p><b>Damaged input:</b> triangles whose vertex indexes fall outside the {@code vertices}
 * array are skipped instead of aborting the load — they cannot be drawn anyway and the
 * mesheditor's own renderer ignores them as well. The count is exposed as
 * {@link MeshExport#skippedTriangles}.
 *
 * <p>Bones and animations' bone transforms are <b>ignored</b>.
 */
public final class MeshExportLoader {

    private MeshExportLoader() {}

    /** Reads and parses {@code file}. */
    public static MeshExport load(FileHandle file) {
        return loadFromString(file.readString());
    }

    /** Parses an export document from its raw JSON text. */
    public static MeshExport loadFromString(String json) {
        return load(new JsonReader().parse(json));
    }

    /** Parses an already-parsed JSON document. */
    public static MeshExport load(JsonValue root) {
        String name = root.has("name") ? root.getString("name") : "mesh";
        // Measurement unit written by the exporter; unspecified/blank defaults to millimetres.
        String measurement = (root.has("measurement") && !root.getString("measurement").isBlank())
            ? root.getString("measurement").trim()
            : "mm";
        // Orientation of the y values. The mesheditor's exporter writes cartesian y-up data by
        // default and marks it with "yUp"; only exports that kept the editor's y-down screen
        // space declare "yUp": false. Files without the flag are assumed to be y-up (see the
        // class documentation) — they must not be mirrored a second time.
        boolean yUp = root.getBoolean("yUp", true);

        // Model texture (version 6): bare file name next to the export file. Missing/blank
        // means "no texture" and the per-corner colours are drawn exactly as before.
        String textureFile = root.has("textureFile") ? root.getString("textureFile", "").trim() : "";
        if (textureFile.isEmpty()) textureFile = null;

        ColorPalette palette = ColorPalette.fromJson(root.get("colors"));

        // ---- default pose (top-level vertices, x/y/z) ----
        JsonValue vertArray = root.get("vertices");
        int    sharedCount = vertArray != null ? vertArray.size : -1;
        float[] defaultPos = vertArray != null ? parsePositions(vertArray) : new float[0];

        // ---- expanded topology (per triangle-corner: shared vertex ref + colour index) ----
        List<Short> sharedRefs = new ArrayList<>();
        Topology    topology  = buildExpandedTopology(root, palette, sharedRefs, sharedCount);
        int         e         = topology.expandedVertices();

        // Files without a top-level "vertices" list (e.g. the exporter's
        // include-vertices = off option) fall back to the topology's vertex count.
        if (sharedCount < 0) {
            sharedCount = 0;
            for (short r : sharedRefs) sharedCount = Math.max(sharedCount, r + 1);
        }
        if (defaultPos.length != sharedCount * BlendableMesh.POSITION_FLOATS) {
            defaultPos = zeroPositions(sharedCount);
        }

        short[] indices      = new short[e];
        for (int i = 0; i < e; i++) indices[i] = (short) i;

        int[] colorIndices = collectColorIndices(root, palette, e, sharedCount);

        // ---- expanded default positions (base/rest pose, shared by every frame) ----
        float[] expandedDefaultPos = expandPositions(defaultPos, sharedRefs);

        // ---- poses ----
        List<BlendableMesh> meshes = new ArrayList<>();

        // Rest pose = the default pose with zero offsets.
        meshes.add(new BlendableMesh(name + "_rest_0",
            expandedDefaultPos, new float[expandedDefaultPos.length],
            colorIndices, indices));

        // Animation frames (deltas over the default pose, or legacy absolute vertices).
        JsonValue anims = root.get("animations");
        java.util.Map<String, boolean[]> animationStepFrames = new java.util.LinkedHashMap<>();
        if (anims != null) {
            int animIndex = 0;
            for (JsonValue anim = anims.child; anim != null; anim = anim.next, animIndex++) {
                String animName = anim.has("name") ? anim.getString("name") : ("anim" + animIndex);
                JsonValue frames = anim.get("frames");
                if (frames == null) continue;
                boolean[] rawSteps = readAttachmentSteps(anim.get("attachmentSteps"), frames.size);
                java.util.List<Boolean> keptSteps = new ArrayList<>();
                // Zero-length keyframe intervals (two poses sharing a time) are merged by
                // SkinExportParser: the later pose wins. The same pose has to be dropped here,
                // otherwise the pose list and the baked clip would drift apart and consumers that
                // pair them by index (per-frame colours, hand-animated vertices — see
                // VatVertexOffsetBaker) would apply every later frame's data to the wrong pose.
                float[] times = SkinExportParser.readTimes(anim.get("frameTimes"), frames.size,
                    anim.getFloat("fps", SkinExportParser.DEFAULT_FPS));
                int frameIndex = 0;
                int keptIndex = 0;
                for (JsonValue frame = frames.child; frame != null; frame = frame.next, frameIndex++) {
                    if (frameIndex + 1 < frames.size
                            && times[frameIndex + 1] <= times[frameIndex]
                                + SkinExportParser.TIME_EPSILON) {
                        // Superseded pose: its attachment switch (if any) happens at
                        // the same instant, so keep it on the surviving frame.
                        if (rawSteps != null && frameIndex < rawSteps.length && rawSteps[frameIndex]
                                && !keptSteps.isEmpty()) {
                            keptSteps.set(keptSteps.size() - 1, true);
                        }
                        continue;
                    }
                    float[] sharedOffs = sharedPoseOffsets(frame, defaultPos, sharedCount);
                    float[] expandedOffs = expandPositions(sharedOffs, sharedRefs);
                    // Per-frame colour-index overrides (version 3+). When absent,
                    // the frame uses the mesh default colours (shared across all poses).
                    int[] frameColors = collectFrameColors(frame, colorIndices, e);
                    meshes.add(new BlendableMesh(name + "_" + animName + "_" + keptIndex,
                        expandedDefaultPos, expandedOffs,
                        frameColors, indices));
                    keptSteps.add(rawSteps != null && frameIndex < rawSteps.length
                        && rawSteps[frameIndex]);
                    keptIndex++;
                }
                if (keptSteps.stream().anyMatch(Boolean::booleanValue)) {
                    boolean[] steps = new boolean[keptSteps.size()];
                    for (int i = 0; i < steps.length; i++) steps[i] = keptSteps.get(i);
                    animationStepFrames.put(animName, steps);
                }
            }
        }

        // ---- skin (bones + weights + baked matrices), null for bone-less exports ----
        SkinExportParser.Parsed skin = SkinExportParser.parse(root, sharedRefs, sharedCount, e);

        return new MeshExport(name, measurement, yUp, palette, meshes,
            skin != null ? skin.skin() : null,
            skin != null ? skin.clips() : List.of(),
            sharedCount, topology.skippedTriangles(),
            readStepVertices(root, sharedRefs, sharedCount, e),
            animationStepFrames,
            textureFile,
            readUvs(root, sharedRefs, sharedCount, e));
    }

    /**
     * Reads {@code animations[].attachmentSteps} (one boolean per authored frame).
     * Returns {@code null} when the array is absent or does not match the frame count.
     */
    private static boolean[] readAttachmentSteps(JsonValue node, int frameCount) {
        if (node == null || node.size != frameCount) return null;
        boolean[] steps = new boolean[frameCount];
        boolean any = false;
        for (int i = 0; i < frameCount; i++) {
            steps[i] = node.get(i).asBoolean();
            any |= steps[i];
        }
        return any ? steps : null;
    }

    /**
     * Expands the export's {@code "stepVertices"} (shared vertex indices of
     * attachment geometry) to the per-corner vertex layout the renderers use.
     * Missing array → empty mask (ordinary meshes pay nothing).
     */
    private static boolean[] readStepVertices(JsonValue root, List<Short> sharedRefs,
                                              int sharedCount, int expandedCount) {
        JsonValue node = root.get("stepVertices");
        if (node == null || node.size == 0) return new boolean[0];
        boolean[] shared = new boolean[Math.max(sharedCount, 0)];
        for (JsonValue value = node.child; value != null; value = value.next) {
            int index = value.asInt();
            if (index >= 0 && index < shared.length) {
                shared[index] = true;
            }
        }
        boolean[] expanded = new boolean[expandedCount];
        for (int i = 0; i < expandedCount && i < sharedRefs.size(); i++) {
            int source = sharedRefs.get(i);
            expanded[i] = source >= 0 && source < shared.length && shared[source];
        }
        return expanded;
    }

    /**
     * Expands the export's {@code "uvVertices"} array (one {@code {u, v}} pair per
     * <em>shared</em> vertex, version 6) to the per-corner vertex layout the renderers use.
     * A missing/empty array means "no texture coordinates" and returns an empty array, so
     * ordinary meshes pay nothing. A present array must match the shared vertex count —
     * silently misaligning UVs would paint a garbage texture.
     */
    private static float[] readUvs(JsonValue root, List<Short> sharedRefs,
                                   int sharedCount, int expandedCount) {
        JsonValue node = root.get("uvVertices");
        if (node == null || node.size == 0) return new float[0];
        requireSize(node, sharedCount, "uvVertices");
        float[] shared = new float[sharedCount * MeshExport.UV_FLOATS];
        for (int i = 0; i < sharedCount; i++) {
            JsonValue uv = node.get(i);
            shared[i * 2]     = uv.getFloat("u", -1f);
            shared[i * 2 + 1] = uv.getFloat("v", -1f);
        }
        float[] expanded = new float[expandedCount * MeshExport.UV_FLOATS];
        for (int i = 0; i < expandedCount; i++) {
            int source = sharedRefs.get(i);
            expanded[i * 2]     = shared[source * 2];
            expanded[i * 2 + 1] = shared[source * 2 + 1];
        }
        return expanded;
    }

    // -----------------------------------------------------------------------
    // Topology building
    // -----------------------------------------------------------------------

    /**
     * First pass: appends one {@code sharedVertexIndex} per triangle-corner into {@code refs}.
     * Triangles that reference a vertex outside the export's {@code vertices} array are
     * <b>skipped</b> (they cannot contribute geometry); the mesheditor's renderer ignores such
     * leftovers too (see its {@code PresentationBuilder}). The number of skipped triangles is
     * reported through {@link MeshExport#skippedTriangles} so callers can surface the damage.
     *
     * @param sharedCount number of entries in the export's {@code vertices} array, or a negative
     *                    value when the export has no vertex table (nothing to validate against)
     * @return the expanded vertex count plus the number of skipped triangles
     */
    private static Topology buildExpandedTopology(JsonValue root, ColorPalette palette,
                                                  List<Short> refs, int sharedCount) {
        JsonValue objects = root.get("objects");
        if (objects == null) {
            throw new IllegalArgumentException("Export must contain an \"objects\" array.");
        }
        int expanded = 0;
        int skipped  = 0;
        for (JsonValue obj = objects.child; obj != null; obj = obj.next) {
            JsonValue tris = obj.get("triangles");
            if (tris == null) continue;
            for (JsonValue tri = tris.child; tri != null; tri = tri.next) {
                JsonValue tverts = tri.get("vertices");
                JsonValue tcols  = tri.get("colors");
                if (tverts == null || tverts.size != 3) {
                    throw new IllegalArgumentException(
                        "Each triangle needs a \"vertices\" array of exactly 3 indexes.");
                }
                if (tcols == null || tcols.size != 3) {
                    throw new IllegalArgumentException(
                        "Each triangle needs a \"colors\" array of exactly 3 colour indexes.");
                }
                if (!hasValidVertexIndices(tverts, sharedCount)) {
                    skipped++;
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    int sharedIdx = tverts.getInt(c);
                    int colIdx    = tcols.getInt(c);
                    validateColor(palette, colIdx);
                    refs.add((short) sharedIdx);
                    expanded++;
                }
            }
        }
        if (expanded == 0) {
            throw new IllegalArgumentException(skipped == 0
                ? "Export contains no triangles."
                : "Export contains no usable triangles (all " + skipped
                    + " reference non-existent vertices).");
        }
        return new Topology(expanded, skipped);
    }

    /**
     * Returns {@code true} when all three vertex indexes of a triangle are non-negative and
     * (if known) lie inside the export's shared vertex table.
     *
     * @param tverts    the triangle's {@code vertices} array (exactly 3 entries)
     * @param sharedCount number of shared vertices, or a negative value when unknown
     */
    private static boolean hasValidVertexIndices(JsonValue tverts, int sharedCount) {
        for (int c = 0; c < 3; c++) {
            int idx = tverts.getInt(c);
            if (idx < 0) return false;
            if (sharedCount >= 0 && idx >= sharedCount) return false;
        }
        return true;
    }

    /**
     * Second pass: produce the per-expanded-vertex colour indexes in the same order.
     * Uses exactly the same triangle-validity test as
     * {@link #buildExpandedTopology}, so both passes stay aligned.
     */
    private static int[] collectColorIndices(JsonValue root, ColorPalette palette, int expected,
                                             int sharedCount) {
        int[] colors = new int[expected];
        int idx = 0;
        JsonValue objects = root.get("objects");
        for (JsonValue obj = objects.child; obj != null; obj = obj.next) {
            JsonValue tris = obj.get("triangles");
            if (tris == null) continue;
            for (JsonValue tri = tris.child; tri != null; tri = tri.next) {
                JsonValue tverts = tri.get("vertices");
                if (tverts == null || !hasValidVertexIndices(tverts, sharedCount)) continue;
                JsonValue tcols = tri.get("colors");
                for (int c = 0; c < 3; c++) {
                    colors[idx++] = tcols.getInt(c);
                }
            }
        }
        if (idx != expected) {
            throw new IllegalStateException("Topology/colour pass mismatch: " + idx
                + " colour entries for " + expected + " expanded vertices.");
        }
        return colors;
    }

    /** Result of {@link #buildExpandedTopology}: usable vertex count plus skipped triangles. */
    private record Topology(int expandedVertices, int skippedTriangles) {}

    /**
     * Reads per-frame colour-index overrides from a frame JSON node.
     * When the frame has no {@code "colors"} array, returns the {@code defaultColors}
     * array unchanged (the frame uses the mesh-default topology colours).
     *
     * @param frame         the animation frame JSON object
     * @param defaultColors the mesh's default colour indices (fallback)
     * @param expected      expected number of expanded vertices
     * @return either {@code defaultColors} or a newly allocated override array
     */
    private static int[] collectFrameColors(JsonValue frame, int[] defaultColors, int expected) {
        JsonValue frameCols = frame.get("colors");
        if (frameCols == null) {
            return defaultColors;
        }
        if (frameCols.size != expected) {
            throw new IllegalArgumentException(
                "Frame \"colors\" array size (" + frameCols.size
                + ") must match expanded vertex count (" + expected + ").");
        }
        int[] colors = new int[expected];
        for (int i = 0; i < expected; i++) {
            colors[i] = frameCols.getInt(i);
        }
        return colors;
    }

    private static void validateColor(ColorPalette palette, int colIdx) {
        if (palette.size() == 0) {
            throw new IllegalArgumentException(
                "Export references colour indexes but defines no \"colors\" palette.");
        }
        if (colIdx < 0 || colIdx >= palette.size()) {
            throw new IllegalArgumentException(
                "Colour index " + colIdx + " out of range [0," + palette.size()
                + ") of the exported palette.");
        }
    }

    // -----------------------------------------------------------------------
    // Pose building
    // -----------------------------------------------------------------------

    /** Maps shared vertex positions through the expansion refs into per-vertex arrays. */
    private static float[] expandPositions(float[] sharedPos, List<Short> sharedRefs) {
        int e = sharedRefs.size();
        float[] positions = new float[e * BlendableMesh.POSITION_FLOATS];
        for (int i = 0; i < e; i++) {
            int s = sharedRefs.get(i).shortValue();
            positions[i * 3]     = sharedPos[s * 3];
            positions[i * 3 + 1] = sharedPos[s * 3 + 1];
            positions[i * 3 + 2] = sharedPos[s * 3 + 2];
        }
        return positions;
    }

    /**
     * Extracts the per-vertex <b>offsets</b> (deltas) for one animation frame. Three cases are
     * supported:
     * <ul>
     *   <li>{@code offsets} present (current format): plain per-vertex increments.</li>
     *   <li>{@code vertices} present (legacy format): absolute positions minus the default pose.</li>
     *   <li>neither present: the frame equals the default pose (zero offsets).</li>
     * </ul>
     */
    private static float[] sharedPoseOffsets(JsonValue frame, float[] defaultPos, int sharedCount) {
        JsonValue offs = frame.get("offsets");
        if (offs != null) {
            requireSize(offs, sharedCount, "offsets");
            float[] offsets = new float[sharedCount * BlendableMesh.POSITION_FLOATS];
            for (int i = 0; i < sharedCount; i++) {
                JsonValue o = offs.get(i);
                int oi = i * 3;
                offsets[oi]     = o.getFloat("x", 0f);
                offsets[oi + 1] = o.getFloat("y", 0f);
                offsets[oi + 2] = o.getFloat("z", 0f);
            }
            return offsets;
        }
        JsonValue verts = frame.get("vertices");
        if (verts != null) {
            requireSize(verts, sharedCount, "vertices");
            float[] abs = parsePositions(verts);
            float[] offsets = new float[sharedCount * BlendableMesh.POSITION_FLOATS];
            for (int i = 0; i < sharedCount; i++) {
                int oi = i * 3;
                offsets[oi]     = abs[oi]     - defaultPos[oi];
                offsets[oi + 1] = abs[oi + 1] - defaultPos[oi + 1];
                offsets[oi + 2] = abs[oi + 2] - defaultPos[oi + 2];
            }
            return offsets;
        }
        return new float[sharedCount * BlendableMesh.POSITION_FLOATS];
    }

    /** Parses an absolute {@code [{x,y,z}, …]} array into {@code [x,y,z,…]} floats. */
    private static float[] parsePositions(JsonValue arr) {
        int n = arr.size;
        float[] pos = new float[n * BlendableMesh.POSITION_FLOATS];
        for (int i = 0; i < n; i++) {
            JsonValue v = arr.get(i);
            pos[i * 3]     = v.getFloat("x", 0f);
            pos[i * 3 + 1] = v.getFloat("y", 0f);
            pos[i * 3 + 2] = v.has("z") ? v.getFloat("z") : 0f;
        }
        return pos;
    }

    private static float[] zeroPositions(int sharedCount) {
        return new float[sharedCount * BlendableMesh.POSITION_FLOATS];
    }

    private static void requireSize(JsonValue arr, int sharedCount, String field) {
        if (arr.size != sharedCount) {
            throw new IllegalArgumentException(
                "Animation frame " + field + " count (" + arr.size
                + ") must match the export's shared vertex count (" + sharedCount + ").");
        }
    }
}
