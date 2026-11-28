package com.jb.rts.player.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import org.lwjgl.glfw.GLFW;

/**
 * Window configuration of the {@link com.jb.rts.MeshPlayer}: the player does <b>not</b> start
 * fullscreen, it opens a centred, resizable <b>HD window (1280×720)</b> whose 16:9 aspect ratio
 * cannot be broken by resizing.
 *
 * <p>Both settings are GLFW window hints, but they need the window at different times, hence the
 * two entry points:</p>
 * <ol>
 *   <li>{@link #configure(Lwjgl3ApplicationConfiguration)} — before the app starts: windowed
 *       1280×720, resizable, and a minimum size of {@value #MIN_WIDTH}×{@value #MIN_HEIGHT} so
 *       the parameter panel, the state graph and the nav bar stay legible. libGDX centres a
 *       windowed mode on the primary monitor itself.</li>
 *   <li>{@link #lockAspect(Lwjgl3Window)} — from
 *       {@code Lwjgl3WindowListener.created(...)}, the first moment the native window handle
 *       exists: pins the <em>content area</em> to 1280:720. From then on every interactive resize
 *       scales <b>both</b> edges — widening the window raises its height, lowering it narrows the
 *       window — so the preview is never letterboxed.</li>
 * </ol>
 *
 * <p>Two platform limits worth knowing (window-manager behaviour, not something the player
 * controls):</p>
 * <ul>
 *   <li>The ratio applies to <em>interactive</em> resizes (dragging an edge/corner, or the system
 *       resize grip). Maximising the window is a window-manager action and is left to it; the
 *       world camera adapts to whatever aspect the window ends up with
 *       ({@code MeshPlayer.updateCamera}), so nothing is distorted.</li>
 *   <li>Wayland compositors have no protocol for an aspect-ratio hint, so a Wayland session stays
 *       freely resizable; Windows, macOS and X11 enforce it.</li>
 * </ul>
 */
final class HdWindow {

    /** Content width of the player window at startup — 1280×720 is HD (16:9). */
    static final int WIDTH = 1280;

    /** Content height of the player window at startup, see {@link #WIDTH}. */
    static final int HEIGHT = 720;

    /** Smallest content width a user resize may produce (16:9, see {@link #MIN_HEIGHT}). */
    static final int MIN_WIDTH = 640;

    /** Smallest content height a user resize may produce. */
    static final int MIN_HEIGHT = 360;

    private HdWindow() {
        // Static configuration only.
    }

    /**
     * Opens the player in a resizable HD window instead of taking over the whole monitor.
     *
     * @param config the launcher's configuration, not yet applied
     */
    static void configure(Lwjgl3ApplicationConfiguration config) {
        config.setWindowedMode(WIDTH, HEIGHT);
        config.setResizable(true);
        // Maximum stays unrestricted (-1 → GLFW_DONT_CARE): only the ratio limits the width.
        config.setWindowSizeLimits(MIN_WIDTH, MIN_HEIGHT, -1, -1);
    }

    /**
     * Pins the content area of {@code window} to its 16:9 starting ratio.
     *
     * <p>Must run once the native window exists — GLFW applies the ratio to the window it is given
     * and would immediately squash a not-yet-created one. Called from the window listener's
     * {@code created} callback, i.e. on the render thread, as GLFW requires.</p>
     *
     * @param window the freshly created window
     */
    static void lockAspect(Lwjgl3Window window) {
        long handle = window.getWindowHandle();
        if (handle == 0L) return; // destroyed/unavailable — nothing to constrain
        GLFW.glfwSetWindowAspectRatio(handle, WIDTH, HEIGHT);
    }
}
