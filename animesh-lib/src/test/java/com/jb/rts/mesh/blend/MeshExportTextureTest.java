package com.jb.rts.mesh.blend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export version 6 adds a model texture: the top-level {@code textureFile} names the picture and
 * {@code uvVertices} holds one {@code {u, v}} pair per <em>shared</em> vertex. The loader must
 * report the texture and expand the UVs to the per-triangle-corner layout the renderers use.
 */
class MeshExportTextureTest {

    private static final String TEXTURED = """
            {
              "name": "t",
              "textureFile": "atlas.png",
              "colors": [
                { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1.0 }
              ],
              "vertices": [
                { "x": 0.0, "y": 0.0 }, { "x": 1.0, "y": 0.0 },
                { "x": 0.0, "y": 1.0 }, { "x": 1.0, "y": 1.0 }
              ],
              "uvVertices": [
                { "u": 0.0, "v": 0.0 }, { "u": 1.0, "v": 0.0 },
                { "u": 0.0, "v": 1.0 }, { "u": 1.0, "v": 1.0 }
              ],
              "objects": [
                { "name": "o", "triangles": [
                  { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                  { "vertices": [0, 2, 3], "colors": [0, 0, 0] }
                ] }
              ],
              "animations": []
            }
            """;

    @Test
    void loadsTheTextureAndExpandsItsUvsToTheCornerLayout() {
        MeshExport export = MeshExportLoader.loadFromString(TEXTURED);

        assertTrue(export.hasTexture());
        assertEquals("atlas.png", export.textureFile);
        assertTrue(export.hasUvs());
        // Shared refs of the two triangles: [0,1,2, 0,2,3] -> one UV pair per corner.
        assertArrayEquals(new float[]{
                0f, 0f, 1f, 0f, 0f, 1f,
                0f, 0f, 0f, 1f, 1f, 1f
        }, export.uvs, 1e-6f);
        assertEquals(1f, export.u(1), 1e-6f);
        assertEquals(1f, export.v(5), 1e-6f);
    }

    @Test
    void exportsWithoutATextureKeepThePaletteColours() {
        MeshExport export = MeshExportLoader.loadFromString(
                TEXTURED.replace("\"textureFile\": \"atlas.png\",", "")
                        .replaceAll("\"uvVertices\": \\[[^]]*],", ""));

        assertFalse(export.hasTexture());
        assertNull(export.textureFile);
        assertFalse(export.hasUvs());
        assertEquals(-1f, export.u(0), 1e-6f, "unset UVs report -1");
    }

    /** Misaligned UVs would sample arbitrary texels: the export must be rejected loudly. */
    @Test
    void rejectsUvTablesThatDoNotMatchTheVertexCount() {
        String broken = TEXTURED.replace("{ \"u\": 1.0, \"v\": 1.0 }", "");
        assertThrows(IllegalArgumentException.class,
                () -> MeshExportLoader.loadFromString(broken));
    }

    /** The real fire export is the reference artefact for the feature. */
    @Test
    void realFireExportCarriesItsAtlas() {
        java.io.File file = java.nio.file.Paths.get(
                "..", "test-assets", "meshes", "fire-export.json").toFile();
        org.junit.jupiter.api.Assumptions.assumeTrue(file.isFile(),
                "fire-export.json not found; skipping");

        MeshExport export = MeshExportLoader.load(
                new com.badlogic.gdx.files.FileHandle(file));

        assertTrue(export.hasTexture(), "fire-export.json must declare its texture");
        assertEquals("fire_texture.png", export.textureFile);
        assertTrue(new java.io.File(file.getParentFile(), export.textureFile).isFile(),
                "the atlas must ship next to the export");
        assertTrue(export.hasUvs());
        assertEquals(export.meshes.get(0).vertexCount() * MeshExport.UV_FLOATS,
                export.uvs.length, "one UV pair per expanded vertex");
    }
}
