package com.jb.rts.mesh.blend;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Matrix4;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatFile;
import com.jb.rts.mesh.vat.VatFormat;
import com.jb.rts.mesh.vat.VatMeshData;
import com.jb.rts.mesh.vat.VatSkinBaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that the shipped {@code assets/meshes/dino_longneck-export.json} really uses the
 * version-4 skin format and that the core pipeline can turn it into GPU data: structure, weight
 * normalization, bone-index ranges, keyframe times, and the shader maths reproducing every
 * authored frame.
 */
class RealDinoExportV4Test {

    private static final Path EXPORT =
        Path.of("..", "test-assets", "meshes", "dino_longneck-export.json");

    @Test
    void shippedExportUsesVersion4SkinFormat(@TempDir File tempDir) throws Exception {
        assumeTrue(Files.isRegularFile(EXPORT), "asset not available: " + EXPORT.toAbsolutePath());
        String json = Files.readString(EXPORT);
        assertTrue(json.contains("\"version\": 4"), "export must declare version 4");
        assertTrue(json.contains("\"skin\""), "export must contain the skin block");

        MeshExport export = MeshExportLoader.loadFromString(json);
        assertNotNull(export.skin, "MeshExportLoader must parse the skin block");
        assertEquals("dino_longneck", export.name);
        assertEquals(3, export.skin.influences, "VatFormat expects three influences");
        assertEquals(3, VatFormat.INFLUENCES);

        BlendableMesh bind = export.meshes.get(0);
        assertEquals(684, bind.vertexCount(), "228 drawable triangles * 3 corners");
        assertEquals(684, export.skin.vertexCount());

        // ---- influences: in range, normalized, mapped to real shared vertices ----
        assertNotNull(export.skin.sourceVertex);
        int boneCount = export.skin.boneCount();
        assertTrue(boneCount <= 26, "25 bones (+ optional bind-pose row), was " + boneCount);
        assertEquals(181, export.sharedVertexCount);
        for (int v = 0; v < export.skin.vertexCount(); v++) {
            float sum = 0f;
            for (int k = 0; k < VatFormat.INFLUENCES; k++) {
                int id = export.skin.boneIds[v * VatFormat.INFLUENCES + k];
                assertTrue(id >= 0 && id < boneCount, "vertex " + v + " bone id " + id);
                float w = export.skin.weights[v * VatFormat.INFLUENCES + k];
                assertTrue(w >= 0f, "vertex " + v + " negative weight");
                sum += w;
            }
            assertEquals(1f, sum, 1e-4f, "vertex " + v + " weights must sum to 1");
            assertTrue(export.skin.sourceVertex[v] >= 0
                && export.skin.sourceVertex[v] < export.sharedVertexCount);
        }

        // ---- clips: names, frame counts, keyframe times, looping ----
        assertEquals(3, export.skinClips.size(), "walk + run + die");
        MeshSkinClip walk = export.skinClips.get(0);
        MeshSkinClip run = export.skinClips.get(1);
        MeshSkinClip die = export.skinClips.get(2);
        assertEquals("walk", walk.name);
        assertEquals("run", run.name);
        assertEquals("die", die.name);
        assertTrue(walk.looping && run.looping);
        for (MeshSkinClip clip : export.skinClips) {
            float previous = -Float.MAX_VALUE;
            for (float time : clip.frameTimes) {
                assertTrue(time >= previous, clip.name + " keyframe times must not decrease");
                previous = time;
            }
            assertEquals(posesOf(export, clip.name).size(), clip.frameCount,
                clip.name + " frame count");
        }

        // ---- bake and reproduce every authored frame with the shader maths ----
        VatMeshData gpu = VatSkinBaker.meshData(bind, export.skin, export.palette);
        assertEquals(684, gpu.vertexCount());
        double worst = 0d;
        double sum = 0d;
        long samples = 0L;
        for (MeshSkinClip clip : export.skinClips) {
            List<BlendableMesh> poses = posesOf(export, clip.name);
            VatAnimationData animation = VatSkinBaker.animation(clip.name, export.skin, clip);
            assertEquals(boneCount, animation.boneCount());

            for (int frame = 0; frame < clip.frameCount; frame++) {
                float[] offsets = poses.get(frame).positions;
                for (int v = 0; v < gpu.vertexCount(); v++) {
                    float[] skinned = skin(gpu, animation, v, frame);
                    int i3 = v * BlendableMesh.POSITION_FLOATS;
                    double error = Math.hypot(
                        gpu.positions()[i3] + offsets[i3] - skinned[0],
                        gpu.positions()[i3 + 1] + offsets[i3 + 1] - skinned[1]);
                    worst = Math.max(worst, error);
                    sum += error;
                    samples++;
                }
            }
        }
        double mean = sum / samples;
        System.out.printf("shipped v4 export: %d bone rows, %d vertices, max %.4f px, mean %.4f px%n",
            boneCount, gpu.vertexCount(), worst, mean);
        // Frames authored without bones deviate slightly (the mesheditor reports the same values);
        // the mean must stay tiny for a bone-driven clip.
        assertTrue(mean < 2.0, "skin does not reproduce the frames: mean " + mean);
        assertTrue(worst < 100.0, "skin deviates too much: max " + worst);

        // ---- multi-clip container round trip with the real data ----
        FileHandle handle = new FileHandle(new File(tempDir, "dino.vat"));
        List<VatAnimationData> clips = List.of(
            VatSkinBaker.animation("walk", export.skin, walk),
            VatSkinBaker.animation("run", export.skin, run));
        VatFile.writeAll(handle, gpu, clips);
        VatFile.FileContent loaded = VatFile.loadAll(handle);
        assertEquals(2, loaded.animations().size());
        assertEquals("walk", loaded.animations().get(0).name());
        assertEquals(walk.frameCount, loaded.animations().get(0).frameCount());
        assertEquals(684, loaded.mesh().vertexCount());
        assertEquals(boneCount, loaded.mesh().boneNames().length);
    }

    private static List<BlendableMesh> posesOf(MeshExport export, String name) {
        List<BlendableMesh> poses = new ArrayList<>();
        for (BlendableMesh pose : export.meshes) {
            if (name.equals(pose.poseName())) poses.add(pose);
        }
        return poses;
    }

    /** Applies the VAT vertex-shader maths (3 influences, columns 0/1/3) for one vertex. */
    private static float[] skin(VatMeshData mesh, VatAnimationData animation, int vertex,
                                int frame) {
        int i3 = vertex * BlendableMesh.POSITION_FLOATS;
        float px = mesh.positions()[i3];
        float py = mesh.positions()[i3 + 1];
        float x = 0f;
        float y = 0f;
        Matrix4 m = new Matrix4();
        for (int k = 0; k < VatFormat.INFLUENCES; k++) {
            float w = mesh.weights()[vertex * VatFormat.INFLUENCES + k];
            if (w <= 0f) continue;
            int bone = mesh.boneIds()[vertex * VatFormat.INFLUENCES + k];
            animation.boneMatrix(bone, frame, m);
            x += (m.val[0] * px + m.val[4] * py + m.val[12]) * w;
            y += (m.val[1] * px + m.val[5] * py + m.val[13]) * w;
        }
        return new float[]{x, y};
    }
}
