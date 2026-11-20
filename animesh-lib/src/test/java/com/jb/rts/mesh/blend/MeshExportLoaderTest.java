package com.jb.rts.mesh.blend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class MeshExportLoaderTest {

    private static final String SAMPLE = """
            {
              "name": "t",
              "colors": [
                { "name": "a", "red": 255, "green": 0,   "blue": 0,   "alpha": 1.0 },
                { "name": "b", "red": 0,   "green": 0,   "blue": 255, "alpha": 1.0 }
              ],
              "vertices": [
                { "x": 0.0, "y": 0.0, "z": 1.0 },
                { "x": 1.0, "y": 0.0, "z": 2.0 },
                { "x": 0.0, "y": 1.0, "z": 3.0 },
                { "x": 1.0, "y": 1.0, "z": 4.0 }
              ],
              "objects": [
                { "name": "o1", "triangles": [
                  { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                  { "vertices": [0, 2, 3], "colors": [1, 1, 1] }
                ] }
              ],
              "animations": [
                { "name": "walk", "frames": [
                  { "name": "f0", "offsets": [
                      { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 },
                      { "x": 0.0, "y": 0.0, "z": 0.0 }, { "x": 0.0, "y": 0.0, "z": 0.0 } ] },
                  { "name": "f1", "offsets": [
                      { "x": 1.0, "y": 0.0, "z": 0.5 }, { "x": 1.0, "y": 0.0, "z": 0.5 },
                      { "x": 1.0, "y": 0.0, "z": 0.5 }, { "x": 1.0, "y": 0.0, "z": 0.5 } ] }
                ] }
              ]
            }
            """;

    @Test
    void loadsPaletteNameAndSharedVertexCount() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        assertEquals("t", e.name);
        assertEquals(4, e.sharedVertexCount);
        assertEquals(2, e.palette.size());
        assertEquals(1f, e.palette.red(0), 1e-6f); // index 0 red
        assertEquals(1f, e.palette.blue(1), 1e-6f); // index 1 blue
        // The sample JSON does not declare a metric -> defaults to millimetres.
        assertEquals("mm", e.measurement);
    }

    @Test
    void explicitMeasurementIsPreserved() {
        MeshExport e = MeshExportLoader.loadFromString(
                "{\"name\":\"x\",\"measurement\":\"cm\","
                        + "\"colors\":[{\"name\":\"a\",\"red\":255,\"green\":0,\"blue\":0,\"alpha\":1}],"
                        + "\"vertices\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":0},{\"x\":0,\"y\":1}],"
                        + "\"objects\":[{\"name\":\"o\",\"triangles\":[{\"vertices\":[0,1,2],\"colors\":[0,0,0]}]}]}");
        assertEquals("cm", e.measurement);
    }

    @Test
    void yOrientationDefaultsToYUpWhenTheExportDoesNotDeclareIt() {
        // Samples written before the "yUp" flag existed: the exporter already wrote cartesian
        // y-up coordinates then, so the loader must not report y-down.
        assertTrue(MeshExportLoader.loadFromString(SAMPLE).yUp);
    }

    @Test
    void declaredYDownOrientationIsReported() {
        String yDown = SAMPLE.replace("\"name\": \"t\",",
                "\"name\": \"t\", \"yUp\": false,");
        assertFalse(MeshExportLoader.loadFromString(yDown).yUp);
    }

    @Test
    void declaredYUpOrientationIsReported() {
        String yUp = SAMPLE.replace("\"name\": \"t\",",
                "\"name\": \"t\", \"yUp\": true,");
        assertTrue(MeshExportLoader.loadFromString(yUp).yUp);
    }

    @Test
    void loaderKeepsTheCoordinatesUntouched() {
        // Orientation is metadata only: the loader must never transform positions itself.
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        BlendableMesh rest = e.meshes.get(0);
        assertEquals(0f, rest.defaultPositions[0], 1e-6f); // x of the first shared vertex
        assertEquals(0f, rest.defaultPositions[1], 1e-6f); // y stays 0 (not negated)
        assertEquals(1f, rest.defaultPositions[2], 1e-6f); // z stays 1
        // walk frame 1: offsets (1, 0, 0.5) — y and z are not transformed either.
        assertEquals(1f, e.meshes.get(2).positions[0], 1e-6f);
        assertEquals(0f, e.meshes.get(2).positions[1], 1e-6f);
        assertEquals(0.5f, e.meshes.get(2).positions[2], 1e-6f);
    }

    @Test
    void registersRestPosePlusEveryAnimationFrame() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        assertEquals(3, e.meshes.size());           // rest + walk_0 + walk_1
        assertEquals("t_rest_0", e.meshes.get(0).name);
        assertEquals("t_walk_0", e.meshes.get(1).name);
        assertEquals("t_walk_1", e.meshes.get(2).name);
        assertEquals("t", e.meshes.get(0).prefix());
    }

    @Test
    void allPosesShareIdenticalTopology() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        for (BlendableMesh m : e.meshes) {
            assertTrue(e.meshes.get(0).sameTopology(m), m.name + " must share topology");
        }
    }

    @Test
    void indicesAreExpandedAndSequential() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        BlendableMesh rest = e.meshes.get(0);
        // two triangles -> six expanded vertices -> six indices, sequential
        assertEquals(6, rest.vertexCount());
        assertEquals(2, rest.triangleCount());
        for (int i = 0; i < rest.indices.length; i++) {
            assertEquals(i, rest.indices[i]);
        }
    }

    @Test
    void sharedVertexGetsDifferentColourPerTriangle() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        BlendableMesh rest = e.meshes.get(0);
        // Vertex 0 is shared by triangle 0 (colour 0) and triangle 1 (colour 1).
        // Expanded vertex 0 = tri0 corner (shared idx 0, colour 0)
        // Expanded vertex 3 = tri1 corner (shared idx 0, colour 1)
        assertEquals(0, rest.colorIndices[0]);
        assertEquals(1, rest.colorIndices[3]);
    }

    @Test
    void rejectsOutOfRangeColourIndex() {
        String bad = SAMPLE.replace("\"colors\": [1, 1, 1]", "\"colors\": [1, 1, 9]");
        assertThrows(IllegalArgumentException.class,
                () -> MeshExportLoader.loadFromString(bad));
    }

    /**
     * Damaged meshes can contain leftover triangles that reference a vertex right after the
     * last one (the shipped {@code dino_longneck-export.json} contained 21 of them before they
     * were cleaned up). The mesheditor's {@code PresentationBuilder} skips those triangles, so
     * the editor still shows the mesh; the loader must do the same instead of failing with an
     * index error.
     */
    @Test
    void skipsTrianglesThatReferenceNonExistentVertices() {
        String damaged = """
                {
                  "name": "damaged",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
                  "vertices": [ { "x": 0, "y": 0 }, { "x": 1, "y": 0 }, { "x": 0, "y": 1 } ],
                  "objects": [ { "name": "o1", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                    { "vertices": [0, 1, 3], "colors": [0, 0, 0] },
                    { "vertices": [-1, 1, 2], "colors": [0, 0, 0] },
                    { "vertices": [3, 3, 3], "colors": [0, 0, 0] }
                  ] } ]
                }
                """;

        MeshExport e = MeshExportLoader.loadFromString(damaged);

        assertEquals(3, e.skippedTriangles, "three of four triangles are unusable");
        assertEquals(3, e.sharedVertexCount);
        BlendableMesh rest = e.meshes.get(0);
        assertEquals(1, rest.triangleCount(), "only the valid triangle may reach the topology");
        assertEquals(3, rest.vertexCount(), "3 corners of the single valid triangle");
        assertArrayEquals(new short[]{0, 1, 2}, rest.indices);
    }

    @Test
    void exportWithOnlyInvalidTrianglesReportsWhyItCannotBeUsed() {
        String onlyInvalid = """
                {
                  "name": "broken",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
                  "vertices": [ { "x": 0, "y": 0 }, { "x": 1, "y": 0 }, { "x": 0, "y": 1 } ],
                  "objects": [ { "name": "o1", "triangles": [
                    { "vertices": [0, 1, 5], "colors": [0, 0, 0] }
                  ] } ]
                }
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MeshExportLoader.loadFromString(onlyInvalid));
        assertTrue(ex.getMessage().contains("no usable triangles"), ex.getMessage());
    }

    @Test
    void validExportSkipsNoTriangles() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        assertEquals(0, e.skippedTriangles);
    }

    @Test
    void legacyAbsoluteFrameVerticesStillParse() {
        // Old exporter wrote absolute per-frame "vertices" lists (no z, no offsets).
        String legacy = """
                {
                  "name": "t",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
                  "vertices": [
                    { "x": 0.0, "y": 0.0 }, { "x": 1.0, "y": 0.0 },
                    { "x": 0.0, "y": 1.0 }, { "x": 1.0, "y": 1.0 }
                  ],
                  "objects": [ { "name": "o1", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] },
                    { "vertices": [0, 2, 3], "colors": [0, 0, 0] }
                  ] } ],
                  "animations": [ { "name": "walk", "frames": [
                    { "name": "f0", "vertices": [
                        { "x": 0.0, "y": 0.0 }, { "x": 1.0, "y": 0.0 },
                        { "x": 0.0, "y": 1.0 }, { "x": 1.0, "y": 1.0 } ] },
                    { "name": "f1", "vertices": [
                        { "x": 2.0, "y": 0.0 }, { "x": 3.0, "y": 0.0 },
                        { "x": 2.0, "y": 1.0 }, { "x": 3.0, "y": 1.0 } ] }
                  ] } ]
                }
                """;
        MeshExport e = MeshExportLoader.loadFromString(legacy);
        BlendableMesh rest = e.meshes.get(0);
        // rest pose equals the top-level vertices (z defaults to 0)
        assertEquals(0f, rest.positions[0]);
        assertEquals(0f, rest.positions[2]); // z=0 for missing z
        BlendableMesh w1 = e.meshes.get(2);
        // legacy frame 1 uses its own absolute vertices (shared 0 -> (2,0))
        assertEquals(2f, w1.positions[0]);
        assertEquals(0f, w1.positions[1]);
    }

    @Test
    void frameWithoutPositionDataFallsBackToDefaultPose() {
        // include-vertices = off exports frames with neither offsets nor vertices.
        String noFrames = """
                {
                  "name": "t",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
                  "objects": [ { "name": "o1", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] }
                  ] } ],
                  "animations": [ { "name": "walk", "frames": [
                    { "name": "f0" }
                  ] } ]
                }
                """;
        // No top-level vertices: shared count derived from the triangles reference.
        MeshExport e = MeshExportLoader.loadFromString(noFrames);
        assertEquals(3, e.sharedVertexCount);
        BlendableMesh frame = e.meshes.get(1);
        // All positions are zero (no default pose information available).
        boolean allZero = true;
        for (float p : frame.positions)
            if (p != 0f) {
                allZero = false;
                break;
            }
        assertTrue(allZero, "frame without data must be a zero pose");
        BlendableMesh rest = e.meshes.get(0);
        assertTrue(rest.sameTopology(frame));
    }

    @Test
    void offsetsRejectWrongSize() {
        // One offset entry for a 3-vertex mesh: must be rejected.
        String bad = """
                {
                  "name": "t",
                  "colors": [ { "name": "a", "red": 255, "green": 0, "blue": 0, "alpha": 1 } ],
                  "vertices": [ { "x": 0, "y": 0 }, { "x": 1, "y": 0 }, { "x": 0, "y": 1 } ],
                  "objects": [ { "name": "o1", "triangles": [
                    { "vertices": [0, 1, 2], "colors": [0, 0, 0] }
                  ] } ],
                  "animations": [ { "name": "walk", "frames": [
                    { "name": "f0", "offsets": [ { "x": 0.0, "y": 0.0, "z": 0.0 } ] }
                  ] } ]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> MeshExportLoader.loadFromString(bad));
    }

    @Test
    void realTreeRedwoodExportParsesAndAllPosesShareTopology() throws Exception {
        // Real-life example shipped with the repo. Skipped if not present (e.g. outside the repo).
        File f = Paths.get("..", "test-assets", "meshes", "tree_redwood-export.json").toFile();
        Assumptions.assumeTrue(f.isFile(), "tree_redwood-export.json not found; skipping");

        MeshExport e = MeshExportLoader.loadFromString(Files.readString(f.toPath()));
        assertEquals("tree_redwood", e.name);
        assertEquals("m", e.measurement); // the tree_redwood export declares metres
        assertEquals(4, e.palette.size());
        assertTrue(!e.meshes.isEmpty());

        BlendableMesh base = e.meshes.get(0);
        assertTrue(base.triangleCount() > 0);
        for (BlendableMesh m : e.meshes) {
            assertTrue(base.sameTopology(m), m.name + " must share topology with rest pose");
        }
        // Sanity: every colour index in the mesh must be within the exported palette.
        for (int i = 0; i < base.colorIndices.length; i++) {
            assertTrue(base.colorIndices[i] >= 0 && base.colorIndices[i] < e.palette.size());
        }
    }

    /**
     * The real dino export used to contain 21 leftover triangles that referenced vertex index
     * 181 while the file only defines 181 vertices (indexes 0..180). They have been removed from
     * the shipped file; the exporter now has to produce a clean export, and the loader must load
     * every triangle. (Skipping of damaged triangles is covered by the synthetic test above.)
     */
    @Test
    void realDinoExportLoadsCleanly() throws Exception {
        File f = Paths.get("..", "test-assets", "meshes", "dino_longneck-export.json").toFile();
        Assumptions.assumeTrue(f.isFile(), "dino_longneck-export.json not found; skipping");

        MeshExport e = MeshExportLoader.loadFromString(Files.readString(f.toPath()));

        assertEquals("dino_longneck", e.name);
        assertEquals(181, e.sharedVertexCount);
        assertEquals(0, e.skippedTriangles, "the shipped export has no out-of-range triangles");
        // One pose per baked clip frame (plus the rest pose): the loader drops the poses of
        // zero-length keyframe intervals, exactly like SkinExportParser merges them, so the pose
        // list and the clips stay index-aligned.
        int clipFrames = 0;
        for (MeshSkinClip clip : e.skinClips) clipFrames += clip.frameCount;
        assertEquals(clipFrames + 1, e.meshes.size(),
            "rest pose + one pose per baked clip frame");
        BlendableMesh rest = e.meshes.get(0);
        assertEquals(228, rest.triangleCount(), "all exported triangles are usable");
        for (BlendableMesh m : e.meshes) {
            assertTrue(rest.sameTopology(m), m.name + " must share topology with rest pose");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @Test
    void stepVerticesAreExpandedToTheRendererLayout() {
        String withSteps = SAMPLE.replace("\"name\": \"t\",",
                "\"name\": \"t\", \"stepVertices\": [1, 3],");
        MeshExport e = MeshExportLoader.loadFromString(withSteps);
        assertTrue(e.hasStepVertices());
        // shared refs of the two triangles: [0,1,2,0,2,3]
        assertArrayEquals(new boolean[]{false, true, false, false, false, true},
                e.stepVertices);
        assertTrue(e.isStepVertex(1));
        assertFalse(e.isStepVertex(0));
    }

    @Test
    void missingStepVerticesMeanNoAttachmentGeometry() {
        MeshExport e = MeshExportLoader.loadFromString(SAMPLE);
        assertFalse(e.hasStepVertices());
        assertFalse(e.isStepVertex(0));
    }

    @Test
    void attachmentStepFlagsAreReadPerAnimation() {
        String withSteps = SAMPLE.replace("\"name\": \"walk\",",
                "\"name\": \"walk\", \"attachmentSteps\": [false, true],");
        MeshExport e = MeshExportLoader.loadFromString(withSteps);
        boolean[] steps = e.animationStepFrames.get("walk");
        assertNotNull(steps);
        assertEquals(2, steps.length);
        assertFalse(steps[0]);
        assertTrue(steps[1]);
    }
}
