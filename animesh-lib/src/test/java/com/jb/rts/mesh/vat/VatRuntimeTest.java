package com.jb.rts.mesh.vat;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Matrix4;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the runtime side of the VAT pipeline: texture geometry, keyframe timing, the static
 * vertex layout and the binary loader's validation. Everything here is GL-free, so it runs
 * headless.
 */
class VatRuntimeTest {

    private static final float EPS = 1e-6f;

    // =======================================================================
    // VatFormat
    // =======================================================================

    @Test
    void format_mapsFourTexelsPerFrameAndOneRowPerBone() {
        assertEquals(12, VatFormat.textureWidth(3));
        assertEquals(5, VatFormat.textureHeight(5));
        assertEquals(3 * 5 * 16, VatFormat.matrixFloatCount(5, 3));
        assertEquals((2 * 3 + 1) * 16, VatFormat.matrixFloatOffset(2, 1, 3));
        assertEquals(9, VatFormat.matrixTexelX(2, 1));
        assertEquals(1024, VatFormat.maxFrames(4096));
        assertEquals(10, VatFormat.FLOATS_PER_VERTEX, "3 pos + 1 colour + 3 ids + 3 weights");
    }

    // =======================================================================
    // VatAnimationData: timing
    // =======================================================================

    @Test
    void frameAt_interpolatesBetweenKeyframeTimes() {
        VatAnimationData anim = new VatAnimationData("walk", 2, 3, 24f, false,
            new float[]{0f, 0.5f, 1.5f}, new float[2 * 3 * 16]);

        assertEquals(0f, anim.frameAt(-1f), EPS);
        assertEquals(0f, anim.frameAt(0f), EPS);
        assertEquals(0.5f, anim.frameAt(0.25f), EPS);
        assertEquals(1f, anim.frameAt(0.5f), EPS);
        assertEquals(1.5f, anim.frameAt(1.0f), EPS);
        assertEquals(2f, anim.frameAt(99f), EPS);
        assertEquals(1.5f, anim.duration(), EPS);
        assertEquals(0.5f, anim.timeOfFrame(1), EPS);
    }

    @Test
    void frameAt_usesFpsForUniformClips() {
        VatAnimationData anim = VatAnimationData.uniform("idle", 1, 10, 20f, false, new float[160]);
        assertEquals(0f, anim.frameAt(0f), EPS);
        assertEquals(2f, anim.frameAt(0.1f), EPS);
        assertEquals(9f, anim.frameAt(10f), EPS, "clamped to the last frame");
        assertEquals(0.45f, anim.duration(), EPS);
    }

    @Test
    void wrapFrame_loopsOrClamps() {
        VatAnimationData loop = VatAnimationData.uniform("loop", 1, 4, 30f, true, new float[64]);
        assertEquals(0.5f, loop.wrapFrame(4.5f), EPS);
        assertEquals(3.5f, loop.wrapFrame(-0.5f), EPS);

        VatAnimationData once = VatAnimationData.uniform("once", 1, 4, 30f, false, new float[64]);
        assertEquals(3f, once.wrapFrame(9f), EPS);
        assertEquals(0f, once.wrapFrame(-2f), EPS);
    }

    @Test
    void boneMatrix_readsTheColumnMajorTextureLayout() {
        float[] matrices = new float[VatFormat.matrixFloatCount(2, 3)];
        for (int i = 0; i < matrices.length; i++) matrices[i] = i;

        VatAnimationData anim = VatAnimationData.uniform("a", 2, 3, 24f, false, matrices);
        Matrix4 m = anim.boneMatrix(1, 2, new Matrix4());

        int offset = VatFormat.matrixFloatOffset(1, 2, 3);
        for (int i = 0; i < 16; i++) {
            assertEquals(offset + i, m.val[i], EPS);
        }
        // Column-major: translation lives in val[12], val[13].
        assertEquals(offset + 12, m.val[Matrix4.M03], EPS);
        assertEquals(offset + 13, m.val[Matrix4.M13], EPS);
    }

    // =======================================================================
    // VatMeshData: layout + validation
    // =======================================================================

    @Test
    void meshData_interleavedVertexLayout() {
        float[] positions = {0, 0, 0, 10, 0, 0, 0, 10, 0};
        float[] colors = {
            1, 0, 0, 1f,
            0, 1, 0, 1f,
            0, 0, 1, 1f
        };
        int[] ids = {0, 0, 0, 1, 1, 1, 0, 1, 0};
        float[] weights = {1, 0, 0, 1, 0, 0, 0.5f, 0.5f, 0};
        VatMeshData mesh = new VatMeshData(positions, colors, ids, weights, null,
            new String[]{"a", "b"});

        float[] v = mesh.toInterleavedVertices();
        assertEquals(VatFormat.FLOATS_PER_VERTEX * 3, v.length);

        // Vertex 2: (0,10,0), blue, bones (0,1,0), weights (0.5,0.5,0)
        int o = 2 * VatFormat.FLOATS_PER_VERTEX;
        assertEquals(0f, v[o]);
        assertEquals(10f, v[o + 1]);
        assertEquals(0f, v[o + 2]);
        assertEquals(Color.toFloatBits(0, 0, 255, 255), v[o + 3]);
        assertEquals(0f, v[o + 4]);
        assertEquals(1f, v[o + 5]);
        assertEquals(0.5f, v[o + 7], EPS);
        assertEquals(0.5f, v[o + 8], EPS);
    }

    @Test
    void meshData_normalizesWeightsInPlace() {
        VatMeshData mesh = new VatMeshData(
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
            new int[]{0, 1, 0, 0, 1, 0, 0, 1, 0},
            new float[]{2f, 2f, 0f, 2f, 2f, 0f, 2f, 2f, 0f},
            null, new String[]{"a", "b"});

        assertEquals(0.5f, mesh.weights()[0], EPS);
        assertEquals(0.5f, mesh.weights()[1], EPS);
    }

    @Test
    void meshData_rejectsUnweightedAndOutOfRangeBones() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] colors = new float[12];
        assertThrows(IllegalArgumentException.class, () -> new VatMeshData(
            positions, colors, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0},
            new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0}, null, new String[]{"a"}),
            "zero weights cannot be skinned");

        assertThrows(IllegalArgumentException.class, () -> new VatMeshData(
            positions, colors, new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0},
            new float[]{1, 0, 0, 1, 0, 0, 1, 0, 0}, null, new String[]{"a"}),
            "bone id out of range");
    }

    // =======================================================================
    // VatFile: binary validation
    // =======================================================================

    @Test
    void file_readRejectsBadMagicAndVersion() throws IOException {
        assertThrows(RuntimeException.class, () -> VatFile.read(new byte[40], "bad.vat"));
        assertThrows(RuntimeException.class,
            () -> VatFile.read(header(0xDEAD, VatFormat.VERSION, 1, 1, 3, 0),
                "bad-magic.vat"));
        assertThrows(RuntimeException.class,
            () -> VatFile.read(header(VatFormat.MAGIC, 99, 1, 1, 3, 0), "old-version.vat"));
    }

    @Test
    void file_writeThenRead_roundTripsAllArrays() {
        float[] matrices = new float[VatFormat.matrixFloatCount(1, 2)];
        for (int i = 0; i < matrices.length; i++) matrices[i] = i * 0.25f;

        VatAnimationData anim = new VatAnimationData("idle", 1, 2, 24f, true,
            new float[]{0f, 0.5f}, matrices);
        VatMeshData mesh = new VatMeshData(
            new float[]{0, 0, 0, 2, 0, 0, 0, 2, 0},
            new float[]{1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1},
            new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0},
            new float[]{1, 0, 0, 1, 0, 0, 1, 0, 0},
            null, new String[]{"root"});

        File temp;
        try {
            temp = File.createTempFile("vat-roundtrip", ".vat");
            temp.deleteOnExit();
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        VatFile.write(new FileHandle(temp), mesh, anim);
        VatFile.Content loaded = VatFile.load(new FileHandle(temp));

        assertArrayEquals(matrices, loaded.animation().boneMatrices());
        assertArrayEquals(mesh.positions(), loaded.mesh().positions());
        assertArrayEquals(mesh.colors(), loaded.mesh().colors());
        assertArrayEquals(mesh.boneIds(), loaded.mesh().boneIds());
        assertArrayEquals(mesh.weights(), loaded.mesh().weights());
        assertArrayEquals(new float[]{0f, 0.5f}, loaded.animation().frameTimes());
        assertTrue(loaded.animation().looping());
        assertEquals("idle", loaded.animation().name());
        assertArrayEquals(new String[]{"root"}, loaded.mesh().boneNames());
        assertFalse(loaded.mesh().toString().isEmpty());
    }

    private static byte[] header(int magic, int version, int bones, int frames,
                                 int vertices, int meta) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(magic);
            out.writeInt(version);
            out.writeInt(bones);
            out.writeInt(frames);
            out.writeInt(vertices);
            out.writeInt(vertices / 3);
            out.writeFloat(24f);
            out.writeInt(0);
            out.writeInt(VatFormat.INFLUENCES);
            out.writeInt(meta);
        }
        return bytes.toByteArray();
    }
}