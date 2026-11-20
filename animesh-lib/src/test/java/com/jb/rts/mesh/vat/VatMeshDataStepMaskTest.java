package com.jb.rts.mesh.vat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the optional per-vertex <b>step</b> mask of {@link VatMeshData} (attachment
 * geometry that must not be blended between animation frames).
 *
 * <p>Regression background: an export without attachments carries an <em>empty</em> mask
 * ({@code MeshExport#stepVertices} is documented as "empty, never null"), and the loader
 * produces {@code new boolean[0]}. {@link VatMeshData} rejected that as a length mismatch,
 * which made <b>every</b> animation group of the MeshPlayer fail to build — the player
 * loaded the file, logged three errors and drew nothing.</p>
 */
class VatMeshDataStepMaskTest {

    /** One triangle = three expanded vertices. */
    private static final int VERTICES = 3;

    private static VatMeshData meshData(boolean[] stepVertices) {
        int influences = VatFormat.INFLUENCES;
        float[] positions = new float[VERTICES * 3];
        float[] colors = new float[VERTICES * 4];
        int[] boneIds = new int[VERTICES * influences];
        float[] weights = new float[VERTICES * influences];
        for (int v = 0; v < VERTICES; v++) {
            weights[v * influences] = 1f;
        }
        return new VatMeshData(positions, colors, boneIds, weights, null,
                new String[]{"bone"}, stepVertices);
    }

    @Test
    void emptyStepMaskMeansNoAttachments() {
        // Exactly what MeshExportLoader produces for an export without attachment geometry.
        VatMeshData data = assertDoesNotThrow(() -> meshData(new boolean[0]),
                "an empty step mask must be accepted as 'no attachments'");

        assertEquals(0, data.stepVertices().length);
        assertFalse(data.hasStepVertices(), "an empty mask never reports step vertices");
    }

    @Test
    void nullStepMaskMeansNoAttachments() {
        assertFalse(meshData(null).hasStepVertices());
    }

    @Test
    void fullStepMaskIsKept() {
        VatMeshData data = meshData(new boolean[]{true, false, false});

        assertEquals(VERTICES, data.stepVertices().length);
        assertTrue(data.hasStepVertices(), "one flagged attachment vertex is a step vertex");
        assertTrue(data.stepVertices()[0]);
    }

    @Test
    void wrongLengthStepMaskIsStillRejected() {
        // A half-filled mask would silently snap the wrong vertices — fail loudly instead.
        assertThrows(IllegalArgumentException.class, () -> meshData(new boolean[]{true, false}));
    }
}
