package com.jb.rts.player.desktop;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowListener;
import com.jb.rts.MeshPlayer;

/**
 * Desktop launcher of the {@link MeshPlayer} (the entry point of the player app-image).
 *
 * <p><b>No arguments</b> — plays the first of the examples bundled in the jar (see
 * {@code MeshPlayer.BUNDLED_EXAMPLES}); when a {@code meshes} directory exists next to the working
 * directory (the repository checkout) its exports are scanned and the first one is played instead.
 * The drop-down at the left of the navigation bar switches between all known exports — the bundled
 * examples, the scanned files and everything opened so far. The on-screen <b>Load…</b> button
 * opens any other export, dropping a {@code .json}/{@code .vat} file onto the window plays it too,
 * and ◀ Prev / Next ▶ (or the arrow keys) step through the animations. Run it with
 * {@code ./gradlew :player:run}.
 *
 * <p><b>With arguments</b> (standalone / single-file mode) — displays one specific mesh export:
 * <pre>
 *   MeshPlayerLauncher &lt;file-path&gt; [copies] [screen-diagonal-mm]
 * </pre>
 * <dl>
 *   <dt>file-path</dt>
 *   <dd>absolute path to the .json mesh export</dd>
 *   <dt>copies</dt>
 *   <dd>number of GPU-instanced copies (default 100)</dd>
 *   <dt>screen-diagonal-mm</dt>
 *   <dd>screen diagonal in millimetres, used to size the viewport (default 500)</dd>
 * </dl>
 * Instances are placed at random positions and sorted by y-coordinate for z-ordering.
 *
 * <p>Both modes always start <b>fullscreen at the monitor's native screen resolution</b>
 * (the largest display mode the monitor reports, at the current desktop refresh rate). This keeps
 * the preview pixel-exact on any display — Full HD, QHD, ultrawide, 4K, … — instead of being
 * letterboxed into a fixed 1920×1080 mode.
 */
public class MeshPlayerLauncher {
    public static void main(String[] args) {
        if (args.length >= 1) {
            standaloneMode(args);
        } else {
            directoryScanMode();
        }
    }

    /** Default mode: bundled example (or the scanned meshes directory), Prev/Next/Load UI. */
    private static void directoryScanMode() {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setForegroundFPS(60);
        config.setTitle("Mesh Player");
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        configureFullscreenNative(config);
        MeshPlayer player = new MeshPlayer();
        config.setWindowListener(droppedFiles(player));
        new Lwjgl3Application(player, config);
    }

    /**
     * Plays a {@code .json} / {@code .vat} file that was dropped onto the window. GLFW delivers the
     * paths on the render thread, so the player can be called directly.
     *
     * @param player the running player
     * @return the listener that hands the first dropped file to {@link MeshPlayer#openExport(String)}
     */
    private static Lwjgl3WindowListener droppedFiles(MeshPlayer player) {
        return new Lwjgl3WindowAdapter() {
            @Override
            public void filesDropped(String[] files) {
                if (files != null && files.length > 0) player.openExport(files[0]);
            }
        };
    }

    /**
     * Starts the player fullscreen at the monitor's <b>native screen resolution</b>.
     *
     * <p>The mode with the largest pixel area reported by the primary monitor is the native
     * resolution of the panel. For that resolution the current desktop refresh rate is preferred
     * (so only the resolution is pinned, not the refresh rate); if that refresh rate is not
     * available the fastest mode of that size is used. If GLFW cannot report any mode, the current
     * desktop mode is used as a fallback so the app still starts — but always in fullscreen,
     * never windowed.
     */
    private static void configureFullscreenNative(Lwjgl3ApplicationConfiguration config) {
        Graphics.DisplayMode[] modes = null;
        Graphics.DisplayMode desktop = null;
        try {
            modes = Lwjgl3ApplicationConfiguration.getDisplayModes();
        } catch (RuntimeException ignored) {
            // GLFW not available (e.g. headless) – handled by the fallbacks below.
        }
        try {
            desktop = Lwjgl3ApplicationConfiguration.getDisplayMode();
        } catch (RuntimeException ignored) {
            // No desktop mode available – handled below.
        }

        if (modes == null || modes.length == 0) {
            if (desktop != null) {
                config.setFullscreenMode(desktop);
            }
            return;
        }

        // 1. Native resolution = the largest pixel area the monitor reports.
        int nativeW = 0;
        int nativeH = 0;
        for (Graphics.DisplayMode mode : modes) {
            if (mode != null && (long) mode.width * mode.height > (long) nativeW * nativeH) {
                nativeW = mode.width;
                nativeH = mode.height;
            }
        }

        // 2. Pick a mode of that size, preferring the desktop refresh rate.
        Graphics.DisplayMode sameRefresh = null;
        Graphics.DisplayMode fastest = null;
        for (Graphics.DisplayMode mode : modes) {
            if (mode == null || mode.width != nativeW || mode.height != nativeH) {
                continue;
            }
            if (fastest == null || mode.refreshRate > fastest.refreshRate) {
                fastest = mode;
            }
            if (desktop != null && mode.refreshRate == desktop.refreshRate) {
                sameRefresh = mode;
            }
        }

        Graphics.DisplayMode chosen = sameRefresh != null ? sameRefresh
                : (fastest != null ? fastest : desktop);
        if (chosen != null) {
            config.setFullscreenMode(chosen);
        }
    }

    /** Standalone mode: loads one file, random positions, y-sorted z-order. */
    private static void standaloneMode(String[] args) {
        String filePath = args[0];
        int copies = 100;
        float screenDiagonalMm = 500f;
        if (args.length >= 2) {
            try { copies = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        if (args.length >= 3) {
            try { screenDiagonalMm = Float.parseFloat(args[2]); } catch (NumberFormatException ignored) {}
        }

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setForegroundFPS(60);
        config.setTitle("Mesh Player — " + new java.io.File(filePath).getName());
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        configureFullscreenNative(config);
        MeshPlayer player = new MeshPlayer(filePath, copies, screenDiagonalMm);
        config.setWindowListener(droppedFiles(player));
        new Lwjgl3Application(player, config);
    }
}
