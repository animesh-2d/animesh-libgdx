package com.jb.rts.player;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration.GLEmulation;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.mesh.vat.VatPoseBaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end guard for the player's <b>colour table</b>: editing a palette colour must recolour
 * the mesh that is <em>on screen</em>, not just the palette object.
 *
 * <p>This is the one test of the project that needs a real OpenGL context, because the failure it
 * guards is a <b>missing GPU upload</b>: a libGDX {@code Mesh} keeps its vertex data in a CPU
 * buffer and only streams it to the VBO on {@link com.badlogic.gdx.graphics.Mesh#bind}, so a
 * palette refresh that forgets that flush keeps the old colours on screen forever — and no test
 * without a context can see that.</p>
 *
 * <p>The test draws one instance of a single-triangle mesh that covers the whole viewport, reads
 * the centre texel, edits palette entry 0 (red → blue), redraws and reads the texel again. All
 * corners use palette entry 0 and the clip has a single frame, so the mesh carries no per-frame
 * colour strip: this exercises exactly the static {@code a_color} attribute that a recolour has to
 * re-upload.</p>
 *
 * <p><b>Opt-in</b>, because it opens a (hidden) window:
 * {@code ./gradlew :player:test -Panimesh.gl=true}. Plain {@code ./gradlew build} skips it, so a
 * headless CI never needs a display. GLFW additionally requires the OS main thread on macOS, which
 * a JUnit worker thread is not — run it on Windows/Linux.</p>
 */
@EnabledIfSystemProperty(named = "animesh.gl", matches = "true")
class VatPaletteGlTest {

    /** Viewport, in pixels: the triangle covers it completely, so any texel works. */
    private static final int VIEWPORT = 64;

    /** The projection the draw uses — identity, because the mesh is authored in NDC. */
    private static final Matrix4 IDENTITY = new Matrix4();

    /** What the GL thread measured, so the assertions can run on the test thread. */
    private static final class Result {
        int beforeEdit;
        int afterEdit;
        int refreshError;
        Throwable failure;
    }

    @Test
    void editingAPaletteColourRecoloursTheDrawnMesh() {
        Result result = new Result();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("VAT palette refresh test");
        config.setWindowedMode(VIEWPORT, VIEWPORT);
        config.setInitialVisible(false);   // offscreen: no window flashes up during a build
        config.setOpenGLEmulation(GLEmulation.GL30, 3, 3);
        config.useVsync(false);
        config.setForegroundFPS(0);

        // Blocks until the adapter calls Gdx.app.exit().
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    measure(result);
                } catch (Throwable t) {
                    result.failure = t;
                } finally {
                    Gdx.app.exit();
                }
            }
        }, config);

        if (result.failure != null) throw new AssertionError("GL run failed", result.failure);
        assertEquals(GL20.GL_NO_ERROR, result.refreshError,
            "refreshPalette() must not leave a GL error behind (0x"
                + Integer.toHexString(result.refreshError) + ")");
        assertRgb(255, 0, 0, result.beforeEdit,
            "the mesh must start with palette colour 0 (red)");
        assertRgb(0, 0, 255, result.afterEdit,
            "editing palette colour 0 must recolour the drawn mesh (blue)");
    }

    /** Draws, edits the palette, draws again — all on the GL thread. */
    private static void measure(Result result) {
        ColorPalette palette = new ColorPalette();
        palette.addColor("red", 1f, 0f, 0f, 1f);
        palette.addColor("blue", 0f, 0f, 1f, 1f);

        // One triangle that covers the whole NDC square; every corner uses palette entry 0.
        float[] positions = {-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f};
        BlendableMesh pose = new BlendableMesh("demo_pose_0", positions,
            new float[positions.length], new int[]{0, 0, 0}, new short[]{0, 1, 2},
            "demo", "pose", 0);

        VatPoseBaker.Result baked = VatPoseBaker.bake("demo_pose", List.of(pose), palette);
        VatInstancedMesh batch = new VatInstancedMesh(baked.mesh(), baked.animation(), 1, null);
        try {
            result.beforeEdit = drawCentreTexel(batch);

            // What the player's colour table does on every tick of the colour picker.
            while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { /* drop earlier errors */ }
            palette.setColor(0, 0f, 0f, 1f, 1f);
            batch.refreshPalette(palette);
            result.refreshError = Gdx.gl.glGetError();

            result.afterEdit = drawCentreTexel(batch);
        } finally {
            batch.dispose();
        }
    }

    /** Renders the batch's single instance and reads back the centre texel as RGBA8888. */
    private static int drawCentreTexel(VatInstancedMesh batch) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, width, height);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        batch.begin();
        batch.add(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f);
        batch.render(IDENTITY);

        ByteBuffer texel = BufferUtils.newByteBuffer(4);
        Gdx.gl.glReadPixels(width / 2, height / 2, 1, 1,
            GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, texel);
        return rgba(texel.get(0) & 0xFF, texel.get(1) & 0xFF,
            texel.get(2) & 0xFF, texel.get(3) & 0xFF);
    }

    /** Packs a read-back texel into one comparable int (RGBA8888 order). */
    private static int rgba(int r, int g, int b, int a) {
        return (r << 24) | (g << 16) | (b << 8) | a;
    }

    /**
     * Asserts the RGB channels of a read-back texel.
     *
     * <p>The alpha channel is ignored on purpose: the window's framebuffer alpha is not part of
     * this feature, and GL dithering (enabled by default) may shift any channel by one step.</p>
     */
    private static void assertRgb(int r, int g, int b, int texel, String message) {
        assertEquals(r, (texel >> 24) & 0xFF, 1, message + " (red)");
        assertEquals(g, (texel >> 16) & 0xFF, 1, message + " (green)");
        assertEquals(b, (texel >> 8) & 0xFF, 1, message + " (blue)");
    }
}
