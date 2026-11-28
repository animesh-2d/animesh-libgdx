package com.jb.rts.player.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
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
 * <p>Both modes open the same <b>windowed HD preview</b> (see {@link HdWindow}): a centred,
 * resizable 1280×720 window that GLFW keeps at the 16:9 ratio — widening the window raises its
 * height and lowering it narrows the window, so the preview is never letterboxed (the world camera
 * fits whatever size the window has). The player is deliberately <b>not fullscreen</b>, so the
 * desktop stays reachable while an export is played.
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
        HdWindow.configure(config);
        MeshPlayer player = new MeshPlayer();
        config.setWindowListener(playerWindow(player));
        new Lwjgl3Application(player, config);
    }

    /**
     * Window listener of the running player: pins the window to 16:9 as soon as the native window
     * exists (see {@link HdWindow#lockAspect(Lwjgl3Window)}) and plays a {@code .json} /
     * {@code .vat} file that was dropped onto the window.
     *
     * <p>GLFW delivers the dropped paths on the render thread, so the player can be called
     * directly.</p>
     *
     * @param player the running player
     * @return the listener that locks the aspect ratio and hands the first dropped file to
     *         {@link MeshPlayer#openExport(String)}
     */
    private static Lwjgl3WindowListener playerWindow(MeshPlayer player) {
        return new Lwjgl3WindowAdapter() {
            @Override
            public void created(Lwjgl3Window window) {
                HdWindow.lockAspect(window);
            }

            @Override
            public void filesDropped(String[] files) {
                if (files != null && files.length > 0) player.openExport(files[0]);
            }
        };
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
        HdWindow.configure(config);
        MeshPlayer player = new MeshPlayer(filePath, copies, screenDiagonalMm);
        config.setWindowListener(playerWindow(player));
        new Lwjgl3Application(player, config);
    }
}
