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
    /** Gap between the export selector / Load button and the next widget of the bar. */
    public static final float NAV_BUTTON_GAP = 8f;
    /** Scale applied to the skin's {@code default-font} (readable in fullscreen / HD). */
    public static final float UI_FONT_SCALE = 1.25f;

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
     * Height of a {@link NumberSpinner} row (the {@code -} / field / {@code +} strip). Matches the
     * text-field rows of a panel, so a spinner and an entry field line up.
     */
    public static final float SPINNER_HEIGHT = 30f;
    /** Width of one of the two step buttons of a {@link NumberSpinner}. */
    public static final float SPINNER_BUTTON_WIDTH = 34f;
    /** Width of the editable value field of a {@link NumberSpinner}. */
    public static final float SPINNER_FIELD_WIDTH = 96f;
    /** Gap between the three parts of a {@link NumberSpinner}. */
    public static final float SPINNER_GAP = 4f;

    /**
     * Height cap of a panel's colour table (one swatch row per palette colour). The table scrolls
     * on its own once an export defines more colours than fit, so a full 64-colour palette cannot
     * make the whole panel taller than the stage.
     */
    public static final float COLOR_TABLE_MAX_HEIGHT = 240f;

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
    /**
     * Vertical space a top-left parameter panel leaves free above itself, so the HUD title line
     * (drawn at {@link #HUD_MARGIN} from the top edge) stays readable next to it.
     */
    public static final float HUD_TITLE_BAND = 34f;
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

    // ------------------------------------------------------------------
    // FSM state graph (see FsmGraphOverlay)
    // ------------------------------------------------------------------

    /** Radius of one state node in the graph, in pixels. */
    public static final float FSM_NODE_RADIUS = 26f;

    /**
     * Radius of the circle the nodes sit on. Wide enough that eight nodes of
     * {@link #FSM_NODE_RADIUS} never touch, small enough to stay clear of the parameter panel.
     */
    public static final float FSM_RING_RADIUS = 132f;

    /**
     * Free distance the graph keeps from the left screen edge, measured to the outermost pixel of a
     * node — see {@link #fsmGraphCenterX(float)}.
     */
    public static final float FSM_GRAPH_MARGIN_X = 100f;

    /**
     * Free distance the graph keeps from the bottom screen edge, measured to the outermost pixel of
     * a node — see {@link #fsmGraphCenterY(float)}. The bottom-left HUD status line
     * ({@link #HUD_LINE_BASELINE}) sits inside this margin, so the graph never covers it.
     */
    public static final float FSM_GRAPH_MARGIN_Y = 100f;

    /**
     * x of the graph's centre in screen pixels (GL bottom-left origin): the left edge position that
     * guarantees {@link #FSM_GRAPH_MARGIN_X} of free space in front of the outermost node.
     *
     * <p><b>Always derive the centre from this method</b> instead of hard-coding a value: the free
     * distance then stays exactly the margin, whatever {@link #FSM_RING_RADIUS} /
     * {@link #FSM_NODE_RADIUS} or {@link #FSM_LINE_WIDTH} become — a hand-tuned centre drifts into
     * the corner as soon as a radius grows.</p>
     *
     * @param viewW current screen width in pixels (unused today, kept so a future clamp can use it)
     * @return centre x for {@link FsmGraphLayout}
     */
    public static float fsmGraphCenterX(float viewW) {
        return FSM_GRAPH_MARGIN_X + FSM_LINE_WIDTH + FSM_NODE_RADIUS + FSM_RING_RADIUS;
    }

    /**
     * y of the graph's centre in screen pixels: the bottom edge position that guarantees
     * {@link #FSM_GRAPH_MARGIN_Y} of free space below the outermost node (the {@link #HUD_LINE_BASELINE}
     * status line lives there).
     *
     * @param viewH current screen height in pixels (unused today, kept so a future clamp can use it)
     * @return centre y for {@link FsmGraphLayout}
     */
    public static float fsmGraphCenterY(float viewH) {
        return FSM_GRAPH_MARGIN_Y + FSM_LINE_WIDTH + FSM_NODE_RADIUS + FSM_RING_RADIUS;
    }

    /** Line thickness of node borders and transition arrows. */
    public static final float FSM_LINE_WIDTH = 2.2f;

    /** Length of a transition arrow's triangle head, in pixels. */
    public static final float FSM_ARROW_HEAD = 11f;

    /**
     * Perpendicular offset applied to one direction of a bidirectional pair ({@code walk <-> run}),
     * so the two arrows are drawn side by side instead of on top of each other.
     */
    public static final float FSM_ARROW_SPREAD = 4.5f;

    /** Font scale of the node labels. */
    public static final float FSM_LABEL_SCALE = 1f;

    /** Font scale of the tooltip next to the hovered node. */
    public static final float FSM_TOOLTIP_SCALE = 1f;

    /** Padding inside a node body, so the label never touches the border. */
    public static final float FSM_LABEL_PADDING = 4f;

    /** Border and fill of the <b>active</b> state (solid green). */
    public static final Color FSM_ACTIVE_COLOR = new Color(0.35f, 0.85f, 0.45f, 1f);
    /** Fill behind an active node (translucent green). */
    public static final Color FSM_ACTIVE_FILL = new Color(0.20f, 0.55f, 0.28f, 0.55f);
    /** Border of the <b>outgoing</b> state during a crossfade (amber). */
    public static final Color FSM_OUTGOING_COLOR = new Color(1f, 0.72f, 0.25f, 1f);
    /** Fill behind an outgoing node (translucent amber). */
    public static final Color FSM_OUTGOING_FILL = new Color(0.55f, 0.38f, 0.10f, 0.5f);
    /** Border of the <b>legal</b> target of the active state (green). */
    public static final Color FSM_LEGAL_COLOR = new Color(0.40f, 0.82f, 0.50f, 1f);
    /** Fill behind a legal node (translucent green, dimmer than the active one). */
    public static final Color FSM_LEGAL_FILL = new Color(0.14f, 0.34f, 0.19f, 0.45f);
    /** Border of an <b>illegal</b> target (red): the state's rule refuses this transition. */
    public static final Color FSM_ILLEGAL_COLOR = new Color(0.90f, 0.34f, 0.34f, 1f);
    /** Fill behind an illegal node (translucent dark red). */
    public static final Color FSM_ILLEGAL_FILL = new Color(0.36f, 0.13f, 0.13f, 0.45f);
    /** Colour of an arrow whose transition the current state's rule allows. */
    public static final Color FSM_ARROW_LEGAL_COLOR = new Color(0.40f, 0.78f, 0.48f, 0.80f);
    /** Colour of an arrow whose transition the current state's rule refuses. */
    public static final Color FSM_ARROW_ILLEGAL_COLOR = new Color(0.82f, 0.32f, 0.32f, 0.70f);
    /** Colour of the arrow that is currently being crossfaded. */
    public static final Color FSM_ARROW_ACTIVE_COLOR = new Color(1f, 0.72f, 0.25f, 1f);
    /** Panel behind the tooltip text. */
    public static final Color FSM_TOOLTIP_FILL = new Color(0.08f, 0.09f, 0.11f, 0.92f);
    /** Border of the tooltip panel. */
    public static final Color FSM_TOOLTIP_BORDER = new Color(0.55f, 0.60f, 0.68f, 0.9f);
    /** Text colour inside the tooltip. */
    public static final Color FSM_TOOLTIP_COLOR = new Color(0.92f, 0.94f, 1f, 1f);
}
