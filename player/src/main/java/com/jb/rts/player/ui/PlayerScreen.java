package com.jb.rts.player.ui;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * The <b>screen shell</b> of a player screen (the mesh player, the VAT demo, …): it owns the
 * Scene2D {@link Stage} and the {@link Skin}, wires the input chain and drives
 * one frame in the order every libGDX demo needs.
 *
 * <p>A subclass only implements the <b>world</b> — the UI stack is handled here, exactly the way
 * {@code ExplosionPlayer} always did it:</p>
 *
 * <ol>
 *   <li>{@link #create()} builds the skin ({@link UiSkin#load()}, font scaled to
 *       {@link UiLayout#UI_FONT_SCALE}) and a {@link Stage} with a {@link ScreenViewport} — so the
 *       on-screen widget size is the same at every window size and fullscreen resolution.</li>
 *   <li>{@link #onCreate()} builds the demo: its panel/camera/resources, and adds
 *       {@code panel.getRoot()} (a {@link ControlPanel}) or a {@link NavBar} to {@link #stage}.</li>
 *   <li>The input chain is assembled from {@link #beforeStage()}, the stage and
 *       {@link #afterStage()} — the fixed order that makes the UI win over the world: an input
 *       processor returning {@code true} <b>before</b> the stage can override a widget (wheel-zoom
 *       routing), and one placed <b>after</b> it only ever sees what the UI did not consume (world
 *       dragging, keyboard shortcuts).</li>
 *   <li>{@link #render()} draws the world via {@link #onRender(float)} and then the UI, so the panel
 *       and the HUD sit on top of everything the world drew. Work that has to happen after the UI
 *       (statistics, screenshots, auto-exit) goes into {@link #onAfterUi(float)}.</li>
 *   <li>{@link #dispose()} frees the world ({@link #onDispose()}) and then the stage and the skin,
 *       which own the native resources ({@code Disposable}).</li>
 * </ol>
 *
 * <p>The frame delta is clamped to {@link #MAX_DELTA}: a stalled frame (window drag, GC pause,
 * screenshot) must not teleport the simulation by a second.</p>
 *
 * <p>Subclasses may use {@link #stage}, {@link #skin} and {@link #uiFont()} freely; they must
 * <b>not</b> override the lifecycle methods (they are {@code final}) and must not dispose the stage
 * or the skin themselves.</p>
 */
public abstract class PlayerScreen extends ApplicationAdapter {

    /**
     * Upper bound for one frame's delta in seconds. Longer frames are treated as this long, so a
     * hitch cannot make an explosion jump or a wave shoot through the sea in a single step.
     */
    public static final float MAX_DELTA = 0.1f;

    /** The shared toolkit skin; owns the fonts and the white texture. Created in {@link #create()}. */
    protected Skin skin;

    /**
     * The Scene2D stage on top of the world. It always uses a {@link ScreenViewport}, so widget
     * sizes are pixels at 1:1 and do not change with the window size.
     */
    protected Stage stage;

    /** The skin's {@code default-font} at {@link UiLayout#UI_FONT_SCALE}, for HUD text. */
    private BitmapFont uiFont;

    /** Guards against a second {@link #dispose()} (some backends call it more than once). */
    private boolean disposed;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public final void create() {
        skin = UiSkin.load();
        // A slightly larger font keeps labels and HUD text readable in fullscreen / HD.
        skin.getFont("default-font").getData().setScale(UiLayout.UI_FONT_SCALE);
        uiFont = skin.getFont("default-font");

        stage = new Stage(new ScreenViewport());
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);

        onCreate();

        Gdx.input.setInputProcessor(inputChain(beforeStage(), stage, afterStage()));
    }

    @Override
    public final void render() {
        float delta = Math.min(Gdx.graphics.getDeltaTime(), MAX_DELTA);
        if (!onRender(delta)) return;      // end the frame early — this also hides the stage's widgets
        stage.act(delta);
        stage.draw();
        onAfterUi(delta);
    }

    @Override
    public final void resize(int width, int height) {
        if (stage != null) stage.getViewport().update(width, height, true);
        onResize(width, height);
    }

    @Override
    public final void dispose() {
        if (disposed) return;
        disposed = true;
        onDispose();
        if (stage != null) {
            stage.dispose();
            stage = null;
        }
        if (skin != null) {
            skin.dispose();                // owns the fonts and the white texture
            skin = null;
            uiFont = null;
        }
    }


    // ------------------------------------------------------------------ hooks for the demo

    /** Builds the demo: world resources, camera, panel and (if used) the HUD. */
    protected abstract void onCreate();

    /**
     * Advances and draws the world for one frame.
     *
     * <p>Returning {@code false} ends the frame <b>before</b> the Scene2D stage is acted and
     * drawn — so it also hides every widget on it (a {@link NavBar}, for example). A screen whose
     * error state must stay operable (e.g. it still offers a <b>Load…</b> button) has to draw its
     * message itself and return {@code true}.
     *
     * @param delta frame time in seconds, already clamped to {@link #MAX_DELTA}
     * @return {@code true} to draw the UI on top of it, {@code false} to end the frame here
     */
    protected abstract boolean onRender(float delta);

    /**
     * Runs once per frame <b>after</b> the UI was drawn — statistics, periodic status text,
     * frame-buffer dumps or an auto-exit belong here.
     *
     * @param delta frame time in seconds, already clamped to {@link #MAX_DELTA}
     */
    protected void onAfterUi(float delta) {
        // Nothing by default.
    }

    /** Window size changed. The stage is already updated; adjust world-space cameras here. */
    protected void onResize(int width, int height) {
        // Nothing by default.
    }

    /** Frees every world resource (meshes, shaders, batches). Stage and skin are disposed here. */
    protected void onDispose() {
        // Nothing by default.
    }

    /**
     * An input processor that runs <b>before</b> the stage, e.g. to route the mouse wheel by cursor
     * position (over the panel → the UI scrolls, over the world → it zooms). Returning {@code true}
     * consumes the event before any widget sees it.
     *
     * @return the processor, or {@code null} for none
     */
    protected InputProcessor beforeStage() {
        return null;
    }

    /**
     * An input processor that runs <b>after</b> the stage, so it only receives what the UI did not
     * consume: world dragging, keyboard shortcuts, clicks into the scene.
     *
     * @return the processor, or {@code null} for none
     */
    protected InputProcessor afterStage() {
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The skin's {@code default-font} (scaled to {@link UiLayout#UI_FONT_SCALE}) — the font the
     * widgets use, so HUD text written with it matches the panel.
     *
     * @return the font, or {@code null} before {@link #create()} / after {@link #dispose()}
     */
    protected final BitmapFont uiFont() {
        return uiFont;
    }

    /**
     * Builds the input chain of the screen: {@code before} → {@code stage} → {@code after}, skipping
     * the {@code null} entries. The order is the contract of this class — the UI always wins over
     * the world, and only a processor registered <i>before</i> the stage can override a widget.
     *
     * @param before processor for events the UI must not see first (may be {@code null})
     * @param stage  the Scene2D stage (may be {@code null})
     * @param after  processor for events the UI did not consume (may be {@code null})
     * @return a multiplexer that always delivers in that order
     */
    static InputProcessor inputChain(InputProcessor before, InputProcessor stage,
                                     InputProcessor after) {
        InputMultiplexer chain = new InputMultiplexer();
        if (before != null) chain.addProcessor(before);
        if (stage != null) chain.addProcessor(stage);
        if (after != null) chain.addProcessor(after);
        return chain;
    }
}
