package com.jb.rts.mesh.blend;

import java.nio.FloatBuffer;

/**
 * Per-instance data for {@link InstancedMeshBlenderGroup}.
 *
 * <p>Each instance specifies:
 * <ul>
 *   <li>up to four blend slots (indices into the group's ordered mesh list)</li>
 *   <li>a blend weight for each active slot</li>
 *   <li>a world-space position offset (added to the blended mesh coordinates)</li>
 * </ul>
 *
 * <h3>Building an instance from mesh names</h3>
 * <pre>{@code
 * MeshBlenderGroup group = ...;
 * MeshBlendInstance inst = MeshBlendInstance.fromCommand(
 *     group,
 *     new MeshBlendCommand("plant_idle_0", 0.7f, "plant_sway_0", 0.3f),
 *     worldX, worldY);
 * }</pre>
 *
 * <h3>Instance buffer layout</h3>
 * Ten floats per instance written by {@link #write(FloatBuffer)}:
 * <pre>[slot0, slot1, slot2, slot3,  w0, w1, w2, w3,  x, y]</pre>
 */
public final class MeshBlendInstance {

    /** Number of floats written to the instance buffer per instance. */
    public static final int FLOATS = 10;

    // -----------------------------------------------------------------------

    /** Blend slot index 0 – index into the group's ordered mesh list. */
    public int slot0;
    /** Blend slot index 1. */
    public int slot1;
    /** Blend slot index 2. */
    public int slot2;
    /** Blend slot index 3. */
    public int slot3;

    /** Blend weight for slot 0. */
    public float w0;
    /** Blend weight for slot 1. */
    public float w1;
    /** Blend weight for slot 2. */
    public float w2;
    /** Blend weight for slot 3. */
    public float w3;

    /** World-space X position offset added to the blended vertex positions. */
    public float x;
    /** World-space Y position offset added to the blended vertex positions. */
    public float y;

    // -----------------------------------------------------------------------
    // Constructors / factories
    // -----------------------------------------------------------------------

    /** Creates a zeroed instance (slot 0, weight 0, position (0,0)). */
    public MeshBlendInstance() {}

    /**
     * Creates a single-mesh instance at the given position (no blending).
     *
     * @param group the group that owns the mesh
     * @param name  full mesh name (must be in {@code group})
     * @param x     world-space X
     * @param y     world-space Y
     */
    public static MeshBlendInstance single(MeshBlenderGroup group, String name, float x, float y) {
        return fromCommand(group, new MeshBlendCommand(name), x, y);
    }

    /**
     * Creates an instance from a {@link MeshBlendCommand}, resolving mesh names to group indices.
     *
     * @param group   the group whose {@link MeshBlenderGroup#indexOf(String)} is used
     * @param command blend description (names + weights)
     * @param x       world-space X
     * @param y       world-space Y
     * @return populated instance ready for {@link InstancedMeshBlenderGroup#add}
     * @throws IllegalArgumentException if a name is not found in {@code group}
     */
    public static MeshBlendInstance fromCommand(
            MeshBlenderGroup group, MeshBlendCommand command, float x, float y) {

        MeshBlendInstance inst = new MeshBlendInstance();
        inst.x = x;
        inst.y = y;

        for (int i = 0; i < MeshBlendCommand.SLOTS; i++) {
            String name = command.names[i];
            int    slot = 0;
            float  w    = 0f;
            if (name != null) {
                int resolved = group.indexOf(name);
                if (resolved < 0) {
                    throw new IllegalArgumentException(
                        "Mesh '" + name + "' is not part of the supplied group.");
                }
                slot = resolved;
                w    = command.weights[i];
            }
            switch (i) {
                case 0 -> { inst.slot0 = slot; inst.w0 = w; }
                case 1 -> { inst.slot1 = slot; inst.w1 = w; }
                case 2 -> { inst.slot2 = slot; inst.w2 = w; }
                case 3 -> { inst.slot3 = slot; inst.w3 = w; }
            }
        }
        return inst;
    }

    // -----------------------------------------------------------------------

    /**
     * Writes this instance's data into {@code buf} at the current position.
     * Advances the buffer position by {@value #FLOATS}.
     *
     * <p>Layout: {@code [slot0, slot1, slot2, slot3,  w0, w1, w2, w3,  x, y]}
     *
     * @param buf the target buffer (must have at least {@value #FLOATS} remaining)
     */
    public void write(FloatBuffer buf) {
        buf.put((float) slot0).put((float) slot1).put((float) slot2).put((float) slot3);
        buf.put(w0).put(w1).put(w2).put(w3);
        buf.put(x).put(y);
    }
}

