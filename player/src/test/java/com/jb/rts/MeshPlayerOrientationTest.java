package com.jb.rts;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the y orientation between the mesheditor export and the player's world (x right, y up).
 *
 * <p>The exporter already negates y (cartesian y-up) and flags it with {@code "yUp"}; such an
 * export must reach the GPU untouched. Only y-down exports ({@code "yUp": false}) may be
 * mirrored, and then exactly once — a second mirror (or one mirror per pose) turns a walking
 * mesh into a mirrored one, which is what made {@code dino_longneck-export.json} look backwards.
 *
 * <p>The fixture has <b>two</b> poses (rest + one frame) on purpose: every pose shares the same
 * {@code defaultPositions} array, so flipping per pose cancelled out for even pose counts.
 */
class MeshPlayerOrientationTest {

    /**
     * Minimal export: one triangle with distinct y and z values, plus one animation frame whose
     * middle vertex is offset by {@code (1, -2, 0.5)}.
     */
    private static final String EXPORT = """
            {
              "name": "t",
              "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
              "vertices": [
                { "x": 0.0, "y": 0.0, "z": 1.0 },
                { "x": 2.0, "y": 3.0, "z": 2.0 },
                { "x": 4.0, "y": 0.0, "z": 3.0 }
              ],
              "objects": [ { "name": "o", "triangles": [
                { "vertices": [0, 1, 2], "colors": [0, 0, 0] }
              ] } ],
              "animations": [ { "name": "walk", "frames": [
                { "name": "f0", "offsets": [
                  { "x": 0.0, "y": 0.0,  "z": 0.0 },
                  { "x": 1.0, "y": -2.0, "z": 0.5 },
                  { "x": 0.0, "y": 0.0,  "z": 0.0 } ] }
              ] } ]
            }
            """;

    private static MeshExport load(String yUpField) {
        String json = yUpField == null
                ? EXPORT
                : EXPORT.replace("\"name\": \"t\",", "\"name\": \"t\", " + yUpField);
        return MeshExportLoader.loadFromString(json);
    }

    /** Absolute (base + offset) y of the middle vertex of the rest pose / the first frame. */
    private static float restY(MeshExport e) {
        BlendableMesh rest = e.meshes.get(0);
        return rest.defaultPositions[4] + rest.positions[4]; // vertex 1 -> floats 3..5
    }

    private static float frameY(MeshExport e) {
        BlendableMesh frame = e.meshes.get(1);
        return frame.defaultPositions[4] + frame.positions[4];
    }

    @Test
    void yUpExportIsUsedAsIs() {
        MeshExport e = load("\"yUp\": true,");
        assertTrue(e.yUp);

        MeshPlayer.convertExportToWorldYUp(e);

        // Base stays cartesian: the middle vertex is at y = 3 (up), not -3.
        assertEquals(3f, restY(e), 1e-6f);
        // The frame offset (-2, i.e. 2 units down) is not mirrored either.
        assertEquals(1f, frameY(e), 1e-6f);
        // x and z are never touched.
        assertEquals(0f, e.meshes.get(0).defaultPositions[0], 1e-6f);
        assertEquals(2f, e.meshes.get(0).defaultPositions[5], 1e-6f);
        assertEquals(1f, e.meshes.get(1).positions[3], 1e-6f); // offset x of vertex 1
        assertEquals(0.5f, e.meshes.get(1).positions[5], 1e-6f); // offset z of vertex 1
    }

    @Test
    void exportWithoutTheFlagIsTreatedAsYUp() {
        MeshExport e = load(null);
        assertTrue(e.yUp);

        MeshPlayer.convertExportToWorldYUp(e);

        // Same as an explicit y-up export — the dino file has no flag either.
        assertEquals(3f, restY(e), 1e-6f);
        assertEquals(1f, frameY(e), 1e-6f);
    }

    @Test
    void yDownExportIsMirroredExactlyOnce() {
        MeshExport e = load("\"yUp\": false,");
        assertFalse(e.yUp);

        MeshPlayer.convertExportToWorldYUp(e);

        // Base mirrored once: y = 3 becomes -3. Flipping once per pose (2 poses here) would
        // leave the shared base array unchanged and the mesh would keep its old orientation.
        assertEquals(-3f, restY(e), 1e-6f);
        // Offset mirrored too, so base + offset stays consistent for the frame: -3 + 2 = -1.
        assertEquals(-1f, frameY(e), 1e-6f);
        // x and z stay untouched.
        assertEquals(0f, e.meshes.get(0).defaultPositions[0], 1e-6f);
        assertEquals(2f, e.meshes.get(0).defaultPositions[5], 1e-6f);
        assertEquals(1f, e.meshes.get(1).positions[3], 1e-6f); // offset x of vertex 1
        assertEquals(0.5f, e.meshes.get(1).positions[5], 1e-6f); // offset z of vertex 1
    }
}
