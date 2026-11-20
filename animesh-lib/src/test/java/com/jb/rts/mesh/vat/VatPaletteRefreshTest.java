package com.jb.rts.mesh.vat;

import com.badlogic.gdx.graphics.Color;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the <b>live palette</b> feature of the VAT pipeline: a baked batch keeps the palette
 * <em>indexes</em> of its colours, so editing one palette entry recolours exactly the mesh parts
 * that reference it — without a re-bake.
 *
 * <p>This is what the mesh player's colour table relies on (one row per colour of the export's
 * JSON): {@link VatMeshData#toInterleavedVertices(ColorPalette)} re-resolves the static colours and
 * {@link VatPalette#resolveFrameColors(int[], int, int, ColorPalette)} re-resolves the per-frame
 * colour strip. Both are GL-free, so they are covered here.
 */
class VatPaletteRefreshTest {

    /** Six expanded vertices (two triangles). */
    private static final float[] BIND = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        0f, 1f, 0f,
        1f, 1f, 0f,
        2f, 0f, 0f,
        2f, 1f, 0f
    };

    private static final short[] INDICES = {0, 1, 2, 3, 4, 5};

    /** First triangle uses palette entry 0, second one entry 1. */
    private static final int[] COLOR_A = {0, 0, 0, 1, 1, 1};

    /** The last frame recolours every corner to palette entry 2 (so a strip is baked). */
    private static final int[] COLOR_B = {2, 2, 2, 2, 2, 2};

    /** Offset of the packed colour inside one interleaved vertex (position is 3 floats). */
    private static final int COLOR_SLOT = 3;

    private static ColorPalette palette() {
        ColorPalette palette = new ColorPalette();
        palette.addColor("red", 1f, 0f, 0f, 1f);
        palette.addColor("green", 0f, 1f, 0f, 0.5f);
        palette.addColor("blue", 0f, 0f, 1f, 1f);
        return palette;
    }

    private static BlendableMesh pose(String name, int[] colorIndices) {
        return new BlendableMesh(name, BIND, new float[BIND.length], colorIndices, INDICES,
            "demo", "walk", 0);
    }

    /**
     * Four frames: three with the bind colours, the last recoloured. The run of three frames makes
     * the baker ramp the colours (see {@code VatPalette.smoothColorChanges}), so the strip really
     * has interpolated texels and the refresh comparison below is meaningful.
     */
    private static List<BlendableMesh> poses() {
        List<BlendableMesh> frames = new ArrayList<>();
        frames.add(pose("demo_walk_0", COLOR_A));
        frames.add(pose("demo_walk_1", COLOR_A));
        frames.add(pose("demo_walk_2", COLOR_A));
        frames.add(pose("demo_walk_3", COLOR_B));
        return frames;
    }

    private static VatPoseBaker.Result bake(ColorPalette palette) {
        return VatPoseBaker.bake("demo_walk", poses(), palette, 30f);
    }

    /** Expected {@code a_color} slot of a palette entry, using the same packing as the upload. */
    private static float packed(ColorPalette palette, int index) {
        return Color.toFloatBits(
            Math.round(palette.red(index) * 255f),
            Math.round(palette.green(index) * 255f),
            Math.round(palette.blue(index) * 255f),
            Math.round(palette.alpha(index) * 255f));
    }


    @Test
    void bakedDataKeepsThePaletteIndexes() {
        VatPoseBaker.Result baked = bake(palette());

        assertTrue(baked.mesh().hasPaletteIndices(), "the bind pose's colour indexes are kept");
        assertArrayEquals(COLOR_A, baked.mesh().paletteIndices());

        VatAnimationData animation = baked.animation();
        assertTrue(animation.hasFrameColors(), "the recoloured frame must bake a strip");
        assertTrue(animation.hasFrameColorIndices(), "the strip must keep its palette indexes");
        assertEquals(animation.frameColors().length, animation.frameColorIndices().length);
        assertArrayEquals(COLOR_A,
            Arrays.copyOfRange(animation.frameColorIndices(), 0, COLOR_A.length),
            "frame 0 keeps the bind pose's indexes");
        assertArrayEquals(COLOR_B,
            Arrays.copyOfRange(animation.frameColorIndices(),
                animation.frameColorIndices().length - COLOR_B.length,
                animation.frameColorIndices().length),
            "the last frame keeps its own indexes");
        assertNotEquals(animation.frameColors()[0], animation.frameColors()[COLOR_A.length],
            "the frames between the two keyframes must be ramped");
    }

    @Test
    void editingOneColourRecoloursOnlyItsVertices() {
        ColorPalette palette = palette();
        VatMeshData mesh = bake(palette).mesh();
        float[] before = mesh.toInterleavedVertices(palette);

        // Edit palette entry 1 (the second triangle) only.
        palette.setColor(1, 0.25f, 0.5f, 0.75f, 1f);
        float[] after = mesh.toInterleavedVertices(palette);

        for (int v = 0; v < COLOR_A.length; v++) {
            int slot = v * VatFormat.FLOATS_PER_VERTEX + COLOR_SLOT;
            if (COLOR_A[v] == 1) {
                assertEquals(packed(palette, 1), after[slot], 0f,
                    "vertex " + v + " must show the edited colour");
                assertNotEquals(before[slot], after[slot],
                    "vertex " + v + " must actually have changed");
            } else {
                assertEquals(before[slot], after[slot], 0f,
                    "vertex " + v + " uses another palette entry and must be untouched");
            }
            // Geometry and skinning data are not part of a recolour.
            for (int k = 0; k < VatFormat.FLOATS_PER_VERTEX; k++) {
                if (k == COLOR_SLOT) continue;
                assertEquals(before[v * VatFormat.FLOATS_PER_VERTEX + k],
                    after[v * VatFormat.FLOATS_PER_VERTEX + k], 0f,
                    "recolouring must not touch slot " + k);
            }
        }
    }

    @Test
    void refreshedStripEqualsAFreshBake() {
        ColorPalette palette = palette();
        VatAnimationData baked = bake(palette).animation();

        // The user edits the colour every corner switches to in the last frame.
        palette.setColor(2, 1f, 1f, 0f, 1f);

        int[] refreshed = VatPalette.resolveFrameColors(baked.frameColorIndices(),
            baked.colorVertexCount(), baked.frameCount(), palette);
        int[] rebaked = bake(palette).animation().frameColors();

        assertArrayEquals(rebaked, refreshed,
            "refreshing the strip must reproduce the bake, including the colour ramps");
    }

    @Test
    void meshWithoutPaletteIndexesIsNotRecoloured() {
        VatMeshData baked = bake(palette()).mesh();
        VatMeshData plain = new VatMeshData(baked.positions().clone(), baked.colors().clone(),
            baked.boneIds().clone(), baked.weights().clone(), null, baked.boneNames(),
            null, null);

        assertFalse(plain.hasPaletteIndices());
        assertArrayEquals(plain.toInterleavedVertices(), plain.toInterleavedVertices(palette()),
            "a mesh without palette indexes keeps its baked colours");
    }

    @Test
    void frameColorIndexesRequireAStrip() {
        VatAnimationData animation = bake(palette()).animation();

        assertThrows(IllegalArgumentException.class, () -> new VatAnimationData(
            animation.name(), animation.boneCount(), animation.frameCount(), animation.fps(),
            animation.looping(), animation.frameTimes(), animation.boneMatrices(),
            animation.colorVertexCount(), null, animation.stepFrames(),
            animation.frameColorIndices()));
    }

    @Test
    void reResolvingWithAMissingPaletteFallsBackToWhite() {
        // Out-of-range indexes (a palette that shrank) become white, matching the bake path.
        float[] colors = VatPalette.resolve(new int[]{0, 7}, new ColorPalette());

        assertArrayEquals(new float[]{1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f}, colors);
    }
}

