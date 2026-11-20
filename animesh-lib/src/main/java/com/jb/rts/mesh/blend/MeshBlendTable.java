package com.jb.rts.mesh.blend;

import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry that maps mesh names to {@link BlendableMesh} instances.
 *
 * <p>Meshes are loaded from the 2D mesheditor <em>export</em> format via
 * {@link #loadExport(FileHandle)}, which registers <b>all poses</b> contained in the file
 * (the rest pose plus every animation frame) under one shared {@link ColorPalette}.
 *
 * <pre>{@code
 * MeshBlendTable table = new MeshBlendTable();
 * MeshExport export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
 *
 * // Blending: pick any two poses of the same mesh (same prefix, same topology).
 * MeshBlenderGroup group = new MeshBlenderGroup(table, export.name);
 * group.setBlend(new MeshBlendCommand("tree_redwood_idle_0", 0.6f, "tree_redwood_idle_1", 0.4f));
 * }</pre>
 *
 * <h3>Colours</h3>
 * Colours live in {@link MeshExport#palette}, referenced by integer indexes. They are
 * <b>not</b> blended across frames; every pose of a mesh family shares the same indexes.
 * The user may replace palette colours freely (see {@link ColorPalette}).
 */
public class MeshBlendTable {

    private final Map<String, BlendableMesh> table = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    /**
     * Registers a pre-built {@link BlendableMesh} under its own name.
     *
     * @param mesh the mesh to register; its {@link BlendableMesh#name} is used as key
     * @throws IllegalArgumentException if a different mesh is already registered under that name
     */
    public void register(BlendableMesh mesh) {
        BlendableMesh existing = table.get(mesh.name);
        if (existing != null && existing != mesh) {
            throw new IllegalArgumentException(
                "A different mesh is already registered under name '" + mesh.name + "'.");
        }
        table.put(mesh.name, mesh);
    }

    /**
     * Constructs and registers a mesh from raw arrays.
     *
     * @param name             full mesh name ({@code prefix_poseName_poseIndex})
     * @param defaultPositions interleaved {@code [x,y,z]} of the base/rest pose
     * @param positions        interleaved {@code [dx,dy,dz]} offsets from default positions
     * @param colorIndices     per-vertex colour indexes
     * @param indices          triangle indices
     */
    public void register(String name, float[] defaultPositions, float[] positions,
                         int[] colorIndices, short[] indices) {
        register(new BlendableMesh(name, defaultPositions, positions, colorIndices, indices));
    }

    /**
     * Loads a mesheditor export file and registers every pose it contains.
     *
     * @param file the export JSON (e.g. {@code tree_redwood-export.json})
     * @return the parsed {@link MeshExport} (palette + registered meshes)
     */
    public MeshExport loadExport(FileHandle file) {
        MeshExport export = MeshExportLoader.load(file);
        for (BlendableMesh m : export.meshes) {
            register(m);
        }
        return export;
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /**
     * Returns the mesh registered under {@code name}, or {@code null} if not found.
     *
     * @param name full mesh name
     * @return the mesh, or {@code null}
     */
    public BlendableMesh get(String name) {
        return table.get(name);
    }

    /**
     * Returns the mesh registered under {@code name}.
     *
     * @param name full mesh name
     * @return the mesh (never {@code null})
     * @throws IllegalArgumentException if the name is not registered
     */
    public BlendableMesh getRequired(String name) {
        BlendableMesh mesh = table.get(name);
        if (mesh == null) {
            throw new IllegalArgumentException("No mesh registered under name '" + name + "'.");
        }
        return mesh;
    }

    /** Returns {@code true} if {@code name} is registered in this table. */
    public boolean contains(String name) {
        return table.containsKey(name);
    }

    /** Returns an unmodifiable view of all registered meshes. */
    public Collection<BlendableMesh> all() {
        return Collections.unmodifiableCollection(table.values());
    }

    /**
     * Returns all meshes whose {@link BlendableMesh#prefix()} equals the given prefix,
     * in registration order (this is the set that may be blended together). The returned
     * list is a live snapshot (not backed by the table).
     *
     * @param prefix the prefix to filter by (e.g. {@code "tree_redwood"})
     * @return ordered list of matching meshes; empty if none found
     */
    public List<BlendableMesh> byPrefix(String prefix) {
        List<BlendableMesh> result = new ArrayList<>();
        for (BlendableMesh m : table.values()) {
            if (m.prefix().equals(prefix)) result.add(m);
        }
        return result;
    }

    /** Number of meshes currently registered. */
    public int size() {
        return table.size();
    }
}
