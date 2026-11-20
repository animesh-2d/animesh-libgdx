package com.jb.rts.mesh.blend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the skin parsing of {@link MeshExportLoader}: the version-4 {@code "skin"} block, the
 * legacy top-level bone weights (version 2/3), top-3 influence resolution and the merging of
 * zero-length keyframe intervals.
 */
class MeshExportSkinTest {

    private static final String V4_SKIN = """
        {
          "name": "skin_demo", "version": 4, "yUp": true, "measurement": "px",
          "colors": [{"name": "red", "red": 255, "green": 0, "blue": 0, "alpha": 1}],
          "vertices": [{"x": 0, "y": 0}, {"x": 10, "y": 0}, {"x": 0, "y": 10}],
          "objects": [{"name": "o", "triangles": [{"vertices": [0, 1, 2], "colors": [0, 0, 0]}]}],
          "skin": {
            "influences": 3, "bindPoseRow": -1,
            "boneNames": ["root"], "bind": [[0, 0, 1, 0]],
            "sourceVertex": [0, 1, 2],
            "boneIds": [0, 0, 0, 0, 0, 0, 0, 0, 0],
            "weights": [1, 0, 0, 1, 0, 0, 1, 0, 0]
          },
          "animations": [{
            "name": "idle", "looping": true, "fps": 30, "frameTimes": [0, 0.5, 0.5],
            "frames": [
              {"boneMatrices": [[0, 1, 0, 0, 0, 1, 0]]},
              {"boneMatrices": [[0, 1, 0, 2, 0, 1, 0]],
               "offsets": [{"x": 2, "y": 0}, {"x": 2, "y": 0}, {"x": 2, "y": 0}]},
              {"boneMatrices": [[0, 1, 0, 3, 0, 1, 0]],
               "offsets": [{"x": 3, "y": 0}, {"x": 3, "y": 0}, {"x": 3, "y": 0}]}
            ]
          }]
        }
        """;

    @Test
    void readsVersion4SkinBlockAndMergesEqualKeyframeTimes() {
        MeshExport export = MeshExportLoader.loadFromString(V4_SKIN);

        assertNotNull(export.skin);
        assertEquals(1, export.skin.boneCount());
        assertEquals(-1, export.skin.bindPoseRow);
        assertEquals(3, export.skin.influences);
        assertEquals(3, export.skin.vertexCount());
        assertEquals(1f, export.skin.weights[0], 1e-6f);
        assertEquals(0f, export.skin.bindCenterX[0], 1e-6f);
        assertEquals(1f, export.skin.bindDirX[0], 1e-6f);
        assertEquals(1, export.skinClips.size());
        MeshSkinClip clip = export.skinClips.get(0);
        assertEquals("idle", clip.name);
        assertTrue(clip.looping);
        // times 0.5 / 0.5 are one zero-length interval: the later pose wins
        assertEquals(2, clip.frameCount);
        assertEquals(0f, clip.frameTimes[0], 1e-6f);
        assertEquals(0.5f, clip.frameTimes[1], 1e-6f);
        assertEquals(1f, clip.value(0, 1, 0), 1e-6f, "m00");
        assertEquals(3f, clip.value(0, 1, 2), 1e-6f, "translation x of the kept (later) frame");

        // The pose list must stay index-aligned with the clip: the superseded pose of the
        // zero-length interval is dropped as well, so pose 1 is the *later* authored frame.
        assertEquals(3, export.meshes.size(), "rest pose + two kept frames");
        assertEquals(3f, export.meshes.get(2).positions[0], 1e-6f,
            "the kept pose is the later one at the shared keyframe time");
        assertEquals(1, export.meshes.get(2).poseIndex(), "kept poses are renumbered densely");
    }

    private static final String V3_BONES = """
        {
          "name": "legacy", "version": 3, "yUp": true, "measurement": "px",
          "colors": [{"name": "red", "red": 255, "green": 0, "blue": 0, "alpha": 1}],
          "vertices": [{"x": 0, "y": 0}, {"x": 10, "y": 0}, {"x": 0, "y": 10}],
          "objects": [{"name": "o", "triangles": [{"vertices": [0, 1, 2], "colors": [0, 0, 0]}]}],
          "bones": [{
            "name": "root", "type": "BONE",
            "center": {"x": 0, "y": 0}, "direction": {"x": 1, "y": 0},
            "weights": [1, 0, 0]
          }],
          "animations": [{
            "name": "turn", "frames": [{
              "bones": [{"name": "root", "center": {"x": 0, "y": 0}, "direction": {"x": 0, "y": 1}}]
            }]
          }]
        }
        """;

    @Test
    void legacyBoneWeightsBecomeTopThreeInfluencesWithBindPoseRow() {
        MeshExport export = MeshExportLoader.loadFromString(V3_BONES);

        assertNotNull(export.skin);
        // one deforming bone + synthetic identity row for the two unweighted vertices
        assertEquals(2, export.skin.boneCount());
        assertEquals(1, export.skin.bindPoseRow);
        assertEquals("root", export.skin.boneNames[0]);
        assertEquals(MeshExportSkinTest.BIND_POSE_NAME, export.skin.boneNames[1]);
        // corner 0 -> bone 0 with weight 1; corners 1 and 2 -> bind-pose row
        assertEquals(0, export.skin.boneIds[0]);
        assertEquals(1f, export.skin.weights[0], 1e-6f);
        assertEquals(1, export.skin.boneIds[3]);
        assertEquals(1f, export.skin.weights[3], 1e-6f);
        assertEquals(9, export.skin.weights.length);

        assertEquals(1, export.skinClips.size());
        MeshSkinClip clip = export.skinClips.get(0);
        assertEquals("turn", clip.name);
        // 90 degree rotation: m00 = cos(90) ~ 0, m10 = sin(90) = 1
        assertEquals(0f, clip.value(0, 0, 0), 1e-5f);
        assertNotEquals(0f, clip.value(0, 0, 3), "m10 must rotate the bone");
        // no frameTimes in v3 -> uniform fps grid, not looping by default
        assertEquals(1, clip.frameCount);
        assertEquals(0f, clip.frameTimes[0], 1e-6f);
        assertTrue(clip.fps > 0f);
    }

    /** Mirrors {@code SkinExportParser.BIND_POSE_BONE} (package-private constant). */
    private static final String BIND_POSE_NAME = "<bind-pose>";

    @Test
    void exportsWithoutBonesHaveNoSkin() {
        String json = """
            {"name": "plain", "vertices": [{"x":0,"y":0},{"x":1,"y":0},{"x":0,"y":1}],
             "colors": [{"name":"c","red":255,"green":0,"blue":0,"alpha":1}],
             "objects": [{"name":"o","triangles":[{"vertices":[0,1,2],"colors":[0,0,0]}]}]}
            """;
        MeshExport export = MeshExportLoader.loadFromString(json);
        assertNull(export.skin);
        assertTrue(export.skinClips.isEmpty());
    }
}
