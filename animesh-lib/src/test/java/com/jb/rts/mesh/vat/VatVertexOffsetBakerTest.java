package com.jb.rts.mesh.vat;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Matrix4;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VatVertexOffsetBaker}: hand-animated vertices (the animation panel's vertex
 * tool) must survive the skinning pipeline, purely bone-driven clips must not pay for extra rows,
 * and the real dino export must be reproduced <b>exactly</b> once the baker patched the skin.
 */
class VatVertexOffsetBakerTest {

    private static final int INFLUENCES = VatFormat.INFLUENCES;

    /** Three expanded vertices (one triangle) at {@code (0,0)}, {@code (1,0)}, {@code (0,1)}. */
    private static final float[] BIND = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        0f, 1f, 0f
    };

    private static final short[] INDICES = {0, 1, 2};
    private static final int[] COLOR_INDICES = {0, 0, 0};

    // -----------------------------------------------------------------------
    // Synthetic helpers
    // -----------------------------------------------------------------------

    /** Skin with one bone row; every vertex is fully weighted to that bone. */
    private static MeshSkin singleBoneSkin() {
        int vertexCount = BIND.length / BlendableMesh.POSITION_FLOATS;
        int[] boneIds = new int[vertexCount * INFLUENCES];
        float[] weights = new float[vertexCount * INFLUENCES];
        int[] sourceVertex = new int[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            sourceVertex[v] = v;
            weights[v * INFLUENCES] = 1f;
        }
        return new MeshSkin(new String[]{"root"}, INFLUENCES, boneIds, weights, sourceVertex, -1,
            new float[]{0f}, new float[]{0f}, new float[]{1f}, new float[]{0f});
    }

    /**
     * Clip from per-frame, per-bone 2D affine matrices
     * ({@code [m00, m01, m03, m10, m11, m13]}).
     */
    private static MeshSkinClip clip(String name, float[][][] frames) {
        int frameCount = frames.length;
        int boneCount = frames[0].length;
        int stride = MeshSkinClip.FLOATS_PER_MATRIX;
        float[] matrices = new float[frameCount * boneCount * stride];
        float[] times = new float[frameCount];
        for (int f = 0; f < frameCount; f++) {
            times[f] = f / 30f;
            for (int b = 0; b < boneCount; b++) {
                System.arraycopy(frames[f][b], 0, matrices, (f * boneCount + b) * stride, stride);
            }
        }
        return new MeshSkinClip(name, true, 30f, boneCount, times, matrices);
    }

    private static float[] identity() {
        return new float[]{1f, 0f, 0f, 0f, 1f, 0f};
    }

    private static float[] translation(float dx, float dy) {
        return new float[]{1f, 0f, dx, 0f, 1f, dy};
    }

    /** One pose of the synthetic triangle; {@code offsets} are deltas over {@link #BIND}. */
    private static BlendableMesh pose(String name, int index, float[] offsets) {
        return new BlendableMesh(name + "_" + index, BIND, offsets, COLOR_INDICES, INDICES,
            name, name, index);
    }

    /** Zero offsets for all three vertices. */
    private static float[] noOffsets() {
        return new float[BIND.length];
    }

    /** Applies the VAT shader maths (three influences, columns 0/1/3) for one vertex. */
    private static float[] skin(MeshSkin skin, MeshSkinClip clip, int vertex, int frame) {
        int i3 = vertex * BlendableMesh.POSITION_FLOATS;
        float px = BIND[i3];
        float py = BIND[i3 + 1];
        float x = 0f;
        float y = 0f;
        int stride = MeshSkinClip.FLOATS_PER_MATRIX;
        for (int k = 0; k < skin.influences; k++) {
            float w = skin.weights[vertex * skin.influences + k];
            if (w <= 0f) continue;
            int o = (frame * clip.boneCount + skin.boneIds[vertex * skin.influences + k]) * stride;
            x += (clip.matrices[o] * px + clip.matrices[o + 1] * py + clip.matrices[o + 2]) * w;
            y += (clip.matrices[o + 3] * px + clip.matrices[o + 4] * py + clip.matrices[o + 5]) * w;
        }
        return new float[]{x, y};
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void purelyBoneDrivenClipNeedsNoExtraRows() {
        MeshSkin skin = singleBoneSkin();
        MeshSkinClip clip = clip("walk", new float[][][]{
            {identity()},
            {translation(2f, 0f)}
        });
        // The frame offsets are exactly what the bone matrix produces.
        float[] frame1 = {
            2f, 0f, 0f,
            2f, 0f, 0f,
            2f, 0f, 0f
        };
        List<BlendableMesh> frames = List.of(pose("walk", 0, noOffsets()),
            pose("walk", 1, frame1));

        assertNull(VatVertexOffsetBaker.bake(bindPose(), skin, clip, frames),
            "a pure bone animation must not add matrix rows");
    }

    @Test
    void handAnimatedVertexGetsItsOwnRowAndIsReproducedExactly() {
        MeshSkin skin = singleBoneSkin();
        MeshSkinClip clip = clip("walk", new float[][][]{
            {identity()},
            {translation(2f, 0f)}
        });
        // Vertex 2 was dragged by hand: the bone moves it to (2,1) but the export says (2,4).
        float[] frame1 = {
            2f, 0f, 0f,
            2f, 0f, 0f,
            2f, 3f, 0f
        };
        List<BlendableMesh> frames = List.of(pose("walk", 0, noOffsets()),
            pose("walk", 1, frame1));
        BlendableMesh bind = bindPose();

        // Before: the bone-only skin leaves the hand-edited vertex behind.
        float[] before = skin(skin, clip, 2, 1);
        assertEquals(2f, before[0], 1e-5f);
        assertEquals(1f, before[1], 1e-5f, "the hand edit is not part of the bone matrix");

        VatVertexOffsetBaker.Result patched = VatVertexOffsetBaker.bake(bind, skin, clip, frames);
        assertNotNull(patched, "a hand-animated vertex must be baked into its own row");
        assertEquals(1, patched.rowCount());
        assertEquals(3f, patched.maxResidual(), 1e-5f, "distance the bones alone missed");
        assertEquals(skin.boneCount() + 1, patched.skin().boneCount());
        assertEquals("root", patched.skin().boneNames[0]);
        assertEquals("<vertex:2>", patched.skin().boneNames[1]);
        assertEquals(1f, patched.skin().weights[2 * INFLUENCES], 1e-6f,
            "the hand-animated vertex is fully bound to its own row");
        assertEquals(0f, patched.skin().weights[2 * INFLUENCES + 1], 1e-6f);
        assertEquals(0, patched.skin().boneIds[0], "untouched vertices keep their bone");
        assertEquals(2, patched.clip().frameCount);

        // After: every authored frame is reproduced exactly.
        for (int frame = 0; frame < frames.size(); frame++) {
            float[] offsets = frames.get(frame).positions;
            for (int v = 0; v < 3; v++) {
                int i3 = v * BlendableMesh.POSITION_FLOATS;
                float[] skinned = skin(patched.skin(), patched.clip(), v, frame);
                assertEquals(BIND[i3] + offsets[i3], skinned[0], 1e-4f,
                    "frame " + frame + " vertex " + v + " x");
                assertEquals(BIND[i3 + 1] + offsets[i3 + 1], skinned[1], 1e-4f,
                    "frame " + frame + " vertex " + v + " y");
            }
        }
    }

    @Test
    void numericNoiseStaysBelowTheTolerance() {
        MeshSkin skin = singleBoneSkin();
        MeshSkinClip clip = clip("walk", new float[][][]{
            {identity()},
            {translation(2f, 0f)}
        });
        float[] frame1 = {
            2f, 0f, 0f,
            2f, 0.0004f, 0f,
            2f, 0f, 0f
        };
        List<BlendableMesh> frames = List.of(pose("walk", 0, noOffsets()),
            pose("walk", 1, frame1));
        BlendableMesh bind = bindPose();

        assertNull(VatVertexOffsetBaker.bake(bind, skin, clip, frames),
            "sub-epsilon deviations must not create rows");
        assertNotNull(VatVertexOffsetBaker.bake(bind, skin, clip, frames, 1e-5f),
            "a lower tolerance must pick the deviation up");
    }

    @Test
    void rejectsClipsThatDoNotMatchTheFrameCount() {
        MeshSkin skin = singleBoneSkin();
        MeshSkinClip clip = clip("walk", new float[][][]{
            {identity()},
            {translation(2f, 0f)}
        });
        List<BlendableMesh> frames = List.of(pose("walk", 0, noOffsets()));

        assertNull(VatVertexOffsetBaker.bake(bindPose(), skin, clip, frames),
            "merged keyframe times cannot be aligned, so the baker must bail out");
    }

    /** Bind pose built from the same triangle as {@link #BIND}. */
    private static BlendableMesh bindPose() {
        return new BlendableMesh("walk_rest_0", BIND, noOffsets(), COLOR_INDICES, INDICES,
            "walk", "rest", 0);
    }

    // -----------------------------------------------------------------------
    // Real export
    // -----------------------------------------------------------------------

    /**
     * End-to-end check with the export {@code MeshPlayer} plays: after
     * {@link VatVertexOffsetBaker} patched the skin, the shader maths (via {@link VatMeshData} and
     * {@link VatAnimationData}) must reproduce every authored vertex position of every frame —
     * including the vertices the user dragged by hand.
     */
    @Test
    void realDinoExportReproducesHandAnimatedVertices() throws Exception {
        File file = Paths.get("..", "test-assets", "meshes", "dino_longneck-export.json").toFile();
        Assumptions.assumeTrue(file.isFile(), "dino_longneck-export.json not found; skipping");

        MeshExport export = MeshExportLoader.load(new FileHandle(file));
        Assumptions.assumeTrue(export.skin != null, "export has no skin; skipping");
        BlendableMesh bind = export.meshes.get(0);

        double worstBefore = 0d;
        double worstAfter = 0d;
        int patchedClips = 0;
        int rows = 0;
        for (MeshSkinClip clip : export.skinClips) {
            List<BlendableMesh> frames = posesOf(export, clip.name);
            Assumptions.assumeTrue(frames.size() == clip.frameCount,
                "clip '" + clip.name + "' has " + clip.frameCount + " frames but "
                    + frames.size() + " poses; skipping");

            VatVertexOffsetBaker.Result patched =
                VatVertexOffsetBaker.bake(bind, export.skin, clip, frames);
            if (patched != null) {
                patchedClips++;
                rows += patched.rowCount();
                assertEquals(export.skin.boneCount() + patched.rowCount(),
                    patched.skin().boneCount());
            }
            MeshSkin skin = patched != null ? patched.skin() : export.skin;
            MeshSkinClip usedClip = patched != null ? patched.clip() : clip;

            VatMeshData gpuBefore = VatSkinBaker.meshData(bind, export.skin, export.palette);
            VatAnimationData beforeAnim = VatSkinBaker.animation(clip.name, export.skin, clip);
            VatMeshData gpu = patched != null
                ? VatSkinBaker.meshData(bind, skin, export.palette) : gpuBefore;
            VatAnimationData animation = patched != null
                ? VatSkinBaker.animation(clip.name, skin, usedClip) : beforeAnim;
            assertTrue(animation.textureWidth() <= 16384 && animation.textureHeight() <= 16384,
                clip.name + " matrix texture " + animation.textureWidth() + "x"
                    + animation.textureHeight() + " exceeds a common GL_MAX_TEXTURE_SIZE");

            for (int frame = 0; frame < clip.frameCount; frame++) {
                float[] offsets = frames.get(frame).positions;
                for (int v = 0; v < gpu.vertexCount(); v++) {
                    int i3 = v * BlendableMesh.POSITION_FLOATS;
                    float targetX = gpu.positions()[i3] + offsets[i3];
                    float targetY = gpu.positions()[i3 + 1] + offsets[i3 + 1];
                    float[] after = skinVertex(gpu, animation, v, frame);
                    float[] before = skinVertex(gpuBefore, beforeAnim, v, frame);
                    worstAfter = Math.max(worstAfter,
                        Math.hypot(targetX - after[0], targetY - after[1]));
                    worstBefore = Math.max(worstBefore,
                        Math.hypot(targetX - before[0], targetY - before[1]));
                }
            }
        }

        System.out.printf("hand-animated vertices: %d clip(s), %d row(s), "
                + "worst error %.4f -> %.6f%n",
            patchedClips, rows, worstBefore, worstAfter);
        assertTrue(patchedClips > 0, "the shipped export contains hand-animated vertices");
        assertTrue(worstBefore > 0.05, "the bone-only skin must have missed them, was "
            + worstBefore);
        assertTrue(worstAfter < VatVertexOffsetBaker.DEFAULT_EPSILON,
            "patched skin must reproduce every frame within the tolerance, was " + worstAfter);
    }

    /**
     * The locomotion pair the player blends: after {@link VatVertexOffsetBaker#bakeAll} every dino
     * clip uses one shared row table, so {@code walk} and {@code run} can be mixed by
     * {@link VatAnimationBlender} — baking each clip on its own used to give them 120 and 111
     * rows, which made blending impossible.
     */
    @Test
    void realDinoWalkAndRunShareOneSkinAndCanBlend() throws Exception {
        File file = Paths.get("..", "test-assets", "meshes", "dino_longneck-export.json").toFile();
        Assumptions.assumeTrue(file.isFile(), "dino_longneck-export.json not found; skipping");

        MeshExport export = MeshExportLoader.load(new FileHandle(file));
        Assumptions.assumeTrue(export.skin != null, "export has no skin; skipping");

        List<MeshSkinClip> clips = new ArrayList<>();
        List<List<BlendableMesh>> framesPerClip = new ArrayList<>();
        for (MeshSkinClip clip : export.skinClips) {
            List<BlendableMesh> frames = posesOf(export, clip.name);
            Assumptions.assumeTrue(frames.size() == clip.frameCount,
                "clip '" + clip.name + "' has " + clip.frameCount + " frames but "
                    + frames.size() + " poses; skipping");
            clips.add(clip);
            framesPerClip.add(frames);
        }
        Assumptions.assumeTrue(clips.size() >= 2, "export needs at least two clips; skipping");

        List<VatVertexOffsetBaker.Result> all = VatVertexOffsetBaker.bakeAll(
            export.meshes.get(0), export.skin, clips, framesPerClip,
            VatVertexOffsetBaker.DEFAULT_EPSILON);

        MeshSkin shared = all.get(0).skin();
        assertSame(shared, all.get(all.size() - 1).skin(), "one skin object for every clip");
        for (VatVertexOffsetBaker.Result result : all) {
            assertEquals(shared.boneCount(), result.clip().boneCount,
                "every clip must use the shared row table");
        }
        assertTrue(shared.boneCount() > export.skin.boneCount(),
            "the union of hand-animated vertices adds rows");

        int walk = indexOf(clips, "walk");
        int run = indexOf(clips, "run");
        Assumptions.assumeTrue(walk >= 0 && run >= 0, "export has no walk/run pair; skipping");

        VatAnimationData walkAnim = VatSkinBaker.animation("walk", shared, all.get(walk).clip(),
            framesPerClip.get(walk), export.palette);
        VatAnimationData runAnim = VatSkinBaker.animation("run", shared, all.get(run).clip(),
            framesPerClip.get(run), export.palette);

        VatAnimationData blended = VatAnimationBlender.blend(walkAnim, runAnim, 0.5f);

        assertEquals(walkAnim.boneCount(), blended.boneCount());
        assertEquals(Math.max(walkAnim.frameCount(), runAnim.frameCount()), blended.frameCount());
    }

    private static int indexOf(List<MeshSkinClip> clips, String name) {
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    private static List<BlendableMesh> posesOf(MeshExport export, String name) {
        List<BlendableMesh> poses = new ArrayList<>();
        for (BlendableMesh pose : export.meshes) {
            if (name.equals(pose.poseName())) poses.add(pose);
        }
        return poses;
    }

    /** Applies the VAT vertex-shader maths (3 influences, columns 0/1/3) for one vertex. */
    private static float[] skinVertex(VatMeshData mesh, VatAnimationData animation, int vertex,
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

    @Test
    void bakeAll_givesEveryClipTheSameRowCountSoTheyCanBlend() {
        MeshSkin skin = singleBoneSkin();
        BlendableMesh bind = pose("rest", 0, noOffsets());

        // Clip A moves vertex 0, clip B moves vertex 1 — on their own each needs one extra row.
        float[] offsetsA = noOffsets();
        offsetsA[0] = 0.5f;
        float[] offsetsB = noOffsets();
        offsetsB[BlendableMesh.POSITION_FLOATS + 1] = 0.5f;
        List<BlendableMesh> framesA = List.of(pose("a", 0, offsetsA));
        List<BlendableMesh> framesB = List.of(pose("b", 0, offsetsB));
        MeshSkinClip clipA = clip("a", new float[][][]{{identity()}});
        MeshSkinClip clipB = clip("b", new float[][][]{{identity()}});

        assertEquals(2, VatVertexOffsetBaker.bake(bind, skin, clipA, framesA).skin().boneCount(),
            "clip A on its own: root + vertex 0");
        assertEquals(2, VatVertexOffsetBaker.bake(bind, skin, clipB, framesB).skin().boneCount(),
            "clip B on its own: root + vertex 1");

        List<VatVertexOffsetBaker.Result> all = VatVertexOffsetBaker.bakeAll(bind, skin,
            List.of(clipA, clipB), List.of(framesA, framesB),
            VatVertexOffsetBaker.DEFAULT_EPSILON);

        assertEquals(2, all.size());
        assertSame(all.get(0).skin(), all.get(1).skin(), "one shared skin for every clip");
        assertEquals(3, all.get(0).skin().boneCount(), "root + the union of both animated vertices");
        assertEquals(all.get(0).clip().boneCount, all.get(1).clip().boneCount,
            "identical row counts are what makes the clips blendable");

        float[] a = skin(all.get(0).skin(), all.get(0).clip(), 0, 0);
        assertEquals(BIND[0] + 0.5f, a[0], 1e-4f, "clip A still reproduces its hand edit");
        float[] b = skin(all.get(1).skin(), all.get(1).clip(), 1, 0);
        assertEquals(BIND[BlendableMesh.POSITION_FLOATS + 1] + 0.5f, b[1], 1e-4f,
            "clip B still reproduces its own hand edit");
    }
}
