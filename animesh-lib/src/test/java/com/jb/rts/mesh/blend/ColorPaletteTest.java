package com.jb.rts.mesh.blend;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorPaletteTest {

    @Test
    void addColorReturnsSequentialIndexes() {
        ColorPalette p = new ColorPalette();
        assertEquals(0, p.addColor("red", 1f, 0f, 0f, 1f));
        assertEquals(1, p.addColor("blue", 0f, 0f, 1f, 0.5f));
        assertEquals(2, p.size());
        assertEquals("blue", p.name(1));
        assertEquals(1f, p.red(0));
        assertEquals(0.5f, p.alpha(1));
    }

    @Test
    void fromJsonNormalizesRgbAndReadsAlpha() {
        // {"name":"fur","red":255,"green":128,"blue":0,"alpha":0.5}
        String json = "{\"colors\":[{\"name\":\"fur\",\"red\":255,\"green\":128,\"blue\":0,\"alpha\":0.5}]}";
        JsonValue root = new JsonReader().parse(json);
        ColorPalette p = ColorPalette.fromJson(root.get("colors"));

        assertEquals(1, p.size());
        assertEquals(1f, p.red(0), 1e-6f);
        assertEquals(128f / 255f, p.green(0), 1e-6f);
        assertEquals(0f, p.blue(0), 1e-6f);
        assertEquals(0.5f, p.alpha(0), 1e-6f);
        assertEquals("fur", p.name(0));
    }

    @Test
    void fromJsonNullYieldsEmptyPalette() {
        assertEquals(0, ColorPalette.fromJson(null).size());
    }

    @Test
    void setColorReplacesInPlace() {
        ColorPalette p = new ColorPalette();
        p.addColor("red", 1f, 0f, 0f, 1f);
        p.setColor(0, 0f, 1f, 0f, 1f); // replace red with green
        assertEquals("red", p.name(0));   // name kept
        assertEquals(0f, p.red(0));
        assertEquals(1f, p.green(0));
    }

    @Test
    void outOfRangeIndexThrows() {
        ColorPalette p = new ColorPalette();
        p.addColor("x", 1f, 1f, 1f, 1f);
        assertThrows(IndexOutOfBoundsException.class, () -> p.red(1));
        assertThrows(IndexOutOfBoundsException.class, () -> p.red(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> p.setColor(5, 0, 0, 0, 1));
    }

    @Test
    void paletteCapsAtMaxColors() {
        ColorPalette p = new ColorPalette();
        for (int i = 0; i < ColorPalette.MAX_COLORS; i++) {
            p.addColor("c" + i, 0, 0, 0, 1);
        }
        assertEquals(ColorPalette.MAX_COLORS, p.size());
        assertThrows(IllegalStateException.class, () -> p.addColor("too-many", 0, 0, 0, 1));
        assertTrue(ColorPalette.MAX_COLORS <= 64); // must fit the shader if-chain budget
    }
}
