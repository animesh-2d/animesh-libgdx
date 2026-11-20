package com.jb.rts.mesh.blend;

/**
 * Immutable data holder for a single <em>pose</em> of a mesh that can take part in a blend.
 *
 * <p>Meshes are loaded from the mesheditor export format (see {@link MeshExportLoader}),
 * which describes one static topology (vertices, objects, triangles and their colour indexes)
 * plus any number of animation frames. Each frame/pose becomes one {@link BlendableMesh}
 * with the <em>same</em> {@link #indices} but potentially different {@link #positions}
 * (offsets from the shared {@link #defaultPositions}) and potentially different
 * {@link #colorIndices} (per-frame colour overrides).
 *
 * <h3>Data model — offsets, not absolute positions</h3>
 * <ul>
 *   <li>{@link #defaultPositions} — interleaved {@code [x, y, z, …]} per vertex for the
 *       <b>base / rest pose</b>. This is the same for every pose of a mesh family.</li>
 *   <li>{@link #positions} — interleaved {@code [dx, dy, dz, …]} per vertex; these are
 *       <b>offsets</b> from {@link #defaultPositions}.  The absolute vertex position at
 *       runtime is {@code defaultPositions[i] + positions[i]}.  For the rest pose the
 *       offsets are all zero.</li>
 *   <li>{@link #colorIndices} — one integer per vertex; an index into a {@link ColorPalette}.
 *       <b>May differ between poses.</b> When the export's animation frame includes a
 *       {@code "colors"} array, that frame's colour indices override the mesh-default
 *       topology colours. Loaders that only have the default colours fall back to the
 *       shared {@code colorIndices} (identical for every pose, backward-compatible).</li>
 *   <li>{@link #indices} — the triangle list referencing vertices.</li>
 * </ul>
 *
 * <p>The same <em>shared</em> source vertex may carry a different colour in different triangles
 * (the mesheditor assigns colours per triangle-corner). To honour that without Gouraud-bleeding
 * neighbours into each other, {@link MeshExportLoader} <b>expands</b> the mesh so each
 * triangle-corner becomes its own vertex with its own colour index. Therefore
 * {@link #vertexCount()} may exceed the number of vertices written in the source JSON, and
 * {@link #indices} is the trivial sequence {@code [0,1,2,3,…]}.
 *
 * <h3>Naming</h3>
 * Full name = {@code <prefix>_<poseName>_<poseIndex>}; the {@link #prefix()} (everything before
 * the first {@code _}) must be shared by every pose that may be blended together, because all
 * poses must share the same vertex count and index buffer (topology). Colour indices may differ.
 */
public final class BlendableMesh {

    /**
     * Position floats stored per vertex: {@code x, y, z}. The z coordinate is
     * used for depth ordering / z-sorting and is blended across poses just like
     * x and y.
     */
    public static final int POSITION_FLOATS = 3;

    /**
     * Full mesh name – must follow the {@code prefix_poseName_poseIndex} convention.
     */
    public final String name;

    /**
     * The mesh prefix (export name), e.g. {@code "tree_redwood"}. Stored explicitly
     * so it never has to be re-parsed from the name string.
     */
    public final String prefix;

    /**
     * The pose / animation name, e.g. {@code "rest"}, {@code "idle"}, {@code "wind"}.
     */
    public final String poseName;

    /**
     * The zero-based frame index within its animation, e.g. {@code 0}.
     */
    public final int poseIndex;

    /**
     * Interleaved base / rest-pose position data {@code [x, y, z, x, y, z, …]}.
     * All poses of a mesh family share the same {@code defaultPositions}.
     * Length is {@code vertexCount() * POSITION_FLOATS}.
     */
    public final float[] defaultPositions;

    /**
     * Interleaved offsets {@code [dx, dy, dz, dx, dy, dz, …]} from
     * {@link #defaultPositions}.  The absolute vertex position at runtime is
     * {@code defaultPositions[i] + positions[i]}.
     * Length is {@code vertexCount() * POSITION_FLOATS}.
     * For the rest pose these are all zero.
     */
    public final float[] positions;

    /**
     * One colour index per vertex, into a {@link ColorPalette}.
     * Length equals {@link #vertexCount()}. May differ between poses
     * (per-frame colour-index overrides are loaded when present in the export JSON).
     */
    public final int[] colorIndices;

    /**
     * Triangle index buffer referencing the expanded vertices.
     */
    public final short[] indices;

    // -----------------------------------------------------------------------

    /**
     * Constructs a pose of a blendable mesh.
     *
     * @param name             full mesh name ({@code prefix_poseName_poseIndex})
     * @param defaultPositions interleaved {@code [x,y,z]} of the base/rest pose
     *                         (shared by every pose of this family)
     * @param positions        interleaved {@code [dx,dy,dz]} offsets from default positions
     *                         (zero for the rest pose)
     * @param colorIndices     per-vertex colour indexes (one per vertex, into a {@link ColorPalette})
     * @param indices          triangle index list
     */
    public BlendableMesh(String name, float[] defaultPositions, float[] positions,
                         int[] colorIndices, short[] indices) {
        this(name, defaultPositions, positions, colorIndices, indices,
                parsePrefix(name), parsePoseName(name), parsePoseIndex(name));
    }

    /**
     * Full constructor that takes already-known prefix, poseName and poseIndex
     * explicitly, avoiding any string parsing. Use this when the values are already
     * known (e.g. from {@link MeshExportLoader}).
     */
    public BlendableMesh(String name, float[] defaultPositions, float[] positions,
                         int[] colorIndices, short[] indices,
                         String prefix, String poseName, int poseIndex) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mesh name must not be null or blank.");
        }
        if (defaultPositions == null || defaultPositions.length == 0
                || defaultPositions.length % POSITION_FLOATS != 0) {
            throw new IllegalArgumentException(
                    "defaultPositions length must be a non-zero multiple of " + POSITION_FLOATS + ".");
        }
        if (positions == null || positions.length != defaultPositions.length) {
            throw new IllegalArgumentException(
                    "positions length (" + (positions == null ? 0 : positions.length)
                            + ") must equal defaultPositions length (" + defaultPositions.length + ").");
        }
        int verts = defaultPositions.length / POSITION_FLOATS;
        if (colorIndices == null || colorIndices.length != verts) {
            throw new IllegalArgumentException(
                    "colorIndices length (" + (colorIndices == null ? 0 : colorIndices.length)
                            + ") must equal vertex count (" + verts + ").");
        }
        if (indices == null || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices length must be a non-zero multiple of 3.");
        }
        this.name = name;
        this.prefix = prefix != null ? prefix : parsePrefix(name);
        this.poseName = poseName != null ? poseName : parsePoseName(name);
        this.poseIndex = poseIndex >= 0 ? poseIndex : parsePoseIndex(name);
        this.defaultPositions = defaultPositions;
        this.positions = positions;
        this.colorIndices = colorIndices;
        this.indices = indices;
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // Name decomposition helpers (public accessors delegate to stored fields)
    // -----------------------------------------------------------------------

    /**
     * Returns the prefix (export name), parsed from the full name.
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Returns the pose/animation name, parsed from the full name.
     */
    public String poseName() {
        return poseName;
    }

    /**
     * Returns the zero-based frame index, parsed from the full name.
     */
    public int poseIndex() {
        return poseIndex;
    }

    // -----------------------------------------------------------------------
    // Static parsing helpers (used by constructor; also usable independently)
    // -----------------------------------------------------------------------

    /**
     * Extracts the prefix (everything before the pose name) from a
     * {@code <prefix>_<poseName>_<poseIndex>} name.
     */
    private static String parsePrefix(String name) {
        int last = name.lastIndexOf('_');
        if (last < 0) return name;
        int secondLast = name.lastIndexOf('_', last - 1);
        if (secondLast < 0) return name.substring(0, last);
        return name.substring(0, secondLast);
    }

    /**
     * Extracts the pose name (middle segment) from a
     * {@code <prefix>_<poseName>_<poseIndex>} name.
     */
    private static String parsePoseName(String name) {
        int last = name.lastIndexOf('_');
        if (last < 0) return "";
        int secondLast = name.lastIndexOf('_', last - 1);
        if (secondLast < 0) return "";
        return name.substring(secondLast + 1, last);
    }

    /**
     * Extracts the pose index (last segment) from a
     * {@code <prefix>_<poseName>_<poseIndex>} name.
     */
    private static int parsePoseIndex(String name) {
        int last = name.lastIndexOf('_');
        if (last < 0) return 0;
        String tail = name.substring(last + 1);
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Topology / metrics
    // -----------------------------------------------------------------------

    /**
     * Number of vertices stored in {@link #defaultPositions} and {@link #positions}.
     */
    public int vertexCount() {
        return positions.length / POSITION_FLOATS;
    }

    /**
     * Number of triangles (index buffer length / 3).
     */
    public int triangleCount() {
        return indices.length / 3;
    }

    /**
     * Returns {@code true} if {@code other} shares the identical vertex count and index buffer.
     * Colour indices may differ between poses (per-frame colour overrides) and are NOT checked.
     * Only meshes that pass this check may be blended together.
     */
    public boolean sameTopology(BlendableMesh other) {
        if (other.vertexCount() != vertexCount() || other.indices.length != indices.length) {
            return false;
        }
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] != other.indices[i]) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "BlendableMesh{name='" + name + "', vertices=" + vertexCount()
                + ", triangles=" + triangleCount() + "}";
    }
}
