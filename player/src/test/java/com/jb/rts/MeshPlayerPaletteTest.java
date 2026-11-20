package com.jb.rts;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the mesh player's <b>colour table</b>: the panel must offer exactly <b>one row per colour
 * of the loaded export</b> (the JSON's {@code "colors"} array) and label each row with the colour
 * name the file wrote — that is the feature the table exists for (adjust every colour of the file
 * individually instead of overriding them all with one).
 *
 * <p>{@link MeshPlayer#paletteTitle(ColorPalette, int)} is the row-title mapping the panel uses for
 * every entry; the tests read the real bundled exports through the class path (exactly what the
 * player does inside its jar) and need no GL context.</p>
 */
class MeshPlayerPaletteTest {

    private static final String DINO = "meshes/dino_longneck-export.json";

    @Test
    void theColourTableHasOneRowPerColourInTheJson() throws IOException {
        String json = readResource(DINO);
        JsonValue colors = new JsonReader().parse(json).get("colors");
        assertNotNull(colors, "'" + DINO + "' must define colours");
        MeshExport export = MeshExportLoader.loadFromString(json);

        List<String> titles = titles(export.palette);

        assertEquals(colors.size, export.palette.size(), "one entry per colour in the file");
        assertEquals(colors.size, titles.size(), "one row per colour in the file");

        int index = 0;
        for (JsonValue color = colors.child; color != null; color = color.next, index++) {
            assertEquals(color.getString("name"), titles.get(index),
                "row " + index + " must keep the colour name of the JSON");
        }
    }

    @Test
    void unnamedColoursGetAnIndexedTitle() {
        ColorPalette palette = new ColorPalette();
        palette.addColor("fur", 0.1f, 0.2f, 0.3f, 1f);
        palette.addColor("  ", 0.4f, 0.5f, 0.6f, 1f);
        palette.addColor(null, 0.7f, 0.8f, 0.9f, 1f);

        assertEquals("fur", MeshPlayer.paletteTitle(palette, 0));
        assertEquals("Colour 2", MeshPlayer.paletteTitle(palette, 1));
        assertEquals("Colour 3", MeshPlayer.paletteTitle(palette, 2));
    }

    @Test
    void everyBundledExportLabelsItsColours() throws IOException {
        for (String path : MeshPlayer.BUNDLED_EXAMPLES) {
            MeshExport export = MeshExportLoader.loadFromString(readResource(path));
            List<String> titles = titles(export.palette);

            assertEquals(export.palette.size(), titles.size(),
                "'" + path + "' must list one row per palette colour");
            for (String title : titles) {
                assertNotNull(title, "'" + path + "' left a row without a title");
                assertFalse(title.trim().isEmpty(), "'" + path + "' left a row with a blank title");
            }
        }
    }

    /** Row titles exactly as {@link com.jb.rts.player.ui.MeshPlayerControls#setPalette} receives them. */
    private static List<String> titles(ColorPalette palette) {
        List<String> titles = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            titles.add(MeshPlayer.paletteTitle(palette, i));
        }
        return titles;
    }

    /** Reads a bundled export the way the player does: from the class path (inside the jar). */
    private static String readResource(String path) throws IOException {
        try (InputStream in = MeshPlayer.class.getResourceAsStream("/" + path)) {
            assertNotNull(in, "resource '" + path + "' is missing from the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
