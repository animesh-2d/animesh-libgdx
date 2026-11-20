package com.jb.rts.player.ui;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;

/**
 * The shared player HUD, drawn on top of the OpenGL scene with a plain {@link Batch} +
 * {@link BitmapFont} (no Scene2D stage needed):
 *
 * <ul>
 *   <li><b>Title</b> — top-left corner, e.g. the file/level name.</li>
 *   <li><b>Primary label</b> — bottom-left, highlighted (e.g. {@code "2/3  idle"}).</li>
 *   <li><b>Stats</b> and <b>info</b> — bottom-left, right after the primary label
 *       (e.g. {@code "100 objects"} and {@code "60 fps"}).</li>
 *   <li><b>Corner value</b> — top-right, faint (e.g. the zoom factor).</li>
 * </ul>
 *
 * <p>Every position, gap, scale and colour comes from {@link UiLayout}, so the HUD looks the
 * same on every screen and is restyled in exactly one place. Empty (or {@code null}) fields are
 * simply not drawn, which is how conditional parts (a zoom value only while zoomed, an
 * animation label only while an animation plays) are handled — no branching in the caller.
 *
 * <p>Usage from a {@code render()} method:
 * <pre>
 *   hud.title("dino.json")
 *      .primary("2/3  idle")
 *      .stats("100 objects")
 *      .info("60 fps")
 *      .corner("x1.4");                 // or .corner(null) to hide it
 *   hud.draw(batch, font, viewW, viewH);
 * </pre>
 *
 * <p>The overlay owns no GPU resources: the {@link Batch} and {@link BitmapFont} (usually the
 * ones the screen already uses) are passed in on every draw, and the font scale is restored
 * afterwards.
 */
public final class HudOverlay {

    /** Measures the labels so the stats can be placed right after the previous one. */
    private final GlyphLayout layout = new GlyphLayout();

    private String title = "";
    private String primary = "";
    private String stats = "";
    private String info = "";
    private String corner = "";

    /** Sets the top-left title line ({@code null}/empty hides it). */
    public HudOverlay title(String text) {
        title = nonNull(text);
        return this;
    }

    /** Sets the highlighted bottom-left label ({@code null}/empty hides it). */
    public HudOverlay primary(String text) {
        primary = nonNull(text);
        return this;
    }

    /** Sets the first statistic on the bottom-left line ({@code null}/empty hides it). */
    public HudOverlay stats(String text) {
        stats = nonNull(text);
        return this;
    }

    /** Sets the statistic after {@link #stats(String)} ({@code null}/empty hides it). */
    public HudOverlay info(String text) {
        info = nonNull(text);
        return this;
    }

    /** Sets the faint top-right corner value ({@code null}/empty hides it). */
    public HudOverlay corner(String text) {
        corner = nonNull(text);
        return this;
    }

    public String title() {
        return title;
    }

    public String primary() {
        return primary;
    }

    public String stats() {
        return stats;
    }

    public String info() {
        return info;
    }

    public String corner() {
        return corner;
    }

    /** Clears every line — nothing is drawn afterwards. */
    public HudOverlay clear() {
        title = "";
        primary = "";
        stats = "";
        info = "";
        corner = "";
        return this;
    }

    /**
     * Draws the overlay for a screen of {@code viewW} × {@code viewH} pixels (GL bottom-left
     * origin). Does its own {@code begin()}/{@code end()} pairs and restores the font scale it
     * was called with.
     *
     * @param batch the batch the screen draws with (must not be inside a begin/end block)
     * @param font  the font to draw with, e.g. {@link UiSkin#load(int, com.badlogic.gdx.graphics.Color)}
     * @param viewW current screen width in pixels
     * @param viewH current screen height in pixels
     */
    public void draw(Batch batch, BitmapFont font, float viewW, float viewH) {
        if (batch == null || font == null) return;

        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        try {
            drawTitle(batch, font, viewH);
            drawStatusLine(batch, font);
            drawCorner(batch, font, viewW, viewH);
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /** Top-left: the title line. */
    private void drawTitle(Batch batch, BitmapFont font, float viewH) {
        if (title.isEmpty()) return;
        font.getData().setScale(UiLayout.HUD_TITLE_SCALE);
        batch.begin();
        font.setColor(UiLayout.HUD_TITLE_COLOR);
        font.draw(batch, title, UiLayout.HUD_MARGIN, viewH - UiLayout.HUD_MARGIN);
        batch.end();
    }

    /** Bottom-left: primary label, then stats, then info — each one after the previous. */
    private void drawStatusLine(Batch batch, BitmapFont font) {
        if (primary.isEmpty() && stats.isEmpty() && info.isEmpty()) return;
        font.getData().setScale(UiLayout.HUD_LINE_SCALE);
        batch.begin();
        float x = UiLayout.HUD_MARGIN;
        if (!primary.isEmpty()) {
            font.setColor(UiLayout.HUD_PRIMARY_COLOR);
            font.draw(batch, primary, x, UiLayout.HUD_LINE_BASELINE);
            layout.setText(font, primary);
            x += layout.width + UiLayout.HUD_PRIMARY_GAP;
        }
        font.setColor(UiLayout.HUD_STAT_COLOR);
        if (!stats.isEmpty()) {
            font.draw(batch, stats, x, UiLayout.HUD_LINE_BASELINE);
            layout.setText(font, stats);
            x += layout.width + UiLayout.HUD_ITEM_GAP;
        }
        if (!info.isEmpty()) {
            font.draw(batch, info, x, UiLayout.HUD_LINE_BASELINE);
        }
        batch.end();
    }

    /** Top-right: the faint corner value (e.g. the zoom factor). */
    private void drawCorner(Batch batch, BitmapFont font, float viewW, float viewH) {
        if (corner.isEmpty()) return;
        font.getData().setScale(1f);
        batch.begin();
        font.setColor(UiLayout.HUD_HINT_COLOR);
        font.draw(batch, corner, viewW - UiLayout.HUD_CORNER_INSET, viewH - UiLayout.HUD_MARGIN);
        batch.end();
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
