package com.jb.rts.mesh.blend;

/**
 * Immutable value object that describes a single blend operation sent from the CPU to the GPU.
 *
 * <p>Up to four mesh names and their corresponding blend weights can be specified.
 * Slots that are not needed should be left {@code null} (name) / {@code 0f} (weight).
 *
 * <p>The weights do not have to sum to 1 – the shader normalises them automatically.  However,
 * supplying pre-normalised weights avoids the extra divide in the shader.
 *
 * <pre>
 * // Blend 60 % idle, 40 % sway
 * MeshBlendCommand cmd = new MeshBlendCommand(
 *     "plant_idle_0", 0.6f,
 *     "plant_sway_0", 0.4f,
 *     null, 0f,
 *     null, 0f);
 * blender.setBlend(cmd);
 * </pre>
 *
 * <h3>Naming rules</h3>
 * It is the caller's responsibility to only pass mesh names that share the same prefix
 * (identical vertex and triangle counts).  See {@link BlendableMesh} for the full convention.
 */
public final class MeshBlendCommand {

    /** Maximum number of blend slots. */
    public static final int SLOTS = 4;

    /** Mesh names for each slot; {@code null} means the slot is inactive. */
    public final String[] names;

    /**
     * Blend weight for each slot.  Inactive slots ({@code names[i] == null}) should use
     * {@code 0f}.  The {@code MeshBlender} normalises these values before uploading them.
     */
    public final float[] weights;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Full constructor – all four slots.
     *
     * @param name0   name of mesh for slot 0, or {@code null}
     * @param weight0 blend weight for slot 0
     * @param name1   name of mesh for slot 1, or {@code null}
     * @param weight1 blend weight for slot 1
     * @param name2   name of mesh for slot 2, or {@code null}
     * @param weight2 blend weight for slot 2
     * @param name3   name of mesh for slot 3, or {@code null}
     * @param weight3 blend weight for slot 3
     */
    public MeshBlendCommand(
            String name0, float weight0,
            String name1, float weight1,
            String name2, float weight2,
            String name3, float weight3) {

        this.names   = new String[]{name0, name1, name2, name3};
        this.weights = new float[] {weight0, weight1, weight2, weight3};
    }

    /**
     * Convenience constructor for a two-way blend.
     *
     * @param name0   name of the first mesh
     * @param weight0 blend weight for the first mesh
     * @param name1   name of the second mesh
     * @param weight1 blend weight for the second mesh
     */
    public MeshBlendCommand(String name0, float weight0, String name1, float weight1) {
        this(name0, weight0, name1, weight1, null, 0f, null, 0f);
    }

    /**
     * Convenience constructor that shows a single mesh at full weight (no blending).
     *
     * @param name the sole mesh to display
     */
    public MeshBlendCommand(String name) {
        this(name, 1f, null, 0f, null, 0f, null, 0f);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the total weight across all active (non-null) slots.
     * Useful for pre-normalisation checks.
     */
    public float totalWeight() {
        float sum = 0f;
        for (int i = 0; i < SLOTS; i++) {
            if (names[i] != null) sum += weights[i];
        }
        return sum;
    }

    /** Returns the number of active (non-null, non-zero-weight) slots. */
    public int activeSlots() {
        int count = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (names[i] != null && weights[i] != 0f) count++;
        }
        return count;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MeshBlendCommand[");
        for (int i = 0; i < SLOTS; i++) {
            if (names[i] != null) {
                if (sb.length() > "MeshBlendCommand[".length()) sb.append(", ");
                sb.append(names[i]).append("×").append(String.format("%.3f", weights[i]));
            }
        }
        return sb.append("]").toString();
    }
}


