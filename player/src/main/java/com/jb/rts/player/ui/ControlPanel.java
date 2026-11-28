package com.jb.rts.player.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;

import java.util.Locale;

/**
 * The shared <b>parameter panel</b> of a player screen: one fill-parent {@link Table} holding a
 * movable, non-resizable {@link Window} whose content is a scrollable form — plus the
 * <b>collapse</b> behaviour described below.
 *
 * <p>This is the "ExplosionPlayer way" of doing a panel, extracted so that every screen builds its
 * controls the same way and a layout change lands everywhere at once:</p>
 *
 * <ul>
 *   <li>The window is anchored <b>top-left</b> at {@link UiLayout#PADDING}, is movable by its title
 *       bar and can never be dragged out of the stage. The root table is
 *       {@link Touchable#childrenOnly}, so clicks beside the panel still reach the world below.</li>
 *   <li>The content lives in a {@link ScrollPane} with fixed scroll bars, so a long form never
 *       makes the window taller than the stage: a shrink-wrapped window stops at the bottom edge of
 *       the stage and scrolls from there on (see {@link #stageHeightLimit}), a
 *       {@code fillHeight} window is the stage height from the start. Rows are added with the
 *       helpers below
 *       ({@link #header}, {@link #slider}, {@link #colorRow}, {@link #checkBox}, {@link #button},
 *       {@link #label}, {@link #hint}, {@link #statusLine}) — they all use the three-column layout
 *       {@code label | widget | value} sized from {@link UiLayout}.</li>
 *   <li>The panel can be <b>collapsed into a single line</b> ({@link #collapse()}) that blocks
 *       practically no screen space, and a <b>click on that line expands it again</b>
 *       ({@link #expand()}). The collapse control is the {@link UiLayout#COLLAPSE_GLYPH} button in
 *       the title bar. Collapsing releases the keyboard/scroll focus of the panel, so a text field
 *       that owned the keyboard can never swallow the world's shortcuts while it is hidden.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   public final class MyControls extends ControlPanel {
 *       public MyControls(Skin skin, MyConfig config) {
 *           super(skin, "My panel");                 // or (skin, title, width, fillHeight)
 *           header("Sizes");
 *           slider("  Radius (m)", 0.5f, 60f, 0.1f, config.getRadius(), false,
 *                  value -&gt; config.setRadius(value));
 *           checkBox("  Show grid", false, config::setGridVisible);
 *           button("Do it!", this::doIt);
 *           statusLine();                            // setStatus(..) fills it later
 *       }
 *   }
 *   ...
 *   stage.addActor(new MyControls(skin, config).getRoot());
 * </pre>
 *
 * <p>The panel never owns the {@link Skin} or the {@link Stage} — the screen does (see
 * {@link PlayerScreen}), so a panel can be created, added and dropped freely.</p>
 */
public abstract class ControlPanel {

    /** Slider callback — a plain {@code float}, no boxed {@code java.util.function} consumer. */
    public interface FloatListener {
        void changed(float value);
    }

    /** Check box callback — a plain {@code boolean}, no boxing and no recursion through getters. */
    public interface BooleanListener {
        void changed(boolean value);
    }

    /** Columns of the form: label | widget | value (see {@link #slider}). */
    protected static final int COLUMNS = 3;

    /** Slider styles the JSON and the fallback skin may provide, in preference order. */
    private static final String[] SLIDER_STYLES = {"default-horizontal", "default-h", "default"};

    /** Colour of a {@link #hint} and of the {@link #statusLine}. */
    private static final Color HINT_COLOR = new Color(0.55f, 0.59f, 0.67f, 1f);
    private static final Color STATUS_COLOR = new Color(0.72f, 0.76f, 0.84f, 1f);

    /** The skin every widget of this panel is built from (owned by the screen, not the panel). */
    protected final Skin skin;

    private final String title;
    private final float panelWidth;
    private final boolean fillHeight;

    /** Fill-parent anchor of the whole panel; the actor to add to the stage. */
    private final Table root = new Table() {
        /**
         * The stage changed size, so the expanded window's height limit did too
         * ({@link #stageHeightLimit}): re-measure the holder, otherwise a window that was cut short
         * would keep its clamped height after the user makes the window taller again.
         */
        @Override
        protected void sizeChanged() {
            super.sizeChanged();
            if (holder != null) holder.invalidateHierarchy();
        }
    };
    /**
     * Holds either the full {@link #window} or the single-line {@link #collapsedBar} — never both.
     * Swapping them with {@code clearChildren()} + {@code add()} is what makes {@link Table}
     * re-measure, so the collapsed panel really occupies one line and nothing else.
     */
    private final Table holder = new Table();
    /** The form the rows of the panel are added to (behind the {@link ScrollPane}). */
    private final Table form = new Table();
    private final Window window;
    /** The scrollable form viewport; the wheel target while the cursor is over the panel. */
    private final ScrollPane scroll;
    /** The one line the panel shrinks to while collapsed; clicking it expands the panel again. */
    private final TextButton collapsedBar;

    /**
     * Upper bound for the expanded window's height: the space the fill-parent {@link #root} offers.
     *
     * <p>libGDX evaluates a cell's max height ({@link Value}) on <b>every</b> layout pass, so this
     * needs no resize hook: the window shrink-wraps its content while that fits and stops at the
     * stage's bottom edge as soon as the form grows longer than the window — the {@link ScrollPane}
     * then scrolls instead of the colour table (and the status line below it) running off-screen in
     * a small window. {@code 0} means "no limit", which is what the first layout pass sees before a
     * stage size is known.</p>
     */
    private final Value stageHeightLimit = new Value() {
        @Override
        public float get(Actor context) {
            return Math.max(0f, root.getHeight() - root.getPadTop() - root.getPadBottom());
        }
    };

    private Label status;
    private boolean collapsed;


    /**
     * A panel of the default width ({@link UiLayout#PANEL_WIDTH}) that hugs its content height.
     *
     * @param skin  the shared toolkit skin ({@link UiSkin#load()})
     * @param title the window title, also shown on the collapsed line
     */
    protected ControlPanel(Skin skin, String title) {
        this(skin, title, UiLayout.PANEL_WIDTH, false);
    }

    /**
     * @param skin       the shared toolkit skin ({@link UiSkin#load()})
     * @param title      the window title, also shown on the collapsed line
     * @param panelWidth width of the window (and of the collapsed line) in pixels
     * @param fillHeight {@code true} to stretch the window over the full stage height (a long,
     *                   scrollable parameter list); {@code false} to shrink-wrap it (a small HUD
     *                   panel). A collapsed panel always needs only
     *                   {@link UiLayout#COLLAPSED_BAR_HEIGHT}, whatever this says.
     */
    protected ControlPanel(Skin skin, String title, float panelWidth, boolean fillHeight) {
        if (skin == null) throw new IllegalArgumentException("skin cannot be null.");
        this.skin = skin;
        this.title = title == null ? "" : title;
        this.panelWidth = panelWidth;
        this.fillHeight = fillHeight;

        root.setFillParent(true);
        root.top().left().pad(UiLayout.PADDING);
        // Clicks beside the panel must not be swallowed by the fill-parent table.
        root.setTouchable(Touchable.childrenOnly);

        window = new Window(this.title, skin);
        window.setMovable(true);
        window.setResizable(false);
        window.setKeepWithinStage(true);

        // The collapse control sits in the title bar (right of the title) as a "toggle" button.
        TextButton collapseButton = new TextButton(UiLayout.COLLAPSE_GLYPH, skin, "toggle");
        collapseButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                collapse();
            }
        });
        window.getTitleTable().add(collapseButton)
            .width(UiLayout.COLLAPSE_BUTTON_SIZE)
            .height(UiLayout.COLLAPSE_BUTTON_SIZE)
            .padLeft(UiLayout.COLLAPSE_BUTTON_GAP);

        form.top().left();
        form.defaults().left().pad(1f);

        scroll = new ScrollPane(form, skin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, false);
        scroll.setSmoothScrolling(true);
        window.add(scroll).grow().pad(8f);
        // The wheel scrolls this form while the cursor is over the panel and belongs to the screen
        // again as soon as it leaves — see ScrollFocusOnHover.
        window.addListener(new ScrollFocusOnHover());

        // The single line of a collapsed panel: click anywhere on it to get the panel back.
        collapsedBar = new TextButton("", skin, "toggle");
        collapsedBar.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                expand();
            }
        });

        holder.top().left();
        // A pure layout container: only the window (or the collapsed line) may be hit, so a click
        // beside the panel still reaches the world underneath.
        holder.setTouchable(Touchable.childrenOnly);
        if (fillHeight) {
            root.add(holder).grow();
        } else {
            root.add(holder).top().left();
        }

        setCollapsed(false);
    }

    // ------------------------------------------------------------------ collapse / expand

    /** {@code true} while the panel is reduced to its single line. */
    public final boolean isCollapsed() {
        return collapsed;
    }

    /** Collapses the panel into its single line (no-op while it already is collapsed). */
    public final void collapse() {
        setCollapsed(true);
    }

    /** Expands the panel back to the full form (no-op while it already is expanded). */
    public final void expand() {
        setCollapsed(false);
    }

    /** Flips the panel between the full form and the single line. */
    public final void toggleCollapsed() {
        setCollapsed(!collapsed);
    }

    /**
     * Shows the full form ({@code false}) or the single line ({@code true}).
     *
     * <p>The window and the line are swapped inside the panel's {@link #holder}: while collapsed the
     * window is not part of the stage at all, so it cannot take a click, a wheel event or the
     * keyboard focus — the world underneath keeps all of it. Collapsing also drops the panel's
     * keyboard/scroll focus for the same reason.</p>
     */
    public final void setCollapsed(boolean value) {
        collapsed = value;
        holder.clearChildren();
        if (collapsed) {
            collapsedBar.setText(UiLayout.EXPAND_GLYPH + "  " + title);
            holder.add(collapsedBar)
                .width(panelWidth)
                .height(UiLayout.COLLAPSED_BAR_HEIGHT)
                .top().left();
            releaseFocus();
        } else if (fillHeight) {
            holder.add(window).width(panelWidth).growY();
        } else {
            // Shrink-wrap, but never below the stage's bottom edge: see stageHeightLimit.
            holder.add(window).width(panelWidth).maxHeight(stageHeightLimit).top().left();
        }
        holder.invalidateHierarchy();
    }

    /** Drops the keyboard and scroll focus a hidden widget may still hold. */
    private void releaseFocus() {
        Stage stage = root.getStage();
        if (stage == null) return;                 // not added to a stage yet
        stage.unfocusAll();
    }

    /**
     * Gives the form's {@link #scroll} pane the stage's <b>scroll focus</b> while the cursor is
     * over the panel and releases it again on exit.
     *
     * <p>libGDX only sets a {@code ScrollPane}'s scroll focus on a click and never releases it.
     * Without this handover the panel would own the mouse wheel of the whole screen after the
     * first click inside it — the mesh player's zoom would be dead in the drawing area. With it the
     * wheel follows the pointer: over the panel it scrolls the form (no click needed), everywhere
     * else the stage does not consume it and the screen's {@code beforeStage()} processor can.</p>
     */
    private final class ScrollFocusOnHover extends InputListener {
        @Override
        public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
            if (pointer != -1) return;             // a dragging pointer is not hovering
            Stage stage = root.getStage();
            if (stage != null) stage.setScrollFocus(scroll);
        }

        @Override
        public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
            if (pointer != -1) return;
            Stage stage = root.getStage();
            // Only drop a focus the panel owns: an open SelectBox popup keeps its own list focused.
            if (stage != null && stage.getScrollFocus() == scroll) stage.setScrollFocus(null);
        }
    }

    // ------------------------------------------------------------------ access for the screen

    /** The panel as a fill-parent actor; add it to the {@link Stage} once. */
    public final Table getRoot() {
        return root;
    }

    /** The form of the panel, for rows the helpers below cannot express. */
    protected final Table form() {
        return form;
    }

    /** Replaces the status line created by {@link #statusLine()} (ignored while there is none). */
    public void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    // ------------------------------------------------------------------ form rows

    /**
     * Adds a section header using the skin's {@code title} label style.
     *
     * @param table the table to add the row to (usually {@link #form()})
     * @param text  the header text
     */
    protected Label header(Table table, String text) {
        Label header = new Label(text, skin, "title");
        table.add(header).colspan(COLUMNS).left().padTop(10f).padBottom(2f).row();
        return header;
    }

    /** Adds a section header to the form. */
    protected Label header(String text) {
        return header(form, text);
    }

    /**
     * Adds one labelled slider row {@code label | slider | value} and keeps the value label in sync
     * with the knob.
     *
     * <p>The listener is invoked <b>once</b> with the initial value, so the widget and the model it
     * edits can never disagree — the slider clamps the value to its range, and the clamped value is
     * the one written back.</p>
     *
     * @param labelText label shown in the first column
     * @param min       lowest value of the slider
     * @param max       highest value of the slider
     * @param step      step size of the knob
     * @param value     initial value (clamped to {@code [min, max]})
     * @param integer   {@code true} to show and round to whole numbers
     * @param listener  writes the value into the model
     * @return the slider, e.g. to re-range it later
     */
    protected Slider slider(String labelText, float min, float max, float step, float value,
                            boolean integer, FloatListener listener) {
        return slider(form, labelText, min, max, step, value, integer, listener);
    }

    /**
     * Same as {@link #slider(String, float, float, float, float, boolean, FloatListener)} but adds
     * the row to {@code table} — for a <b>sub-section</b> that a screen shows, dims or disables as a
     * whole (the water player's target-dependent sea block).
     *
     * @param table the table to add the row to
     */
    protected Slider slider(Table table, String labelText, float min, float max, float step,
                            float value, boolean integer, FloatListener listener) {
        Label name = new Label(labelText, skin);
        Slider slider = new Slider(min, max, step, false, sliderStyle(skin));
        slider.setValue(value);

        Label valueLabel = new Label(format(slider.getValue(), integer), skin);
        valueLabel.setAlignment(Align.right);

        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                float v = slider.getValue();
                valueLabel.setText(format(v, integer));
                listener.changed(v);
            }
        });

        float initial = integer ? Math.round(slider.getValue()) : slider.getValue();
        valueLabel.setText(format(initial, integer));
        listener.changed(initial);

        table.add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        table.add(slider).width(UiLayout.FORM_WIDGET_WIDTH).left();
        table.add(valueLabel).width(UiLayout.FORM_VALUE_WIDTH).right();
        table.row();
        return slider;
    }

    /**
     * Adds one colour row {@code title | swatch | hex}: the swatch shows the live {@link Color} and
     * opens the shared {@link ColorPickerDialog} on a click, so picker, swatch and hex value stay in
     * sync — the {@code color} instance itself is edited in place, exactly what a config wants.
     *
     * @param title row label, also the title of the colour picker
     * @param color the live colour the picker edits
     * @return the clickable swatch button
     */
    protected Button colorRow(String title, Color color) {
        return colorRow(form, title, color, null);
    }

    /**
     * Same as {@link #colorRow(String, Color)} but adds the row to {@code table} — for a
     * <b>sub-section</b> (see {@link #slider(Table, String, float, float, float, float, boolean,
     * FloatListener)}).
     *
     * @param table the table to add the row to
     * @param title row label, also the title of the colour picker
     * @param color the live colour the picker edits
     * @return the clickable swatch button
     */
    protected Button colorRow(Table table, String title, Color color) {
        return colorRow(table, title, color, null);
    }

    /**
     * Same as {@link #colorRow(Table, String, Color)}, plus a callback that fires on <b>every</b>
     * picker change — so the screen can recolour while the user is still dragging.
     *
     * @param table     the table to add the row to
     * @param title     row label, also the title of the colour picker
     * @param color     the live colour the picker edits (changed in place)
     * @param onChanged called after every edit, or {@code null}
     * @return the clickable swatch button
     */
    protected Button colorRow(Table table, String title, Color color, Runnable onChanged) {
        Label name = new Label(title, skin);

        Image swatch = new Image(skin.getDrawable("white"));
        swatch.setColor(color);
        Button pick = new Button(skin);
        pick.add(swatch).grow().pad(2f);

        Label value = new Label(ColorPicker.hex(color), skin);
        value.setAlignment(Align.right);

        pick.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Stage stage = root.getStage();
                if (stage == null) return;         // not added to a stage yet
                ColorPickerDialog.open(stage, skin, title.trim(), color, () -> {
                    swatch.setColor(color);
                    value.setText(ColorPicker.hex(color));
                    if (onChanged != null) onChanged.run();
                });
            }
        });

        table.add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        table.add(pick).width(UiLayout.FORM_SWATCH_WIDTH).height(30f).left();
        table.add(value).width(UiLayout.FORM_VALUE_WIDTH).right();
        table.row();
        return pick;
    }


    /**
     * Adds a check box row spanning the whole form width. The listener fires on <b>user</b> clicks
     * only, so a screen can mirror its own state into the box without recursing.
     *
     * @param label    text next to the box
     * @param checked  initial state
     * @param listener notified with the new state
     * @return the check box (e.g. for {@code setChecked} from a keyboard shortcut)
     */
    protected CheckBox checkBox(String label, boolean checked, BooleanListener listener) {
        CheckBox box = new CheckBox(label, skin);
        box.setChecked(checked);
        // The skin's checkbox drawables are 1×1 px textures: stretch the glyph and give it a size,
        // otherwise the state would only be a 1 px speck.
        box.getImage().setScaling(Scaling.stretch);
        box.getImageCell().width(UiLayout.CHECKBOX_BOX).height(UiLayout.CHECKBOX_BOX).padRight(6f);
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                listener.changed(box.isChecked());
            }
        });
        form.add(box).colspan(COLUMNS).left().padTop(2f).padBottom(2f).row();
        return box;
    }

    /** Adds a full-width action button of {@link UiLayout#BUTTON_HEIGHT}. */
    protected TextButton button(String label, Runnable action) {
        return button(label, action, UiLayout.BUTTON_HEIGHT);
    }

    /** Adds a full-width action button of the given height (e.g. the panel's main action). */
    protected TextButton button(String label, Runnable action, float height) {
        TextButton button = new TextButton(label, skin);
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                action.run();
            }
        });
        form.add(button).colspan(COLUMNS).growX().height(height).padTop(6f).row();
        return button;
    }

    /**
     * Adds a wrapped label row spanning the form — the place for information lines (map size,
     * render statistics, …). The caller styles it, e.g.
     * {@code label("").setColor(UiLayout.HUD_STAT_COLOR)}.
     */
    protected Label label(String text) {
        Label label = new Label(text, skin);
        label.setWrap(true);
        label.setAlignment(Align.left);
        form.add(label).colspan(COLUMNS).growX().width(wrapWidth()).padTop(6f).row();
        return label;
    }

    /**
     * Adds the wrapped, muted control hints at the bottom of the form.
     *
     * @param text the hints, one per line ({@code "\n"}-separated)
     */
    protected Label hint(String text) {
        Label hint = new Label(text, skin);
        hint.setColor(HINT_COLOR);
        hint.setWrap(true);
        hint.setAlignment(Align.left);
        form.add(hint).colspan(COLUMNS).growX().width(wrapWidth()).padTop(8f).row();
        return hint;
    }

    /**
     * Adds the (initially empty) status line at the bottom of the form and remembers it, so
     * {@link #setStatus(String)} can update it every few frames. Call it after the last control.
     *
     * @return the status label, for a screen that wants to style it further
     */
    protected Label statusLine() {
        status = new Label("", skin);
        status.setColor(STATUS_COLOR);
        status.setWrap(true);
        status.setAlignment(Align.left);
        form.add(status).colspan(COLUMNS).growX().width(wrapWidth()).padTop(10f).row();
        return status;
    }

    /**
     * Width a wrapped row may use: the panel minus window padding and the scroll bar. Also used by
     * a screen that adds wrapped rows to a sub-table (e.g. a colour table hint).
     */
    protected float wrapWidth() {
        return Math.max(1f, panelWidth - 90f);
    }

    // ------------------------------------------------------------------ pure helpers

    /**
     * Formats a slider value: integers without decimals, everything else with just enough decimals
     * that tiny (m³/s²) and huge (m/s of a 60 m blast) values stay readable.
     *
     * @param value   the number to show
     * @param integer {@code true} to round to a whole number
     */
    protected static String format(float value, boolean integer) {
        if (integer) return Integer.toString(Math.round(value));
        float abs = Math.abs(value);
        if (abs >= 1000f) return String.format(Locale.ROOT, "%.0f", value);
        if (abs >= 100f) return String.format(Locale.ROOT, "%.1f", value);
        if (abs >= 1f) return String.format(Locale.ROOT, "%.2f", value);
        if (abs >= 0.1f) return String.format(Locale.ROOT, "%.3f", value);
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * The JSON skin registers the slider style as {@code default-horizontal} while the programmatic
     * fallback also answers to {@code default-h} / {@code default}, and libGDX's
     * {@code Slider(min, max, step, vertical, skin)} only asks for {@code default-horizontal} — so
     * the style is resolved by hand.
     *
     * <p>{@code public} because a panel <em>section</em> (not only a {@link ControlPanel} subclass)
     * needs exactly the same slider look — see {@link FsmControlsSection}.</p>
     *
     * @throws IllegalStateException when the skin carries no slider style at all
     */
    public static Slider.SliderStyle sliderStyle(Skin skin) {
        for (String name : SLIDER_STYLES) {
            if (skin.has(name, Slider.SliderStyle.class)) {
                return skin.get(name, Slider.SliderStyle.class);
            }
        }
        throw new IllegalStateException(
            "skin has no SliderStyle (tried default-horizontal / default-h / default)");
    }
}

