package com.jb.rts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MeshPlayer#findExistingFile(java.io.File, String, int, java.util.function.Consumer)},
 * the path resolution used in standalone mode. A repo-relative path such as
 * {@code assets/meshes/dino_longneck-export.json} must be found even when the player was started
 * from a module directory (e.g. {@code mesheditor}); at the same time a truly missing file must
 * stay missing so the failure is reported instead of silently loading another file.
 */
class MeshPlayerFileResolutionTest {

    @Test
    void relativePathIsFoundInTheWorkingDirectory(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("dino-export.json"));

        java.io.File resolved = MeshPlayer.findExistingFile(
                tmp.toFile(), "dino-export.json", 4, null);

        assertEquals(file.toFile().getAbsoluteFile(), resolved);
    }

    @Test
    void repoRelativePathIsFoundInAParentDirectory(@TempDir Path tmp) throws IOException {
        // Repo layout: <tmp>/assets/meshes/<file>, player started in <tmp>/mesheditor.
        Path meshes = Files.createDirectories(tmp.resolve("assets").resolve("meshes"));
        Path file = Files.createFile(meshes.resolve("dino_longneck-export.json"));
        Path moduleDir = Files.createDirectories(tmp.resolve("mesheditor"));

        List<java.io.File> tried = new ArrayList<>();
        java.io.File resolved = MeshPlayer.findExistingFile(
                moduleDir.toFile(), "assets/meshes/dino_longneck-export.json", 4, tried::add);

        assertEquals(file.toFile().getAbsoluteFile(), resolved);
        // The first candidate (inside the module) must be missing, the second (repo root) hit.
        assertEquals(2, tried.size(), "both candidates should be probed: " + tried);
        assertEquals(moduleDir.resolve("assets").resolve("meshes")
                        .resolve("dino_longneck-export.json").toFile().getAbsoluteFile(),
                tried.get(0));
        assertEquals(resolved, tried.get(1));
    }

    @Test
    void absolutePathIsCheckedOnceAndReturnedAsGiven(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("absolute-export.json"));
        List<java.io.File> tried = new ArrayList<>();

        java.io.File resolved = MeshPlayer.findExistingFile(
                tmp.resolve("elsewhere").toFile(), file.toAbsolutePath().toString(), 4, tried::add);

        assertEquals(file.toFile().getAbsoluteFile(), resolved);
        assertEquals(1, tried.size());
    }

    @Test
    void missingRelativePathReturnsNullAfterAllCandidates(@TempDir Path tmp) throws IOException {
        Path moduleDir = Files.createDirectories(tmp.resolve("mesheditor"));
        List<java.io.File> tried = new ArrayList<>();

        java.io.File resolved = MeshPlayer.findExistingFile(
                moduleDir.toFile(), "assets/meshes/nope.json", 2, tried::add);

        assertNull(resolved);
        assertEquals(3, tried.size(), "cwd + two parents: " + tried);
        assertTrue(tried.stream().noneMatch(java.io.File::isFile));
    }

    @Test
    void parentSearchCanBeDisabled(@TempDir Path tmp) throws IOException {
        Path meshes = Files.createDirectories(tmp.resolve("assets").resolve("meshes"));
        Files.createFile(meshes.resolve("dino_longneck-export.json"));
        Path moduleDir = Files.createDirectories(tmp.resolve("mesheditor"));

        java.io.File resolved = MeshPlayer.findExistingFile(
                moduleDir.toFile(), "assets/meshes/dino_longneck-export.json", 0, null);

        assertNull(resolved, "maxParents = 0 must not search above the working directory");
    }
}
