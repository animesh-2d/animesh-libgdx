package com.jb.rts.mesh.vat;

import com.badlogic.gdx.files.FileHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the multi-clip {@code .vat} container (magic {@code VAT2}): the static mesh is stored
 * once, every clip keeps its own name/timing/matrices, and legacy version-1 files still load.
 */
class VatFileContainerTest {

    private static VatMeshData mesh() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] colors = {1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1};
        int[] ids = {0, 0, 0, 1, 1, 1, 0, 1, 0};
        float[] weights = {1, 0, 0, 1, 0, 0, 0.5f, 0.5f, 0};
        return new VatMeshData(positions, colors, ids, weights, null, new String[]{"a", "b"});
    }

    private static VatAnimationData clip(String name, int frameCount) {
        float[] matrices = new float[VatFormat.matrixFloatCount(2, frameCount)];
        for (int i = 0; i < matrices.length; i++) matrices[i] = i * 0.25f;
        float[] times = new float[frameCount];
        for (int i = 0; i < frameCount; i++) times[i] = i * 0.1f;
        return new VatAnimationData(name, 2, frameCount, 10f, true, times, matrices);
    }

    @Test
    void multiClipContainerRoundTripsThroughAFile(@TempDir File dir) {
        VatMeshData mesh = mesh();
        VatAnimationData walk = clip("walk", 3);
        VatAnimationData idle = clip("idle", 2);

        FileHandle file = new FileHandle(new File(dir, "dino.vat"));
        VatFile.writeAll(file, mesh, List.of(walk, idle));

        VatFile.FileContent loaded = VatFile.loadAll(file);
        assertEquals(2, loaded.animations().size(), "both clips must survive the round trip");
        assertEquals("walk", loaded.animations().get(0).name());
        assertEquals(3, loaded.animations().get(0).frameCount());
        assertEquals("idle", loaded.animations().get(1).name());
        assertEquals(2, loaded.animations().get(1).frameCount());
        assertTrue(loaded.animations().get(0).looping());
        assertArrayEquals(walk.frameTimes(), loaded.animations().get(0).frameTimes(), 1e-6f);
        assertArrayEquals(walk.boneMatrices(), loaded.animations().get(0).boneMatrices(), 1e-6f);

        assertEquals(mesh.vertexCount(), loaded.mesh().vertexCount());
        assertEquals("b", loaded.mesh().boneNames()[1]);
        assertArrayEquals(mesh.positions(), loaded.mesh().positions(), 1e-6f);
        assertArrayEquals(mesh.weights(), loaded.mesh().weights(), 1e-6f);

        // The single-clip API keeps working and returns the first clip.
        VatFile.Content first = VatFile.load(file);
        assertEquals("walk", first.animation().name());
        assertEquals(2, first.mesh().boneNames().length);
    }

    @Test
    void version1FilesStillLoadAsASingleClip(@TempDir File dir) {
        VatMeshData mesh = mesh();
        VatAnimationData animation = clip("idle", 2);

        FileHandle file = new FileHandle(new File(dir, "legacy.vat"));
        VatFile.write(file, mesh, animation);

        VatFile.FileContent loaded = VatFile.loadAll(file);
        assertEquals(1, loaded.animations().size());
        assertEquals("idle", loaded.animations().get(0).name());
        assertEquals(2, loaded.animations().get(0).frameCount());
    }
}
