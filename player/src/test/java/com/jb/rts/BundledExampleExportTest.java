package com.jb.rts;

import com.badlogic.gdx.files.FileHandle;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.vat.VatPoseBaker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the example export the player ships inside its jar
 * ({@code player/assets/meshes/fire-export.json}).
 *
 * <p>If this file stops loading — or stops baking into VAT data — the player opens with a load
 * error instead of a mesh. The player now keeps its navigation bar usable in that case (so another
 * export can be opened, see {@code MeshPlayer#onRender}), but the bundled example itself must never
 * regress: it is the first thing a user sees.
 *
 * <p>The test mirrors the CPU half of {@code MeshPlayer.buildAnims}: group the poses by animation
 * name and bake each group through {@link VatPoseBaker}. No GL context is needed.
 */
class BundledExampleExportTest {

    /** Path relative to the {@code player} module, the working directory of its tests. */
    private static final Path EXAMPLE = Path.of("assets", "meshes", "fire-export.json");

    @Test
    void exampleExportLoadsAndBakesEveryAnimation() {
        assertTrue(Files.isRegularFile(EXAMPLE), "bundled example is missing: " + EXAMPLE);

        MeshExport export = MeshExportLoader.load(new FileHandle(EXAMPLE.toFile()));

        assertFalse(export.meshes.isEmpty(), "example export contains no poses");
        assertTrue(export.hasTexture(), "example export should still reference its atlas");
        assertTrue(export.hasUvs(), "a textured export must carry uvVertices");
        assertNotNull(export.textureFile);
        assertTrue(Files.isRegularFile(EXAMPLE.getParent().resolve(export.textureFile)),
                "texture '" + export.textureFile + "' must sit next to the example JSON");

        if (export.skin != null) {
            // Bone-driven exports are baked through VatSkinBaker by the player (needs no GL
            // either, but its clip/bind-pose contract is covered by the library tests). Loading a
            // skinned export is enough here: the player's own path is exercised on startup.
            assertFalse(export.skinClips.isEmpty(),
                    "a skinned export should carry baked clips");
            return;
        }

        Map<String, List<BlendableMesh>> byName = new LinkedHashMap<>();
        for (BlendableMesh mesh : export.meshes) {
            String animation = mesh.poseName();
            if (animation == null || animation.isEmpty()) animation = "rest";
            byName.computeIfAbsent(animation, key -> new ArrayList<>()).add(mesh);
        }
        assertTrue(byName.size() >= 2, "example should still have a rest pose and an animation");

        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            List<BlendableMesh> frames = entry.getValue();
            VatPoseBaker.Result baked = VatPoseBaker.bake(
                    export.name + "_" + entry.getKey(), frames, export.palette, 30f,
                    export.stepVertices, export.animationStepFrames.get(entry.getKey()),
                    export.uvs);

            assertTrue(baked.mesh().vertexCount() > 0, "empty mesh for '" + entry.getKey() + "'");
            assertEquals(frames.size(), baked.animation().frameCount(),
                    "baked frame count of '" + entry.getKey() + "'");
        }
    }
}
