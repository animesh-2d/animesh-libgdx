package com.jb.rts.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.io.File;
import java.util.function.Consumer;

/**
 * <b>Locates the files the mesh player plays</b> — the {@code meshes} directory it scans, the
 * single export of standalone mode, and the {@link FileHandle} for an export or its texture.
 *
 * <p>Extracted from {@code MeshPlayer} so the resolution rules can be tested without a window
 * ({@code MeshPlayerFileResolutionTest}) and so the screen only contains rendering/UI logic.</p>
 *
 * <p>Resolution rules:</p>
 * <ul>
 *   <li>{@link #resolveMeshesDir()} first honours {@code -DmeshPlayer.assetsDir=<path>}, then walks
 *       up to {@value #MAX_PARENT_LEVELS} parent directories of {@code user.dir} looking for
 *       {@code meshes} (at the working directory itself) or {@code assets/meshes}. Every candidate
 *       is logged with its absolute path, so a missing folder is easy to diagnose.</li>
 *   <li>{@link #resolveStandaloneFile(String)} accepts an absolute path as-is and resolves a
 *       relative one against {@code user.dir} and its parents — that makes repo-relative paths
 *       work when the player is started from a module directory (e.g. {@code mesheditor}).</li>
 *   <li>{@link #handleFor(String)} prefers a file that exists on disk and falls back to the
 *       class path, where the packaged examples live inside the player jar.</li>
 * </ul>
 */
public final class MeshFileResolver {

    /** How many parent directories above {@code user.dir} are searched for relative paths. */
    public static final int MAX_PARENT_LEVELS = 4;

    private MeshFileResolver() {}

    /**
     * Locates the assets {@code meshes} directory, printing the <b>complete paths</b> it tries so
     * it is easy to debug why a file was (or was not) found.
     *
     * @return the resolved directory, or {@code null} if none of the candidates exists
     */
    public static File resolveMeshesDir() {
        File cwd = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        log("user.dir = " + cwd.getAbsolutePath());

        String override = System.getProperty("meshPlayer.assetsDir");
        if (override != null && !override.isBlank()) {
            File d = new File(override).getAbsoluteFile();
            log("meshPlayer.assetsDir override -> " + d.getAbsolutePath());
            if (d.isDirectory()) return d;
        }

        File dir = cwd;
        for (int level = 0; level <= MAX_PARENT_LEVELS; level++) {
            // At the working directory itself also accept a bare "meshes" folder (the
            // desktop gradle tasks run from the repo root, where "assets/meshes" lives).
            String[] rels = level == 0
                    ? new String[]{"meshes", "assets/meshes"}
                    : new String[]{"assets/meshes"};
            for (String rel : rels) {
                File d = new File(dir, rel).getAbsoluteFile();
                log("  checking: " + d.getAbsolutePath()
                        + (d.isDirectory() ? "  [exists]" : "  [missing]"));
                if (d.isDirectory()) return d;
            }
            File parent = dir.getParentFile();
            if (parent == null) break;
            dir = parent;
        }
        return null;
    }

    /**
     * Resolves the single export file of standalone mode and logs every candidate it tries.
     * Absolute paths are used as given; relative paths are resolved against the working directory
     * first and then against up to {@link #MAX_PARENT_LEVELS} parent directories.
     *
     * @return the existing file, or {@code null} if no candidate exists
     */
    public static File resolveStandaloneFile(String path) {
        File cwd = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        if (new File(path).isAbsolute()) {
            log("Standalone path is absolute (user.dir = " + cwd.getAbsolutePath() + ")");
        } else {
            log("user.dir = " + cwd.getAbsolutePath()
                    + " (relative path; also searching up to " + MAX_PARENT_LEVELS
                    + " parent directories)");
        }
        return findExistingFile(cwd, path, MAX_PARENT_LEVELS,
                f -> log("  checking: " + f.getAbsolutePath()
                        + (f.isFile() ? "  [exists]" : "  [missing]")));
    }

    /**
     * Resolves an export path to a readable {@link FileHandle}.
     *
     * <p>A file that exists on disk — an absolute path, or a path relative to the working
     * directory (the repository checkout, where {@code player/assets/meshes} lives) — is used as
     * it is. Otherwise the path is resolved against the <b>class path</b>, which is where the
     * packaged example lives inside the player jar: libGDX's internal handles fall back to
     * {@code getResourceAsStream}, so an installed app-image can play its own assets.</p>
     *
     * @param path path of the export (or of one of its auxiliary files, relative to the export)
     * @return a handle whose {@code read()} / {@code exists()} work in both situations
     */
    public static FileHandle handleFor(String path) {
        FileHandle onDisk = Gdx.files.absolute(new File(path).getAbsolutePath());
        if (onDisk.exists() || new File(path).isAbsolute()) return onDisk;
        return Gdx.files.internal(path);
    }

    /**
     * Testable core of {@link #resolveStandaloneFile(String)}: returns the first existing file
     * for {@code path}. Absolute paths are checked once; relative paths are checked against
     * {@code startDir} and then each of its up to {@code maxParents} parent directories.
     *
     * @param startDir    directory to resolve relative paths against (usually {@code user.dir})
     * @param path        the (possibly relative) file path
     * @param maxParents  how many parent directories to try for relative paths
     * @param onCandidate optional observer called for every candidate that is tested
     * @return the existing file with an absolute path, or {@code null}
     */
    public static File findExistingFile(File startDir, String path, int maxParents,
                                        Consumer<File> onCandidate) {
        File given = new File(path);
        if (given.isAbsolute()) {
            File candidate = given.getAbsoluteFile();
            if (onCandidate != null) onCandidate.accept(candidate);
            return candidate.isFile() ? candidate : null;
        }
        File dir = startDir.getAbsoluteFile();
        for (int level = 0; level <= maxParents; level++) {
            File candidate = new File(dir, path).getAbsoluteFile();
            if (onCandidate != null) onCandidate.accept(candidate);
            if (candidate.isFile()) return candidate;
            File parent = dir.getParentFile();
            if (parent == null) break;
            dir = parent;
        }
        return null;
    }

    /**
     * The file name of a path (native or repo paths).
     *
     * @param path the path to shorten ({@code null} stays {@code null})
     * @return everything after the last {@code /} or {@code \}
     */
    public static String shortName(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Logs through {@code Gdx.app} when it is available (the resolver also runs in tests). */
    private static void log(String message) {
        if (Gdx.app != null) Gdx.app.log("MeshPlayer", message);
    }
}
