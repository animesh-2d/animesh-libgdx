package com.jb.rts.mesh.vat;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;
import com.badlogic.gdx.files.FileHandle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the <b>per-frame colour strip</b> that restores colour blending on the GPU: an export
 * that recolours triangle corners per animation frame must end up in
 * {@link VatAnimationData#frameColors()} — not silently degrade to the bind-pose colours.
 */
class VatFrameColorTest {

    private static final int FLOATS_PER_MATRIX = MeshSkinClip.FLOATS_PER_MATRIX;

    /** Six expanded vertices (two triangles), one colour index per vertex. */
    private static final float[] BIND = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        0f, 1f, 0f,
        1f, 1f, 0f,
        2f, 0f, 0f,
        2f, 1f, 0f
    };

    private static final short[] INDICES = {0, 1, 2, 3, 4, 5};

    /** First triangle red, second triangle green. */
    private static final int[] COLOR_INDICES = {0, 0, 0, 1, 1, 1};

    private static ColorPalette palette() {
        ColorPalette palette = new ColorPalette();
        palette.addColor("red", 1f, 0f, 0f, 1f);
        palette.addColor("green", 0f, 1f, 0f, 0.5f);
        palette.addColor("blue", 0f, 0f, 1f, 1f);          // 255 blue
        palette.addColor("half", 0.5f, 0.25f, 0f, 0.25f);  // rounding check
        return palette;
    }

    private static BlendableMesh pose(String name, int[] colorIndices) {
        return new BlendableMesh(name, BIND, new float[BIND.length], colorIndices, INDICES,
            "demo", "walk", 0);
    }

    /** Decodes a packed texel ({@code 0xAABBGGRR}) into {@code {r, g, b, a}} bytes. */
    private static int[] decode(int packed) {
        return new int[]{packed & 0xFF, (packed >> 8) & 0xFF, (packed >> 16) & 0xFF,
            (packed >> 24) & 0xFF};
    }

    private static int[] expectedRgba(ColorPalette palette, int index) {
        return new int[]{
            Math.round(palette.red(index) * 255f),
            Math.round(palette.green(index) * 255f),
            Math.round(palette.blue(index) * 255f),
            Math.round(palette.alpha(index) * 255f)
        };
    }

    /** Packed texel of the colour strip at {@code (frame, vertex)}. */
    private static int texel(VatAnimationData animation, int frame, int vertex) {
        return animation.frameColors()[VatFormat.frameColorTexel(
            animation.colorVertexCount(), frame, vertex)];
    }

    /** Expected per-channel blend of two RGBA byte arrays. */
    private static int[] mix(int[] from, int[] to, float t) {
        int[] out = new int[4];
        for (int c = 0; c < 4; c++) {
            out[c] = Math.round(from[c] + (to[c] - from[c]) * t);
        }
        return out;
    }

    @Test
    void poseBakerKeepsPerFrameColourOverrides() {
        // Two frames, immediate change: nothing to ramp, so the strip must hold the exact
        // palette colours of each frame.
        VatPoseBaker.Result baked = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", COLOR_INDICES),
                    pose("demo_walk_1", new int[]{3, 3, 3, 2, 2, 2})),
            palette(), 30f);

        VatAnimationData animation = baked.animation();
        assertTrue(animation.hasFrameColors(), "per-frame colours must survive the bake");
        assertEquals(6, animation.colorVertexCount());
        assertEquals(6 * 2, animation.frameColors().length);

        ColorPalette palette = palette();
        int[][] frames = {
            {0, 0, 0, 1, 1, 1},
            {3, 3, 3, 2, 2, 2}
        };
        for (int frame = 0; frame < frames.length; frame++) {
            for (int v = 0; v < 6; v++) {
                int texel = VatFormat.frameColorTexel(6, frame, v);
                assertArrayEquals(expectedRgba(palette, frames[frame][v]),
                    decode(animation.frameColors()[texel]),
                    "frame " + frame + " vertex " + v);
            }
        }

        // The strip must fit into a normal GL_MAX_TEXTURE_SIZE on desktop and mobile.
        assertEquals(6, animation.colorTextureWidth());
        assertEquals(2, animation.colorTextureHeight());
        assertTrue(animation.colorTextureWidth() <= 4096);
        assertTrue(animation.colorTextureHeight() <= 4096);
    }

    /**
     * The exporter subdivides a keyframe interval into many sampled frames and gives every
     * intermediate frame the <em>previous</em> keyframe's colours. A colour change therefore sits
     * on a single frame boundary and would snap. The bake must spread it back over the frames that
     * preceded it, i.e. {@code mix(from, to, k / runLength)}.
     */
    @Test
    void colourChangeIsRampedAcrossThePrecedingFrames() {
        // 0..3 red (one subdivided keyframe interval), 4..5 blue (the next one).
        List<BlendableMesh> poses = List.of(
            pose("demo_walk_0", new int[]{0, 0, 0, 0, 0, 0}),
            pose("demo_walk_1", new int[]{0, 0, 0, 0, 0, 0}),
            pose("demo_walk_2", new int[]{0, 0, 0, 0, 0, 0}),
            pose("demo_walk_3", new int[]{0, 0, 0, 0, 0, 0}),
            pose("demo_walk_4", new int[]{2, 2, 2, 2, 2, 2}),
            pose("demo_walk_5", new int[]{2, 2, 2, 2, 2, 2}));

        VatAnimationData animation = VatPoseBaker.bake("demo_walk", poses,
            palette(), 30f).animation();

        ColorPalette palette = palette();
        int[] red = expectedRgba(palette, 0);      // 255,0,0,255
        int[] blue = expectedRgba(palette, 2);     // 0,0,255,255
        for (int v = 0; v < 6; v++) {
            assertArrayEquals(red, decode(texel(animation, 0, v)),
                "frame 0 keeps the authored colour, vertex " + v);
            assertArrayEquals(mix(red, blue, 0.25f), decode(texel(animation, 1, v)),
                "frame 1 is a quarter of the way, vertex " + v);
            assertArrayEquals(mix(red, blue, 0.5f), decode(texel(animation, 2, v)),
                "frame 2 is halfway, vertex " + v);
            assertArrayEquals(mix(red, blue, 0.75f), decode(texel(animation, 3, v)),
                "frame 3 is three quarters, vertex " + v);
            assertArrayEquals(blue, decode(texel(animation, 4, v)),
                "frame 4 keeps the authored colour, vertex " + v);
            assertArrayEquals(blue, decode(texel(animation, 5, v)),
                "frame 5 stays at the authored colour, vertex " + v);
        }
    }

    /** Sparse exports (one frame per keyframe) already blend in the shader — no ramp needed. */
    @Test
    void singleFrameRunsAreNotRamped() {
        // Three authored keyframes: red, blue, red — exactly what a non-subdivided export holds.
        VatAnimationData animation = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", new int[]{0, 0, 0, 0, 0, 0}),
                    pose("demo_walk_1", new int[]{2, 2, 2, 2, 2, 2}),
                    pose("demo_walk_2", new int[]{0, 0, 0, 0, 0, 0})),
            palette(), 30f).animation();

        ColorPalette palette = palette();
        int[][] expected = {expectedRgba(palette, 0), expectedRgba(palette, 2),
            expectedRgba(palette, 0)};
        for (int frame = 0; frame < expected.length; frame++) {
            for (int v = 0; v < 6; v++) {
                assertArrayEquals(expected[frame], decode(texel(animation, frame, v)),
                    "frame " + frame + " vertex " + v + " must stay authored");
            }
        }
    }

    @Test
    void identicalFrameColoursNeedNoStrip() {
        VatPoseBaker.Result baked = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", COLOR_INDICES),
                    pose("demo_walk_1", COLOR_INDICES.clone())),
            palette(), 30f);

        assertFalse(baked.animation().hasFrameColors(),
            "frames that match the bind pose must not allocate a colour texture");
        assertNull(baked.animation().frameColors());
        assertEquals(0, baked.animation().colorVertexCount());
    }

    @Test
    void singleFrameAnimationHasNoStrip() {
        VatPoseBaker.Result baked = VatPoseBaker.bake("demo_walk",
            List.of(pose("demo_walk_0", COLOR_INDICES)), palette(), 30f);
        assertFalse(baked.animation().hasFrameColors());
    }

    @Test
    void missingPaletteEntriesBecomeWhite() {
        int[] strip = VatPalette.resolveFrames(
            List.of(pose("demo_walk_0", COLOR_INDICES),
                    pose("demo_walk_1", new int[]{9, 9, 9, 9, 9, 9})),
            null);
        assertNotNull(strip);
        for (int i = 0; i < strip.length; i++) {
            assertArrayEquals(new int[]{255, 255, 255, 255}, decode(strip[i]),
                "texel " + i + " must fall back to white");
        }
    }

    @Test
    void stripRejectsFramesWithDifferentVertexCounts() {
        BlendableMesh small = new BlendableMesh("demo_walk_1",
            new float[]{0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f}, new float[9],
            new int[]{0, 0, 0}, new short[]{0, 1, 2}, "demo", "walk", 1);
        assertThrows(IllegalArgumentException.class,
            () -> VatPalette.resolveFrames(List.of(pose("demo_walk_0", COLOR_INDICES), small),
                palette()));
    }

    /**
     * End-to-end guard on the real test export: its {@code ColorChange} animation has three
     * authored keyframes (default → recoloured → default) that the exporter sampled into 34
     * frames. The strip must fade across the whole first/second keyframe interval instead of
     * snapping on the single frame where the colour index changes.
     */
    @Test
    void realExportFadesAColourChangeAcrossTheKeyframeInterval() throws Exception {
        File file = Paths.get("..", "test-assets", "meshes", "test animation-export.json").toFile();
        Assumptions.assumeTrue(file.isFile(), "test animation-export.json not found; skipping");

        MeshExport export = MeshExportLoader.load(new FileHandle(file));
        List<BlendableMesh> frames = new ArrayList<>();
        for (BlendableMesh m : export.meshes) {
            if ("ColorChange".equals(m.poseName())) frames.add(m);
        }
        Assumptions.assumeTrue(frames.size() > 3, "export is not subdivided; skipping");

        VatAnimationData animation =
            VatPoseBaker.bake("ColorChange", frames, export.palette).animation();
        assertTrue(animation.hasFrameColors());

        // Vertex 0 starts at the default colour and is recoloured at the authored keyframe that
        // the exporter sampled as frame 10. A snap would show exactly two distinct colours here;
        // the ramp produces the intermediate shades of a cross-fade.
        java.util.Set<Integer> distinct = new java.util.LinkedHashSet<>();
        for (int f = 0; f < Math.min(20, animation.frameCount()); f++) {
            distinct.add(texel(animation, f, 0));
        }
        assertTrue(distinct.size() > 2,
            "the colour change must be spread over several frames, found " + distinct.size()
                + " distinct colours");

        // The recoloured keyframe is reached exactly at the sampled frame of the authored keyframe
        // (10); the frame before it is still part of the fade, and the third authored keyframe
        // brings the default colour back.
        assertNotEquals(texel(animation, 0, 0), texel(animation, 10, 0),
            "frame 10 must hold the recoloured keyframe");
        assertNotEquals(texel(animation, 9, 0), texel(animation, 10, 0),
            "frame 9 must still be an intermediate colour of the fade");
        assertEquals(texel(animation, 0, 0),
            texel(animation, animation.frameCount() - 1, 0),
            "the last authored keyframe restores the default colour");
    }

    /** One bone, every vertex fully bound to it — enough to drive the skinning path. */
    private static MeshSkin singleBoneSkin() {
        int influences = VatFormat.INFLUENCES;
        int vertexCount = BIND.length / BlendableMesh.POSITION_FLOATS;
        int[] boneIds = new int[vertexCount * influences];
        float[] weights = new float[vertexCount * influences];
        for (int v = 0; v < vertexCount; v++) {
            weights[v * influences] = 1f;
        }
        return new MeshSkin(new String[]{"bone"}, influences, boneIds, weights,
            null, -1,
            new float[]{0f}, new float[]{0f}, new float[]{1f}, new float[]{0f});
    }

    @Test
    void skinBakerAttachesStripWhenFramesLineUpWithTheClip() {
        MeshSkin skin = singleBoneSkin();
        int frames = 2;
        // Clip layout: 6 floats per bone and frame (m00, m01, m03, m10, m11, m13).
        float[] matrices = new float[frames * 1 * FLOATS_PER_MATRIX];
        for (int frame = 0; frame < frames; frame++) {
            int o = (frame * 1) * FLOATS_PER_MATRIX;
            matrices[o] = 1f;     // m00
            matrices[o + 4] = 1f; // m11
        }
        MeshSkinClip clip = new MeshSkinClip("walk", true, 30f, 1,
            new float[]{0f, 1f / 30f}, matrices);

        List<BlendableMesh> poses = List.of(pose("demo_walk_0", COLOR_INDICES),
            pose("demo_walk_1", new int[]{2, 2, 2, 2, 2, 2}));

        VatAnimationData withColors =
            VatSkinBaker.animation("walk", skin, clip, poses, palette());
        assertTrue(withColors.hasFrameColors());
        assertEquals(6, withColors.colorVertexCount());
        assertEquals(2 * 6, withColors.frameColors().length);

        // A mismatch (e.g. a subdivided clip) must silently fall back, never fail the load.
        VatAnimationData onePose =
            VatSkinBaker.animation("walk", skin, clip, List.of(poses.get(0)), palette());
        assertFalse(onePose.hasFrameColors());

        // The plain 3-argument overload never allocates a strip.
        assertFalse(VatSkinBaker.animation("walk", skin, clip).hasFrameColors());
    }
}
