package com.jb.rts.mesh.vat;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Matrix4;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VatPoseBaker}: the baked bone matrices must reproduce every exported pose with
 * the exact maths of the VAT vertex shader, a fractional frame index must interpolate like the CPU
 * blend renderer, and the static vertex stream must carry the palette colours.
 */
class VatPoseBakerTest {

    private static final float EPS = 1e-5f;

    /** Six expanded vertices (two triangles) forming a 2x1 bind-pose strip. */
    private static final float[] BIND = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        0f, 1f, 0f,
        1f, 1f, 0f,
        2f, 0f, 0f,
        2f, 1f, 0f
    };

    private static final short[] INDICES = {0, 1, 2, 3, 4, 5};
    private static final int[] COLOR_INDICES = {0, 1, 2, 0, 1, 2};

    private static ColorPalette palette() {
        ColorPalette palette = new ColorPalette();
        palette.addColor("red", 1f, 0f, 0f, 1f);
        palette.addColor("green", 0f, 1f, 0f, 0.5f);
        palette.addColor("blue", 0f, 0f, 1f, 1f);
        return palette;
    }

    private static BlendableMesh pose(String name, float[] offsets) {
        return new BlendableMesh(name, BIND, offsets, COLOR_INDICES, INDICES,
            "demo", "walk", 0);
    }

    /** Offsets that move every vertex by {@code (dx, dy, dz)}. */
    private static float[] shifted(float dx, float dy, float dz) {
        float[] offsets = new float[BIND.length];
        for (int i = 0; i < offsets.length; i += BlendableMesh.POSITION_FLOATS) {
            offsets[i] = dx;
            offsets[i + 1] = dy;
            offsets[i + 2] = dz;
        }
        return offsets;
    }

    private static VatPoseBaker.Result bakeTwoFrameWalk() {
        return VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", new float[BIND.length]),
                    pose("demo_walk_1", shifted(0.5f, -1f, 0.25f))),
            palette(), 12f);
    }

    /** Applies one baked matrix exactly like the VAT shader: columns 0, 1 and 3. */
    private static float[] skin(VatAnimationData animation, int bone, int frame, float x, float y) {
        Matrix4 m = animation.boneMatrix(bone, frame, new Matrix4());
        return new float[]{
            m.val[0] * x + m.val[4] * y + m.val[12],
            m.val[1] * x + m.val[5] * y + m.val[13]
        };
    }

    @Test
    void bakesStaticMeshBoneTableAndPaletteColours() {
        VatPoseBaker.Result result = bakeTwoFrameWalk();
        VatMeshData mesh = result.mesh();

        assertEquals(6, mesh.vertexCount());
        assertEquals(2, mesh.triangleCount());
        assertEquals(6, mesh.boneNames().length, "one translation bone per expanded vertex");
        assertArrayEquals(BIND, mesh.positions(), EPS, "bind pose = the export's default pose");

        float[] colors = mesh.colors();
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, new float[]{
            colors[0], colors[1], colors[2], colors[3]}, EPS, "colour index 0 = red");
        assertArrayEquals(new float[]{0f, 1f, 0f, 0.5f}, new float[]{
            colors[4], colors[5], colors[6], colors[7]}, EPS,
            "colour index 1 = half-transparent green");
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, new float[]{
            colors[8], colors[9], colors[10], colors[11]}, EPS, "colour index 2 = blue");

        for (int v = 0; v < mesh.vertexCount(); v++) {
            for (int k = 0; k < VatFormat.INFLUENCES; k++) {
                assertEquals(v, mesh.boneIds()[v * VatFormat.INFLUENCES + k],
                    "every influence of vertex " + v + " must address its own bone row");
            }
            assertEquals(1f, mesh.weights()[v * VatFormat.INFLUENCES], EPS);
            assertEquals(0f, mesh.weights()[v * VatFormat.INFLUENCES + 1], EPS);
            assertEquals(0f, mesh.weights()[v * VatFormat.INFLUENCES + 2], EPS);
        }

        VatAnimationData animation = result.animation();
        assertEquals("demo_walk", animation.name());
        assertEquals(6, animation.boneCount());
        assertEquals(2, animation.frameCount());
        assertEquals(12f, animation.fps(), EPS);
        assertFalse(animation.looping(), "frames must not wrap last -> first");
    }

    @Test
    void shaderMathReproducesEveryExportedFrame() {
        VatPoseBaker.Result result = bakeTwoFrameWalk();
        float[][] frames = {new float[BIND.length], shifted(0.5f, -1f, 0.25f)};

        for (int f = 0; f < frames.length; f++) {
            for (int v = 0; v < BIND.length / BlendableMesh.POSITION_FLOATS; v++) {
                int i3 = v * BlendableMesh.POSITION_FLOATS;
                float[] skinned = skin(result.animation(), v, f, BIND[i3], BIND[i3 + 1]);
                assertEquals(BIND[i3] + frames[f][i3], skinned[0], EPS,
                    "frame " + f + " vertex " + v + " x");
                assertEquals(BIND[i3 + 1] + frames[f][i3 + 1], skinned[1], EPS,
                    "frame " + f + " vertex " + v + " y");
            }
        }

        // The z offset is part of the 4x4 matrix (column 3, element 14) even though the 2D shader
        // passes the bind-pose z through unchanged.
        int offset = VatFormat.matrixFloatOffset(3, 1, 2);
        assertEquals(0.25f, result.animation().boneMatrices()[offset + 14], EPS);
    }

    @Test
    void fractionalFrameMatchesCpuPoseBlend() {
        VatPoseBaker.Result result = bakeTwoFrameWalk();
        float t = 0.25f;
        float[] frame1 = shifted(0.5f, -1f, 0.25f);

        for (int v = 0; v < BIND.length / BlendableMesh.POSITION_FLOATS; v++) {
            int i3 = v * BlendableMesh.POSITION_FLOATS;
            // Shader: mix(matrix(f0), matrix(f1), t) -> translation = mix(offset0, offset1, t).
            Matrix4 m0 = result.animation().boneMatrix(v, 0, new Matrix4());
            Matrix4 m1 = result.animation().boneMatrix(v, 1, new Matrix4());
            float c3x = m0.val[12] + (m1.val[12] - m0.val[12]) * t;
            float c3y = m0.val[13] + (m1.val[13] - m0.val[13]) * t;
            float skinnedX = m0.val[0] * BIND[i3] + m0.val[4] * BIND[i3 + 1] + c3x;
            float skinnedY = m0.val[1] * BIND[i3] + m0.val[5] * BIND[i3 + 1] + c3y;

            assertEquals(BIND[i3] + frame1[i3] * t, skinnedX, EPS,
                "vertex " + v + " x at frame 0.25");
            assertEquals(BIND[i3 + 1] + frame1[i3 + 1] * t, skinnedY, EPS,
                "vertex " + v + " y at frame 0.25");
        }
    }

    /** Texture coordinates travel through the pose fallback into the static mesh data. */
    @Test
    void carriesPerVertexUvsIntoTheStaticMeshData() {
        float[] uvs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.25f, 0.75f, 0.25f};
        VatPoseBaker.Result result = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", new float[BIND.length])), palette(), 12f,
            null, null, uvs);

        assertTrue(result.mesh().hasUvs(), "UVs must reach the mesh data");
        assertArrayEquals(uvs, result.mesh().uvs(), EPS);
    }

    /** A partially filled UV table would sample wrong texels — it must be rejected, not guessed. */
    @Test
    void rejectsIncompleteUvTables() {
        assertThrows(IllegalArgumentException.class,
            () -> VatPoseBaker.bake("demo_walk",
                List.of(pose("demo_walk_0", new float[BIND.length])), palette(), 12f,
                null, null, new float[]{0f, 0f}));
    }

    @Test
    void nullPaletteFallsBackToWhite() {
        VatPoseBaker.Result result = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", new float[BIND.length])), null);
        float[] colors = result.mesh().colors();
        for (int v = 0; v < result.mesh().vertexCount(); v++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(1f, colors[v * 4 + c], EPS, "vertex " + v + " channel " + c);
            }
        }
    }

    @Test
    void rejectsEmptyOrTopologicallyDifferentPoses() {
        assertThrows(IllegalArgumentException.class,
            () -> VatPoseBaker.bake("empty", List.<BlendableMesh>of(), palette()));

        BlendableMesh bind = pose("demo_walk_0", new float[BIND.length]);
        BlendableMesh other = new BlendableMesh("demo_walk_1",
            new float[]{0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f},
            new float[9], new int[]{0, 0, 0}, new short[]{0, 1, 2}, "demo", "walk", 1);
        assertThrows(IllegalArgumentException.class,
            () -> VatPoseBaker.bake("demo_walk", List.of(bind, other), palette()));
    }

    /**
     * End-to-end check with the real export {@code MeshPlayer} plays: every baked frame must
     * reconstruct the authored vertex positions with the shader's maths and the matrix texture
     * must fit into {@code GL_MAX_TEXTURE_SIZE}.
     */
    @Test
    void realDinoExportBakesOneAnimationExactly() throws Exception {
        File file = Paths.get("..", "test-assets", "meshes", "dino_longneck-export.json").toFile();
        Assumptions.assumeTrue(file.isFile(), "dino_longneck-export.json not found; skipping");

        MeshExport export = MeshExportLoader.load(new FileHandle(file));
        String animationName = null;
        List<BlendableMesh> frames = new ArrayList<>();
        for (BlendableMesh m : export.meshes) {
            if ("rest".equals(m.poseName())) continue;
            if (animationName == null) animationName = m.poseName();
            if (animationName.equals(m.poseName())) frames.add(m);
        }
        Assumptions.assumeFalse(frames.isEmpty(), "export has no animation; skipping");

        VatPoseBaker.Result result = VatPoseBaker.bake(animationName, frames, export.palette);
        VatAnimationData animation = result.animation();
        VatMeshData mesh = result.mesh();

        assertEquals(frames.size(), animation.frameCount());
        assertEquals(mesh.vertexCount(), animation.boneCount());
        assertTrue(animation.textureWidth() <= 16384 && animation.textureHeight() <= 16384,
            "matrix texture " + animation.textureWidth() + "x" + animation.textureHeight()
                + " exceeds a common GL_MAX_TEXTURE_SIZE");

        for (int frame = 0; frame < frames.size(); frame++) {
            float[] offsets = frames.get(frame).positions;
            for (int v = 0; v < mesh.vertexCount(); v++) {
                int i3 = v * BlendableMesh.POSITION_FLOATS;
                float[] skinned = skin(animation, v, frame,
                    mesh.positions()[i3], mesh.positions()[i3 + 1]);
                assertEquals(mesh.positions()[i3] + offsets[i3], skinned[0], 1e-3f,
                    "frame " + frame + " vertex " + v + " x");
                assertEquals(mesh.positions()[i3 + 1] + offsets[i3 + 1], skinned[1], 1e-3f,
                    "frame " + frame + " vertex " + v + " y");
            }
        }
    }
}
