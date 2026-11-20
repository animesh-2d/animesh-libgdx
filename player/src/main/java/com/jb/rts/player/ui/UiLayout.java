package com.jb.rts.player.ui;

import com.badlogic.gdx.graphics.Color;

/**
 * The <b>single source of truth</b> for the shared UI layout: paddings, button sizes, gaps,
 * font scales and HUD colours.
 *
 * <p>Everything {@link NavBar} and {@link HudOverlay} draw is sized and positioned from these
 * constants, so editing a value here restyles <b>every</b> screen that uses the toolkit
 * (mesh player, VAT demo) at once — no widget code has to be touched.
 *
 * <p>All values are pixels at a 1:1 {@code ScreenViewport}, i.e. the on-screen size stays the
 * same on every window size and fullscreen resolution. The HUD colours are
 * {@code static final} {@link Color} instances — never mutate them (they are shared); pass
 * them to {@code BitmapFont#setColor(Color)}, which copies the components.
 */
public final class UiLayout {

    private UiLayout() {}

    // ------------------------------------------------------------------
    // Scene2D chrome (corner-anchored button bars)
    // ------------------------------------------------------------------

    /** Outer padding of a corner-anchored bar (screen edge → widget). */
    public static final float PADDING = 12f;
    /** Height of every button in the bottom-right navigation bar. */
    public static final float BUTTON_HEIGHT = 42f;
    /** Width of the Prev / Next buttons. */
    public static final float NAV_BUTTON_WIDTH = 130f;
    /** Width of the optional Load ("open a file") button of a {@link NavBar}. */
    public static final float LOAD_BUTTON_WIDTH = 130f;
    /**
     * Width of the optional export selector (drop-down) of a {@link NavBar}. Wide enough for the
     * longest bundled example name ({@code dino_longneck-export.json}) at
     * {@link #UI_FONT_SCALE}, without stealing the bar's corner.
     */
    public static final float EXPORT_SELECT_WIDTH = 300f;
    /** Width of the Close button. */
    public static final float CLOSE_BUTTON_WIDTH = 110f;
    /** Gap between the Prev and Next buttons. */
    public static final float NAV_BUTTON_GAP = 8f;
    /** Gap between the Prev/Next pair and Close. */
    public static final float CLOSE_BUTTON_GAP = 14f;
    /** Scale applied to the skin's {@code default-font} (readable in fullscreen / HD). */
    public static final float UI_FONT_SCALE = 1.25f;
    /** Alpha of a navigation button that is currently not applicable (dimmed, not hidden). */
    public static final float DISABLED_ALPHA = 0.45f;

    /** Label of the "previous" navigation button. */
    public static final String PREV_LABEL = "◀ Prev";
    /** Label of the "next" navigation button. */
    public static final String NEXT_LABEL = "Next ▶";
    /** Label of the "close / exit" navigation button. */
    public static final String CLOSE_LABEL = "Close";
    /** Label of the optional "load another file" navigation button. */
    public static final String LOAD_LABEL = "Load…";


    // ------------------------------------------------------------------
    // Collapsible parameter panels (see ControlPanel)
    // ------------------------------------------------------------------

    /** Default width of a parameter panel window (a {@link ControlPanel}). */
    public static final float PANEL_WIDTH = 720f;

    /** Width of the label column of a panel form row ("  Radius (m)"). */
    public static final float FORM_LABEL_WIDTH = 300f;
    /** Width of the widget column of a panel form row (a slider, a swatch, …). */
    public static final float FORM_WIDGET_WIDTH = 230f;
    /** Width of the value column of a panel form row (the number / hex value). */
    public static final float FORM_VALUE_WIDTH = 110f;
    /** Width of a colour swatch button in a panel form row. */
    public static final float FORM_SWATCH_WIDTH = 160f;

    /**
     * Height of the <b>single line</b> a collapsed panel shrinks to. One text line at
     * {@link #UI_FONT_SCALE} plus a little button padding — small enough that a collapsed panel
     * blocks practically nothing of the world underneath it.
     */
    public static final float COLLAPSED_BAR_HEIGHT = 30f;
    /** Glyph on the collapsed line (points right = "closed, click to open it again"). */
    public static final String EXPAND_GLYPH = "▶";
    /** Glyph on the collapse button of a panel's title bar (points down = "open, click to close"). */
    public static final String COLLAPSE_GLYPH = "▼";
    /** Edge length of the square collapse button in a panel's title bar. */
    public static final float COLLAPSE_BUTTON_SIZE = 26f;
    /** Gap between a panel's title and its collapse button. */
    public static final float COLLAPSE_BUTTON_GAP = 12f;
    /** Edge length of the check box glyph of a {@code CheckBox} (the skins draw 1×1 px). */
    public static final float CHECKBOX_BOX = 18f;
    /**
     * Height of a {@code Window}'s title bar (the band that holds the title and the collapse
     * button). Scene2D derives it from the window background's <em>top padding</em>, and both skins
     * paint windows with a 1×1 px tinted texture — so {@link UiSkin} sets this value as the top
     * height of every window background. Without it the band would be 0 px tall and the title would
     * be clipped out of sight.
     */
    public static final float WINDOW_TITLE_HEIGHT = 30f;

    // ------------------------------------------------------------------
    // HUD / overlay text (drawn with a SpriteBatch + BitmapFont)
    // ------------------------------------------------------------------

    /** Margin of the overlay text from the screen edges. */
    public static final float HUD_MARGIN = 16f;
    /** Font scale of the title line (top-left, e.g. the current file name). */
    public static final float HUD_TITLE_SCALE = 1.5f;
    /** Font scale of the bottom-left status line. */
    public static final float HUD_LINE_SCALE = 1.1f;
    /** Baseline of the bottom-left status line, measured from the bottom screen edge. */
    public static final float HUD_LINE_BASELINE = 14f;
    /** Gap between the primary label and the following statistics. */
    public static final float HUD_PRIMARY_GAP = 28f;
    /** Gap between two statistics on the bottom-left status line. */
    public static final float HUD_ITEM_GAP = 24f;
    /** Left edge of the faint corner value, measured from the right screen edge. */
    public static final float HUD_CORNER_INSET = 80f;

    /** Colour of the title line (top-left). */
    public static final Color HUD_TITLE_COLOR = new Color(1f, 1f, 1f, 1f);
    /** Colour of the highlighted primary label, e.g. {@code "2/3  idle"}. */
    public static final Color HUD_PRIMARY_COLOR = new Color(0.85f, 0.9f, 1f, 1f);
    /** Colour of the secondary statistics (object count, fps). */
    public static final Color HUD_STAT_COLOR = new Color(0.62f, 0.7f, 0.8f, 1f);
    /** Colour of the faint corner value (e.g. the zoom factor). */
    public static final Color HUD_HINT_COLOR = new Color(0.7f, 0.7f, 0.7f, 0.7f);

    // ------------------------------------------------------------------
    // Widget geometry that a tinted 1×1 drawable cannot express
    // ------------------------------------------------------------------

    /**
     * Thickness of a {@code Slider} track (across the sliding direction) and of the filled part.
     * Applied by {@link UiSkin} to the slider styles of <b>both</b> skins (JSON and fallback),
     * because {@code Skin$TintedDrawable} only carries a colour — without this every track is
     * 2 px thin.
     */
    public static final float TRACK_THICKNESS = 10f;

    /**
     * Edge length of the square {@code Slider} knob. It is drawn centered on the value, is
     * visible on both orientations and is the part the mouse grabs — keep it clearly larger than
     * {@link #TRACK_THICKNESS} so it reads as a handle.
     */
    public static final float SLIDER_KNOB = 22f;

    /** Thickness of a {@code ProgressBar} track (across the bar direction). */
    public static final float PROGRESS_THICKNESS = 16f;

    /** Thickness of the {@code ScrollPane} scroll bars (width of the vertical one, etc.). */
    public static final float SCROLLBAR_THICKNESS = 10f;
}
