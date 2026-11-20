package com.jb.rts;

import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkinClip;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatPoseBaker;
import com.jb.rts.mesh.vat.VatSkinBaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the examples a user of the <b>downloaded player</b> can watch
 * ({@link MeshPlayer#BUNDLED_EXAMPLES}).
 *
 * <p>Three things can silently break that feature, and each has its own test here:
 * <ol>
 *   <li>a file is added to / removed from {@code test-assets/meshes} without updating
 *       {@link MeshPlayer#BUNDLED_EXAMPLES} (or vice versa),</li>
 *   <li>the Gradle wiring stops putting the files on the runtime class path — the app-image would
 *       then open with a load error instead of a mesh,</li>
 *   <li>an export stops loading or bakes into no animation at all.</li>
 * </ol>
 *
 * <p>The tests read the exports through the class path — exactly what the player does inside its
 * jar — and need no GL context. {@code fire-export.json} is provided by {@code player/assets}, the
 * others by the {@code examples} source set of {@code player/build.gradle}.
 */
class BundledExamplesTest {

    /** The folder {@code player/build.gradle} bundles from, relative to the module directory. */
    private static final Path SHARED_EXAMPLE_DIR = Path.of("..", "test-assets", "meshes");

    /** Every {@link MeshPlayer#BUNDLED_EXAMPLES} entry is a resource below this directory. */
    private static final String RESOURCE_DIR = "meshes";

    @Test
    void theBundledListIsExactlyTheSharedExampleFolder() throws IOException {
        assumeTrue(Files.isDirectory(SHARED_EXAMPLE_DIR),
                "shared example folder not available: " + SHARED_EXAMPLE_DIR.toAbsolutePath());

        List<String> inRepository = new ArrayList<>();
        try (Stream<Path> files = Files.list(SHARED_EXAMPLE_DIR)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(inRepository::add);
        }

        List<String> bundled = new ArrayList<>();
        for (String path : MeshPlayer.BUNDLED_EXAMPLES) {
            assertTrue(path.startsWith(RESOURCE_DIR + "/"),
                    "bundled example '" + path + "' must be a resource below " + RESOURCE_DIR + "/");
            bundled.add(path.substring(RESOURCE_DIR.length() + 1));
        }
        bundled.sort(String::compareTo);

        assertEquals(inRepository, bundled, "MeshPlayer.BUNDLED_EXAMPLES must list exactly the "
                + "exports of " + SHARED_EXAMPLE_DIR.toAbsolutePath() + " (the folder "
                + "player/build.gradle bundles into the jar)");
    }

    @Test
    void everyBundledExampleIsOnTheRuntimeClassPath() {
        for (String path : MeshPlayer.BUNDLED_EXAMPLES) {
            assertNotNull(MeshPlayer.class.getResource("/" + path),
                    "'" + path + "' is not on the runtime class path — the deployed player would "
                            + "report a load error instead of showing the example");
        }
    }

    @Test
    void everyBundledExampleLoadsAndBakesAnAnimation() throws IOException {
        for (String path : MeshPlayer.BUNDLED_EXAMPLES) {
            MeshExport export = MeshExportLoader.loadFromString(readResource(path));
            String what = "'" + path + "'";

            // A texture the export names must be bundled next to it (the exporter writes it there).
            if (export.hasTexture()) {
                assertNotNull(MeshPlayer.class.getResource("/" + RESOURCE_DIR + "/" + export.textureFile),
                        what + " names texture '" + export.textureFile + "', which is not bundled");
            }

            if (export.skin != null && !export.skinClips.isEmpty()) {
                // Skinned export: MeshPlayer bakes every clip of it through VatSkinBaker.
                for (MeshSkinClip clip : export.skinClips) {
                    VatAnimationData animation = VatSkinBaker.animation(
                            export.name + "_" + clip.name, export.skin, clip);
                    assertTrue(animation.frameCount() > 0,
                            what + " baked clip '" + clip.name + "' has no frame");
                }
                continue;
            }

            // Pose export: MeshPlayer bakes one VAT group per animation name (the same check as
            // BundledExampleExportTest, which guards the fire example alone).
            Map<String, List<BlendableMesh>> byName = groupByPose(export);
            assertFalse(byName.isEmpty(), what + " carries no geometry to play");
            for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
                List<BlendableMesh> frames = entry.getValue();
                VatPoseBaker.Result baked = VatPoseBaker.bake(
                        export.name + "_" + entry.getKey(), frames, export.palette, 30f,
                        export.stepVertices, export.animationStepFrames.get(entry.getKey()),
                        export.uvs);

                assertTrue(baked.mesh().vertexCount() > 0,
                        what + " baked an empty mesh for '" + entry.getKey() + "'");
                assertEquals(frames.size(), baked.animation().frameCount(),
                        what + " lost frames of '" + entry.getKey() + "'");
            }
        }
    }

    /** Groups the poses of an export by animation name — the player's own grouping. */
    private static Map<String, List<BlendableMesh>> groupByPose(MeshExport export) {
        Map<String, List<BlendableMesh>> byName = new LinkedHashMap<>();
        for (BlendableMesh mesh : export.meshes) {
            String animation = mesh.poseName();
            if (animation == null || animation.isEmpty()) animation = "rest";
            byName.computeIfAbsent(animation, key -> new ArrayList<>()).add(mesh);
        }
        return byName;
    }

    /** Reads a bundled export the way the player does: from the class path (inside the jar). */
    private static String readResource(String path) throws IOException {
        try (InputStream in = MeshPlayer.class.getResourceAsStream("/" + path)) {
            assertNotNull(in, "resource '" + path + "' is missing from the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
