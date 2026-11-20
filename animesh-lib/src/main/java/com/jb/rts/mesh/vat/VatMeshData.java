package com.jb.rts.mesh.vat;

import com.badlogic.gdx.graphics.Color;
import com.jb.rts.mesh.blend.ColorPalette;

/**
 * Immutable description of the <b>static</b> half of a GPU-skinned mesh: the bind-pose geometry
 * plus the per-vertex bone influences. Nothing in this class changes at runtime — it is uploaded
 * to the GPU exactly once by {@link VatInstancedMesh}.
 *
 * <h3>Topology</h3>
 * Vertices are <em>expanded</em> per triangle corner, exactly like
 * {@link com.jb.rts.mesh.blend.MeshExportLoader} does for the CPU-side blend renderer: the
 * mesheditor assigns colours per triangle corner, so every corner needs its own colour (and
 * therefore its own vertex). The triangle list is implicit — vertex {@code 3t}, {@code 3t+1},
 * {@code 3t+2} form triangle {@code t} — which allows {@code glDrawArraysInstanced} without an
 * index buffer.
 *
 * <h3>Vertex layout uploaded to the GPU</h3>
 * {@value VatFormat#FLOATS_PER_VERTEX} floats (40 bytes) per vertex:
 * <pre>
 *   [ px, py, pz,  packedColor,  bone0, bone1, bone2,  w0, w1, w2 ]
 *      └─ Usage.Position ─┘ └ColorPacked┘└ a_boneIds ┘ └ a_weights ┘
 * </pre>
 * {@code packedColor} is the ABGR bit pattern produced by
 * {@link Color#toFloatBits(int, int, int, int)}, i.e. the four RGBA bytes in memory order
 * expected by {@code Usage.ColorPacked}.
 *
 * <p>Weights are normalized ({@code w0 + w1 + w2 == 1}), so the vertex shader can skin without
 * a divide.
 *
 * <h3>Texture coordinates (optional)</h3>
 * A mesh painted with the editor's texture atlas carries {@value #UV_FLOATS_PER_VERTEX} floats
 * per expanded vertex ({@link #uvs()}). They are <b>not</b> part of the interleaved layout above:
 * {@link VatInstancedMesh} uploads them as a second static VBO ({@code a_texcoord0}) that only
 * exists when the mesh has UVs, so untextured meshes keep their exact previous footprint. The
 * fragment shader samples the model texture instead of {@code a_color} when the batch was built
 * with one.
 *
 * <h3>Palette indexes (optional)</h3>
 * The colours are resolved from the export's {@code ColorPalette} at bake time, but the
 * <b>index</b> of every vertex is kept ({@link #paletteIndices()}). That is what lets a live colour
 * editor recolour an uploaded mesh without a re-bake:
 * {@link VatInstancedMesh#refreshPalette(ColorPalette)} re-resolves
 * {@link #toInterleavedVertices(ColorPalette)} and re-uploads the VBO. Meshes that are not
 * palette-driven (legacy {@code .vat} files, clips blended on the CPU) leave the table empty and
 * keep their resolved colours.
 */
public final class VatMeshData {

    /** Floats per vertex in {@link #uvs()}: one {@code u} and one {@code v} component. */
    public static final int UV_FLOATS_PER_VERTEX = 2;

    private final float[] positions;    // 3 per vertex (bind pose, export space)
    private final float[] colors;       // 4 per vertex (RGBA 0..1)
    private final int[]   boneIds;      // INFLUENCES per vertex
    private final float[] weights;      // INFLUENCES per vertex, sum == 1
    private final int[]   sourceVertex; // expanded vertex -> source (shared) vertex index
    private final String[] boneNames;   // bone table the ids index into
    private final boolean[] stepVertices; // true = snap the frame (attachment geometry)
    private final float[] uvs;          // 2 per vertex (u, v) or empty: no model texture
    private final int[]   paletteIndices; // 1 palette index per vertex, or empty: not palette-driven
    private final int vertexCount;

    /**
     * Creates mesh data.
     *
     * @param positions    3 floats per vertex (bind pose)
     * @param colors       4 floats per vertex (RGBA in {@code 0..1})
     * @param boneIds      {@link VatFormat#INFLUENCES} bone indexes per vertex
     * @param weights      {@link VatFormat#INFLUENCES} weights per vertex
     * @param sourceVertex expanded vertex → source vertex index in the mesheditor mesh
     * @param boneNames    names of the bones the ids refer to (may be {@code null})
     */
    public VatMeshData(float[] positions, float[] colors,
                       int[] boneIds, float[] weights, int[] sourceVertex,
                       String[] boneNames) {
        this(positions, colors, boneIds, weights, sourceVertex, boneNames, null);
    }

    /**
     * Creates mesh data with a per-vertex <b>step</b> mask.
     *
     * <p>Vertices flagged {@code true} belong to an attachment. They must not be
     * blended between two animation frames (an attachment is either placed or
     * collapsed to size&nbsp;0); {@link VatInstancedMesh} therefore snaps their
     * frame index to {@code floor(frame)} instead of interpolating.
     *
     * @param stepVertices one flag per expanded vertex, or {@code null} for none
     */
    public VatMeshData(float[] positions, float[] colors,
                       int[] boneIds, float[] weights, int[] sourceVertex,
                       String[] boneNames, boolean[] stepVertices) {
        this(positions, colors, boneIds, weights, sourceVertex, boneNames, stepVertices, null);
    }

    /**
     * Creates mesh data with a per-vertex <b>step</b> mask and optional per-vertex
     * <b>texture coordinates</b>.
     *
     * <p>UVs are uploaded as a second static VBO (two floats per expanded vertex) and are only
     * used when a {@link com.badlogic.gdx.graphics.Texture} is handed to
     * {@link VatInstancedMesh}: the fragment shader then samples that texture instead of the
     * per-vertex (Gouraud) colour. Without UVs the mesh data behaves exactly like before.
     *
     * @param stepVertices one flag per expanded vertex, or {@code null} for none
     * @param uvs          {@code 2 * vertexCount} floats ({@code u, v} per expanded vertex),
     *                     or {@code null} for "no model texture"
     */
    public VatMeshData(float[] positions, float[] colors,
                       int[] boneIds, float[] weights, int[] sourceVertex,
                       String[] boneNames, boolean[] stepVertices, float[] uvs) {
        this(positions, colors, boneIds, weights, sourceVertex, boneNames, stepVertices, uvs, null);
    }

    /**
     * Creates mesh data that keeps the <b>palette indexes</b> its colours were resolved from.
     *
     * <p>With this table a batch can be <b>recoloured without a rebake</b>:
     * {@link VatInstancedMesh#refreshPalette(com.jb.rts.mesh.blend.ColorPalette)} re-resolves every
     * colour from the (mutable) palette and re-uploads the vertex buffer, so a colour edited in the
     * palette editor updates the mesh instantly — the same contract as
     * {@code MeshBlenderGroup#refreshPalette()}.
     *
     * @param paletteIndices one palette index per expanded vertex (the pose's
     *                       {@code colorIndices}), or {@code null} when the colours come from
     *                       somewhere else (legacy {@code .vat} files, blended clips)
     */
    public VatMeshData(float[] positions, float[] colors,
                       int[] boneIds, float[] weights, int[] sourceVertex,
                       String[] boneNames, boolean[] stepVertices, float[] uvs,
                       int[] paletteIndices) {
        if (positions == null || colors == null || boneIds == null || weights == null) {
            throw new IllegalArgumentException("VAT mesh arrays must not be null.");
        }
        if (positions.length % 3 != 0) {
            throw new IllegalArgumentException(
                "positions.length must be a multiple of 3, was " + positions.length);
        }
        int count = positions.length / 3;
        if (count % 3 != 0) {
            throw new IllegalArgumentException(
                "VAT vertex count must be a multiple of 3 (expanded triangles), was " + count);
        }
        if (colors.length != count * 4) {
            throw new IllegalArgumentException(
                "colors.length must be vertexCount*4 = " + (count * 4) + ", was " + colors.length);
        }
        int influences = VatFormat.INFLUENCES;
        if (boneIds.length != count * influences || weights.length != count * influences) {
            throw new IllegalArgumentException(
                "boneIds/weights length must be vertexCount*" + influences + " = "
                    + (count * influences) + ", was " + boneIds.length + " / " + weights.length);
        }
        if (sourceVertex != null && sourceVertex.length != count) {
            throw new IllegalArgumentException(
                "sourceVertex length must be vertexCount = " + count + ", was " + sourceVertex.length);
        }
        // An EMPTY mask means "this mesh has no attachment vertices" — the representation the
        // export uses for ordinary meshes (MeshExport#stepVertices is never null, it is empty).
        // Rejecting it here made every MeshPlayer group of a normal export fail to build, so the
        // player drew nothing at all.
        if (stepVertices != null && stepVertices.length != 0 && stepVertices.length != count) {
            throw new IllegalArgumentException(
                "stepVertices length must be vertexCount = " + count + ", was " + stepVertices.length);
        }
        // UVs are optional: null means "no model texture". A present table must be complete,
        // otherwise a textured draw would silently sample the wrong texel for later vertices.
        if (uvs != null && uvs.length != count * UV_FLOATS_PER_VERTEX) {
            throw new IllegalArgumentException(
                "uvs length must be vertexCount*" + UV_FLOATS_PER_VERTEX + " = "
                    + (count * UV_FLOATS_PER_VERTEX) + ", was " + uvs.length);
        }
        // Palette indexes are optional as well, but a present table must describe every vertex —
        // a recolour maps it 1:1 onto the expanded triangle corners.
        if (paletteIndices != null && paletteIndices.length != count) {
            throw new IllegalArgumentException(
                "paletteIndices length must be vertexCount = " + count + ", was "
                    + paletteIndices.length);
        }

        this.positions = positions;
        this.colors = colors;
        this.boneIds = boneIds;
        this.weights = weights;
        this.sourceVertex = sourceVertex != null ? sourceVertex : new int[0];
        this.boneNames = boneNames != null ? boneNames : new String[0];
        this.stepVertices = stepVertices != null ? stepVertices : new boolean[0];
        this.uvs = uvs != null ? uvs : new float[0];
        this.paletteIndices = paletteIndices != null
            ? paletteIndices.clone() : new int[0];
        this.vertexCount = count;

        validateInfluences();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Number of expanded (per triangle corner) vertices. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Number of triangles ({@code vertexCount / 3}). */
    public int triangleCount() {
        return vertexCount / 3;
    }

    /** Number of bone influences stored per vertex ({@link VatFormat#INFLUENCES}). */
    public int influences() {
        return VatFormat.INFLUENCES;
    }

    /** Bone table the ids index into (may be empty). */
    public String[] boneNames() {
        return boneNames;
    }

    /** Bind-pose positions: 3 floats per vertex. Do not modify. */
    public float[] positions() {
        return positions;
    }

    /** Per-vertex RGBA colours: 4 floats per vertex. Do not modify. */
    public float[] colors() {
        return colors;
    }

    /** Bone ids: {@link VatFormat#INFLUENCES} per vertex. Do not modify. */
    public int[] boneIds() {
        return boneIds;
    }

    /** Normalized weights: {@link VatFormat#INFLUENCES} per vertex. Do not modify. */
    public float[] weights() {
        return weights;
    }

    /** Expanded vertex → source vertex mapping (empty when not provided). */
    public int[] sourceVertex() {
        return sourceVertex;
    }

    /** Per-expanded-vertex step mask (empty when the mesh has no attachments). */
    public boolean[] stepVertices() {
        return stepVertices;
    }

    /** True when at least one vertex must snap instead of blending between frames. */
    public boolean hasStepVertices() {
        for (boolean step : stepVertices) {
            if (step) return true;
        }
        return false;
    }

    /**
     * Per-vertex texture coordinates: {@value #UV_FLOATS_PER_VERTEX} floats ({@code u, v}) per
     * expanded vertex, or an empty array when the mesh has no UV data. Do not modify.
     */
    public float[] uvs() {
        return uvs;
    }

    /** True when the mesh carries per-vertex texture coordinates. */
    public boolean hasUvs() {
        return uvs.length >= UV_FLOATS_PER_VERTEX;
    }

    /**
     * Per-expanded-vertex colour <b>indexes</b> into the export's palette (one per vertex), or an
     * empty array when the colours of this mesh do not come from a palette (legacy {@code .vat}
     * files, blended clips). Used by
     * {@link VatInstancedMesh#refreshPalette(com.jb.rts.mesh.blend.ColorPalette)}. Do not modify.
     */
    public int[] paletteIndices() {
        return paletteIndices;
    }

    /**
     * True when this mesh can be recoloured from a palette:
     * {@link VatInstancedMesh#refreshPalette(com.jb.rts.mesh.blend.ColorPalette)} re-resolves the
     * {@code a_color} attribute from {@link #paletteIndices()}.
     */
    public boolean hasPaletteIndices() {
        return paletteIndices.length == vertexCount && vertexCount > 0;
    }

    /** Returns the zero-based index of {@code name} in the bone table, or {@code -1}. */
    public int indexOfBone(String name) {
        for (int i = 0; i < boneNames.length; i++) {
            if (name != null && name.equals(boneNames[i])) return i;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // GPU upload
    // -----------------------------------------------------------------------

    /**
     * Builds the interleaved float array uploaded as the static vertex buffer, using the exact
     * layout documented on this class ({@value VatFormat#FLOATS_PER_VERTEX} floats / 40 bytes per
     * vertex). Called <b>once</b> per mesh; the result is handed to {@code Mesh.setVertices}.
     */
    public float[] toInterleavedVertices() {
        return toInterleavedVertices(null);
    }

    /**
     * Same as {@link #toInterleavedVertices()}, but with the {@code a_color} slot resolved from
     * {@code palette} through {@link #paletteIndices()}.
     *
     * <p>This is the cheap <b>recolour</b> path: the geometry, bone ids and weights are copied
     * unchanged, only the packed colour of each vertex is looked up again. A
     * {@code null} palette — or a mesh without palette indexes — produces exactly
     * {@link #toInterleavedVertices()}. Indexes outside the palette become white, matching
     * {@code VatPalette.resolve}.
     *
     * @param palette the palette to resolve the per-vertex colours from (may be {@code null})
     */
    public float[] toInterleavedVertices(ColorPalette palette) {
        final int stride = VatFormat.FLOATS_PER_VERTEX;
        final int inf = VatFormat.INFLUENCES;
        float[] resolved = palette != null && hasPaletteIndices()
            ? VatPalette.resolve(paletteIndices, palette) : colors;
        float[] out = new float[vertexCount * stride];
        int o = 0;
        int i3 = 0;   // walk the 3-component arrays
        int i4 = 0;   // walk the 4-component arrays
        for (int v = 0; v < vertexCount; v++) {
            out[o++] = positions[i3];
            out[o++] = positions[i3 + 1];
            out[o++] = positions[i3 + 2];
            out[o++] = packColor(resolved[i4], resolved[i4 + 1], resolved[i4 + 2],
                resolved[i4 + 3]);
            for (int k = 0; k < inf; k++) out[o++] = boneIds[v * inf + k];
            for (int k = 0; k < inf; k++) out[o++] = weights[v * inf + k];
            i3 += 3;
            i4 += 4;
        }
        return out;
    }

    /**
     * Packs an RGBA colour ({@code 0..1} floats) into the float representation libgdx expects in
     * a {@code Usage.ColorPacked} vertex slot: the bytes are laid out ABGR in the 32-bit value so
     * that little-endian memory contains R, G, B, A in attribute order.
     *
     * <p>Delegates to {@link Color#toFloatBits(int, int, int, int)} so the encoding is
     * bit-identical to {@code SpriteBatch}/{@code ColorPacked} meshes elsewhere in libgdx.
     */
    public static float packColor(float r, float g, float b, float a) {
        return Color.toFloatBits(toByte(r), toByte(g), toByte(b), toByte(a));
    }

    private static int toByte(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /**
     * Verifies and normalizes the influence data: ids in range, weights finite/non-negative, and
     * each vertex's weights renormalized to exactly {@code 1.0}. Runs once at load time so the
     * vertex shader never has to guard against bad data.
     *
     * @throws IllegalArgumentException when the data cannot be skinned
     */
    private void validateInfluences() {
        final int inf = VatFormat.INFLUENCES;
        final int bones = boneNames.length;
        for (int v = 0; v < vertexCount; v++) {
            int base = v * inf;
            float sum = 0f;
            for (int k = 0; k < inf; k++) {
                int id = boneIds[base + k];
                float w = weights[base + k];
                if (id < 0 || (bones > 0 && id >= bones)) {
                    throw new IllegalArgumentException(
                        "Vertex " + v + " references bone id " + id
                            + " but the bone table has " + bones + " entries.");
                }
                if (!Float.isFinite(w) || w < 0f) {
                    throw new IllegalArgumentException(
                        "Vertex " + v + " has an invalid skin weight: " + w);
                }
                sum += w;
            }
            if (sum <= 1e-6f) {
                throw new IllegalArgumentException(
                    "Vertex " + v + " has no skin influence (weight sum 0). "
                        + "Unweighted vertices must be bound to a bind-pose bone with weight 1.");
            }
            if (Math.abs(sum - 1f) > 1e-6f) {
                float inv = 1f / sum;
                for (int k = 0; k < inf; k++) weights[base + k] *= inv;
            }
        }
    }

    @Override
    public String toString() {
        return "VatMeshData{vertices=" + vertexCount + ", triangles=" + triangleCount()
            + ", bones=" + boneNames.length + ", influences=" + VatFormat.INFLUENCES + "}";
    }
}