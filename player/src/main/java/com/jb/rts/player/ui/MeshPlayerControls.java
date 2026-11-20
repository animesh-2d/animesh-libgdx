package com.jb.rts.player.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.jb.rts.player.InstanceField;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The mesh player's <b>parameter panel</b>: instance count, size randomisation, placement,
 * animation blending and the export's <b>colour table</b> — built on the family's
 * {@link ControlPanel} so it looks and behaves exactly like every other player screen's controls.
 *
 * <p>Rows (three-column {@code label | widget | value}, sized from {@link UiLayout}):</p>
 * <ul>
 *   <li><b>Count (Enter)</b> — type how many copies to draw and press <b>Enter</b>. The panel
 *       never applies a value itself: {@link Listener#instanceCountEntered(String)} returns the
 *       count that was actually applied (clamped), which is written back into the field.</li>
 *   <li><b>Size deviation (%)</b> — slider for the maximum random size deviation, e.g. {@code 30}
 *       means each copy is {@code 1 ± 0.3} times the mesh size.</li>
 *   <li><b>Placement</b> — {@code Grid} or {@code Random}; instances are always drawn in
 *       descending y (lower copies in front).</li>
 *   <li><b>Blend with</b> / <b>Blend weight (%)</b> — mix the current animation with another clip
 *       of the loaded file (walk → run). {@code — none —} switches blending off.</li>
 *   <li><b>Colours</b> — one row per colour of the loaded export's palette (the JSON's
 *       {@code "colors"} array), each with the shared {@link ColorPickerDialog}. Editing a row
 *       recolours exactly the mesh parts that reference that palette index; the live changes are
 *       forwarded so the 3D view updates while the user drags.</li>
 * </ul>
 *
 * <p>The panel holds callbacks only, never the screen, so it can be re-wired freely.
 * {@link #setAnimations(List)}, {@link #setInstanceCount(int)} and
 * {@link #setPalette(List, List, String)} mirror the screen's state without firing the listener
 * (programmatic refills are guarded).</p>
 */
public final class MeshPlayerControls extends ControlPanel {

    /** Receives every user change; the screen owns the state and mirrors it back. */
    public interface Listener {
        /**
         * The user typed a count and confirmed with Enter.
         *
         * @param text the raw field text
         * @return the count that was applied (the panel displays it), or a negative value when the
         *         entry was invalid and the field should keep its text
         */
        int instanceCountEntered(String text);

        /** The size-deviation slider moved (percent, {@code 0..100}). */
        void sizeDeviationChanged(float percent);

        /** The placement selector changed. */
        void placementChanged(InstanceField.Layout layout);

        /** The blend target changed ({@code -1} = off, otherwise an animation index). */
        void blendTargetChanged(int animationIndex);

        /** The blend weight slider moved ({@code 0..1}). */
        void blendWeightChanged(float weight);

        /**
         * One palette colour of the loaded export was edited — called on <b>every</b> picker
         * change, so the screen can recolour while the user is still dragging.
         *
         * @param index palette entry (0-based; the index the mesh parts reference)
         * @param color the live colour instance the picker edits
         */
        void paletteColorChanged(int index, Color color);
    }

    /** First item of the blend selector — the "off" entry. */
    private static final String BLEND_OFF_LABEL = "— none —";

    /** Colour of the colour table's note line (muted, like the panel's hint rows). */
    private static final Color PALETTE_NOTE_COLOR = new Color(0.55f, 0.59f, 0.67f, 1f);

    /** Placement modes in selector order; index 0 = {@link InstanceField.Layout#GRID}. */
    private static final InstanceField.Layout[] PLACEMENTS = InstanceField.Layout.values();

    private final Listener listener;
    private final TextField countField;
    private final Label countValue;
    private final SelectBox<String> blendBox;
    private final Slider blendSlider;
    /** Holds one {@code label | swatch | hex} row per palette colour; rebuilt per loaded export. */
    private final Table paletteTable;

    /** True while widgets are refilled programmatically, so no listener fires. */
    private boolean refilling;

    /**
     * Builds the panel.
     *
     * @param skin          the shared toolkit skin
     * @param instanceCount initial instance count shown in the field
     * @param layout        initial placement mode
     * @param listener      callbacks into the screen
     */
    public MeshPlayerControls(Skin skin, int instanceCount, InstanceField.Layout layout,
                              Listener listener) {
        super(skin, "Mesh player");
        if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
        this.listener = listener;

        header("Instances");
        countField = new TextField(Integer.toString(instanceCount), skin);
        countField.setMaxLength(6);
        countField.setAlignment(Align.center);
        countValue = textFieldRow("  Count (Enter)", countField);
        countValue.setText(Integer.toString(instanceCount));
        addCountListeners();

        slider("  Size deviation (%)", 0f, 100f, 1f, 0f, true,
            listener::sizeDeviationChanged);
        selectRow("  Placement", labels(PLACEMENTS), indexOf(layout),
            index -> listener.placementChanged(PLACEMENTS[index]));

        header("Animation");
        blendBox = selectRow("  Blend with", blendLabels(null), 0,
            index -> listener.blendTargetChanged(index - 1));
        blendSlider = slider("  Blend weight (%)", 0f, 100f, 1f, 0f, true,
            value -> listener.blendWeightChanged(value / 100f));

        header("Colours");
        paletteTable = new Table(skin);
        paletteTable.top().left();
        paletteTable.defaults().left().pad(1f);
        // The table scrolls on its own so a 64-colour palette cannot outgrow the stage height.
        ScrollPane paletteScroll = new ScrollPane(paletteTable, skin);
        paletteScroll.setFadeScrollBars(false);
        paletteScroll.setScrollingDisabled(true, false);
        paletteScroll.setOverscroll(false, false);
        form().add(paletteScroll).colspan(COLUMNS).growX()
            .maxHeight(UiLayout.COLOR_TABLE_MAX_HEIGHT).row();
        statusLine();
    }


    // ------------------------------------------------------------------ state mirroring

    /** Refills the blend selector and resets it to "off" (used when another file loads). */
    public void setAnimations(List<String> names) {
        refilling = true;
        try {
            blendBox.setItems(blendLabels(names));
            blendBox.setSelectedIndex(0);
        } finally {
            refilling = false;
        }
    }

    /** Shows {@code count} in the entry field (no listener). */
    public void setInstanceCount(int count) {
        refilling = true;
        try {
            countField.setText(Integer.toString(count));
            countValue.setText(Integer.toString(count));
        } finally {
            refilling = false;
        }
    }

    /**
     * Mirrors the blend target and weight into the widgets (no listener) — used by the screen's
     * keyboard shortcuts so keyboard and panel can never disagree.
     *
     * @param animationIndex target clip index, or {@code -1} for "blend off"
     * @param weight         blend weight in {@code 0..1}
     */
    public void setBlend(int animationIndex, float weight) {
        refilling = true;
        try {
            int item = animationIndex + 1;
            if (item >= 0 && item < blendBox.getItems().size) {
                blendBox.setSelectedIndex(item);
            }
            blendSlider.setValue(weight * 100f);
        } finally {
            refilling = false;
        }
    }

    /**
     * Rebuilds the <b>colour table</b>: one editable row per colour of the loaded export's palette,
     * in the file's order, plus an optional note (why nothing is listed, or why edits do not show).
     *
     * <p>The colour instances are owned by the screen and edited in place by the picker; every
     * change is forwarded through
     * {@link Listener#paletteColorChanged(int, Color)} so the 3D view recolours live.</p>
     *
     * @param colors live colour per palette entry, in palette order (never {@code null})
     * @param titles row titles, same size and order as {@code colors}
     * @param note   hint shown above the rows, or {@code null} for none
     */
    public void setPalette(List<Color> colors, List<String> titles, String note) {
        paletteTable.clearChildren();
        if (note != null && !note.isEmpty()) {
            Label hint = new Label("  " + note, skin);
            hint.setColor(PALETTE_NOTE_COLOR);
            hint.setWrap(true);
            hint.setAlignment(Align.left);
            paletteTable.add(hint).colspan(COLUMNS).width(wrapWidth()).left().padTop(4f).row();
        }
        for (int i = 0; i < colors.size() && i < titles.size(); i++) {
            final int index = i;
            final Color color = colors.get(i);
            colorRow(paletteTable, "  " + titles.get(i), color,
                () -> listener.paletteColorChanged(index, color));
        }
        paletteTable.invalidateHierarchy();
    }

    /** Focuses the count field and selects its text, so typing replaces the current value. */
    public void focusInstanceCount() {
        Stage stage = getRoot().getStage();
        if (stage == null) return;
        stage.setKeyboardFocus(countField);
        countField.selectAll();
    }

    /** True while the count field owns the keyboard (the screen then skips world shortcuts). */
    public boolean hasTypingFocus() {
        Stage stage = getRoot().getStage();
        return stage != null && stage.getKeyboardFocus() == countField;
    }

    // ------------------------------------------------------------------ rows

    /** Adds a {@code label | text field | value} row and returns the value label. */
    private Label textFieldRow(String labelText, TextField field) {
        Label name = new Label(labelText, skin);
        Label value = new Label("", skin);
        value.setAlignment(Align.right);

        form().add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        form().add(field).width(UiLayout.FORM_WIDGET_WIDTH).height(30f).left();
        form().add(value).width(UiLayout.FORM_VALUE_WIDTH).right();
        form().row();
        return value;
    }

    /** Typing a count + Enter applies it; an invalid entry leaves the field untouched. */
    private void addCountListeners() {
        countField.setTextFieldListener((field, character) -> {
            if (refilling) return;
            if (character != '\n' && character != '\r') return;
            int applied = listener.instanceCountEntered(field.getText());
            if (applied < 0) return;
            setInstanceCount(applied);
        });
    }

    /** Adds a {@code label | select box | (empty value)} row and returns the select box. */
    private SelectBox<String> selectRow(String labelText, Array<String> items, int selected,
                                        IntConsumer onSelect) {
        Label name = new Label(labelText, skin);
        SelectBox<String> box = new SelectBox<>(skin);
        box.setItems(items);
        refilling = true;
        try {
            box.setSelectedIndex(selected);
        } finally {
            refilling = false;
        }
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (refilling) return;
                onSelect.accept(box.getSelectedIndex());
            }
        });

        form().add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        form().add(box).width(UiLayout.FORM_WIDGET_WIDTH).left();
        form().add().width(UiLayout.FORM_VALUE_WIDTH);
        form().row();
        return box;
    }

    // ------------------------------------------------------------------ label helpers

    /** Placement labels in {@link #PLACEMENTS} order. */
    private static Array<String> labels(InstanceField.Layout[] layouts) {
        Array<String> out = new Array<>(layouts.length);
        for (InstanceField.Layout layout : layouts) out.add(layout.label());
        return out;
    }

    /** {@code — none —} followed by the animation names (which may be {@code null}). */
    private static Array<String> blendLabels(List<String> names) {
        Array<String> out = new Array<>();
        out.add(BLEND_OFF_LABEL);
        if (names != null) {
            for (String name : names) out.add(name);
        }
        return out;
    }

    private static int indexOf(InstanceField.Layout layout) {
        for (int i = 0; i < PLACEMENTS.length; i++) {
            if (PLACEMENTS[i] == layout) return i;
        }
        return 0;
    }


}
