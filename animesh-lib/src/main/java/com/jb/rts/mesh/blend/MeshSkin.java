package com.jb.rts.mesh.blend;

/**
 * Skinning half of one mesheditor export: the bone table in VAT texture-row order plus the
 * resolved skin influences of every <b>expanded</b> vertex (one triangle corner each, see
 * {@link MeshExportLoader}).
 *
 * <p>Produced by {@link MeshExportLoader} from the export's {@code "skin"} block (export
 * version&nbsp;4) or from the legacy top-level {@code "bones"} weights (version&nbsp;2/3). The
 * loader resolves the influences exactly like the mesheditor's {@code VatExporter}:
 * <ul>
 *   <li>only bones that actually influence a vertex get a row (plus the optional
 *       {@link #bindPoseRow} identity row for unweighted vertices),</li>
 *   <li>each vertex keeps its {@link #influences} strongest bones, renormalized to sum&nbsp;1,</li>
 *   <li>{@link #sourceVertex} maps every expanded corner back to the shared vertex it came
 *       from (useful for tooling; may be {@code null} in hand-written files).</li>
 * </ul>
 *
 * <p>The per-frame matrices live in {@link MeshSkinClip}; the bind transforms in
 * {@link #bindCenterX} … {@link #bindDirY} are kept for validation/tooling and so a renderer can
 * still rebuild matrices from centre/direction keyframes.
 */
public final class MeshSkin {

    /** Bone names in texture-row order (may include the synthetic bind-pose row last). */
    public final String[] boneNames;

    /** Influences stored per vertex (matches {@code VatFormat.INFLUENCES}). */
    public final int influences;

    /** {@link #influences} bone row indexes per expanded vertex. */
    public final int[] boneIds;

    /** {@link #influences} weights per expanded vertex, normalized to sum 1. */
    public final float[] weights;

    /** Expanded vertex → shared vertex index, or {@code null} when unknown. */
    public final int[] sourceVertex;

    /** Row that holds identity matrices for unweighted vertices, or {@code -1}. */
    public final int bindPoseRow;

    /** Bind-pose bone centres (export space) in {@link #boneNames} order. */
    public final float[] bindCenterX;
    public final float[] bindCenterY;

    /** Bind-pose bone directions (export space, no origin offset) in {@link #boneNames} order. */
    public final float[] bindDirX;
    public final float[] bindDirY;

    /**
     * Creates skin data.
     *
     * @throws IllegalArgumentException when the arrays disagree on sizes
     */
    public MeshSkin(String[] boneNames, int influences, int[] boneIds, float[] weights,
                    int[] sourceVertex, int bindPoseRow,
                    float[] bindCenterX, float[] bindCenterY,
                    float[] bindDirX, float[] bindDirY) {
        if (boneNames == null || boneNames.length == 0) {
            throw new IllegalArgumentException("A skin needs at least one bone row.");
        }
        if (influences <= 0) {
            throw new IllegalArgumentException("influences must be > 0, was " + influences);
        }
        if (boneIds == null || weights == null || boneIds.length != weights.length
                || boneIds.length % influences != 0) {
            throw new IllegalArgumentException(
                "boneIds/weights must have the same length and be a multiple of " + influences);
        }
        int boneCount = boneNames.length;
        if (bindPoseRow < -1 || bindPoseRow >= boneCount) {
            throw new IllegalArgumentException(
                "bindPoseRow " + bindPoseRow + " is outside [0," + boneCount + ").");
        }
        if (bindCenterX == null || bindCenterY == null
                || bindDirX == null || bindDirY == null
                || bindCenterX.length != boneCount || bindCenterY.length != boneCount
                || bindDirX.length != boneCount || bindDirY.length != boneCount) {
            throw new IllegalArgumentException(
                "The bind transform arrays must hold one entry per bone row.");
        }
        if (sourceVertex != null && sourceVertex.length != boneIds.length / influences) {
            throw new IllegalArgumentException(
                "sourceVertex must hold one entry per expanded vertex.");
        }
        this.boneNames = boneNames;
        this.influences = influences;
        this.boneIds = boneIds;
        this.weights = weights;
        this.sourceVertex = sourceVertex;
        this.bindPoseRow = bindPoseRow;
        this.bindCenterX = bindCenterX;
        this.bindCenterY = bindCenterY;
        this.bindDirX = bindDirX;
        this.bindDirY = bindDirY;
    }

    /** Number of bone rows (texture height). */
    public int boneCount() {
        return boneNames.length;
    }

    /** Number of expanded vertices described by {@link #boneIds}/{@link #weights}. */
    public int vertexCount() {
        return boneIds.length / influences;
    }

    /**
     * Mirrors the bind transforms on y ({@code y -> -y}) so they match geometry that was flipped
     * from a y-down export into world y-up space (see {@code MeshPlayer}).
     */
    public void flipY() {
        for (int i = 0; i < boneNames.length; i++) {
            bindCenterY[i] = -bindCenterY[i];
            bindDirY[i] = -bindDirY[i];
        }
    }
}
