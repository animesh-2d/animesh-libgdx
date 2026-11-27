package com.jb.rts.player.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;

import java.util.Locale;

/**
 * A small <b>numeric spinner</b> for a panel form row: {@code [-] 0.3 [+]}, where the value can be
 * stepped with the two buttons or typed into the field.
 *
 * <p>It replaces a slider where the exact value matters and the range is large: the mesh player's
 * crossfade length runs from {@code 0} to {@code 60 s} in {@code 100 ms} steps, which a slider
 * cannot hit reliably. Every value the widget reports is <b>snapped</b> onto the step grid and
 * clamped to {@code [min, max]}, so the model behind it can never receive an off-grid value.</p>
 *
 * <p>The widget holds a callback only, never the screen. {@link #setValue(float)} mirrors a value
 * without firing it, so a screen may push the model back into the widget every frame.</p>
 *
 * <p>The arithmetic lives in the static helpers ({@link #snap}, {@link #parse}, {@link #format}),
 * which are free of GL and Scene2D state, so the behaviour is unit-tested headless
 * ({@code NumberSpinnerTest}).</p>
 */
public final class NumberSpinner extends Table {

    /** Receives every value the user set (already snapped and clamped). */
    public interface Listener {
        /** @param value the new value, inside {@code [min, max]} and on the step grid */
        void valueChanged(float value);
    }

    /** Glyph of the decrement button (a real minus sign, not a hyphen). */
    private static final String MINUS = "\u2212";
    /** Glyph of the increment button. */
    private static final String PLUS = "+";

    private final Listener listener;
    private final float min;
    private final float max;
    private final float step;
    private final int decimals;
    private final TextField field;

    /** The value the widget currently shows — always snapped and clamped. */
    private float value;
    /** True while the widget refills itself, so its own listeners never fire. */
    private boolean refilling;

    /**
     * @param skin     the shared toolkit skin
     * @param min      lowest accepted value
     * @param max      highest accepted value
     * @param step     increment of one button press (and the grid typed values snap to)
     * @param decimals digits shown in the field
     * @param value    initial value (snapped and clamped)
     * @param listener callbacks into the screen
     */
    public NumberSpinner(Skin skin, float min, float max, float step, int decimals, float value,
                         Listener listener) {
        super(skin);
        if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
        if (!(step > 0f)) throw new IllegalArgumentException("step must be positive, not " + step);
        if (max < min) throw new IllegalArgumentException("max (" + max + ") is below min (" + min + ")");
        this.listener = listener;
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = Math.max(0, decimals);
        this.value = snap(value, min, max, step, this.decimals);

        top().left();
        defaults().left();

        add(stepButton(MINUS, -1f)).width(UiLayout.SPINNER_BUTTON_WIDTH)
            .height(UiLayout.SPINNER_HEIGHT).padRight(UiLayout.SPINNER_GAP);

        field = new TextField(format(this.value, this.decimals), skin);
        field.setAlignment(Align.center);
        field.setMaxLength(8);
        field.setTextFieldFilter(new NumberFilter());
        field.setTextFieldListener((textField, character) -> {
            if (character == '\n' || character == '\r') commitField();
        });
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                // Typing and then clicking elsewhere must not leave an unapplied number behind.
                if (!focused) commitField();
            }
        });
        add(field).width(UiLayout.SPINNER_FIELD_WIDTH).height(UiLayout.SPINNER_HEIGHT)
            .padRight(UiLayout.SPINNER_GAP);

        add(stepButton(PLUS, 1f)).width(UiLayout.SPINNER_BUTTON_WIDTH)
            .height(UiLayout.SPINNER_HEIGHT);
    }

    /** One of the two {@code -}/{@code +} buttons; {@code direction} is {@code -1} or {@code +1}. */
    private TextButton stepButton(String text, float direction) {
        TextButton button = new TextButton(text, getSkin());
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (refilling) return;
                apply(value + direction * step);
            }
        });
        return button;
    }

    /** The current value (snapped and clamped). */
    public float getValue() {
        return value;
    }

    /**
     * Shows {@code newValue} without notifying the listener — for mirroring the model into the
     * widget (the screen calls this every frame).
     *
     * @param newValue the value to show; snapped and clamped like any other
     */
    public void setValue(float newValue) {
        float snapped = snap(newValue, min, max, step, decimals);
        if (snapped == value) return;
        value = snapped;
        refilling = true;
        try {
            field.setText(format(value, decimals));
        } finally {
            refilling = false;
        }
    }

    /** Applies a user value: snap, store, show, notify. */
    private void apply(float newValue) {
        float snapped = snap(newValue, min, max, step, decimals);
        boolean changed = snapped != value;
        value = snapped;
        refilling = true;
        try {
            field.setText(format(value, decimals));
        } finally {
            refilling = false;
        }
        if (changed) listener.valueChanged(value);
    }

    /** Reads the typed text; an unparsable entry simply restores the current value. */
    private void commitField() {
        if (refilling) return;
        float parsed = parse(field.getText());
        if (Float.isNaN(parsed)) {
            refilling = true;
            try {
                field.setText(format(value, decimals));
            } finally {
                refilling = false;
            }
            return;
        }
        apply(parsed);
    }

    // ------------------------------------------------------------------ arithmetic (GL-free)

    /**
     * Clamps {@code value} to {@code [min, max]} and snaps it onto the {@code step} grid.
     *
     * @param value    the raw value (may be outside the range)
     * @param min      lowest accepted value
     * @param max      highest accepted value
     * @param step     grid distance (must be positive)
     * @param decimals digits the result is rounded to, so {@code 0.30000001} becomes {@code 0.3}
     * @return a value inside the range that is a multiple of {@code step}
     */
    static float snap(float value, float min, float max, float step, int decimals) {
        float clamped = Math.max(min, Math.min(max, value));
        float snapped = Math.round(clamped / step) * step;
        double scale = Math.pow(10d, Math.max(0, decimals));
        return (float) (Math.round(snapped * scale) / scale);
    }

    /**
     * Parses a typed number, accepting both {@code .} and {@code ,} as the decimal separator.
     *
     * @param text the field text
     * @return the number, or {@link Float#NaN} when the text is blank or not a number
     */
    static float parse(String text) {
        if (text == null) return Float.NaN;
        String trimmed = text.trim().replace(',', '.');
        if (trimmed.isEmpty()) return Float.NaN;
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** Formats {@code value} with {@code decimals} digits, using {@code .} as the separator. */
    static String format(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    /** Accepts digits, one decimal separator and a leading minus sign. */
    private static final class NumberFilter implements TextField.TextFieldFilter {
        @Override
        public boolean acceptChar(TextField textField, char c) {
            if (Character.isDigit(c)) return true;
            if (c == '.' || c == ',') {
                return textField.getText().indexOf('.') < 0 && textField.getText().indexOf(',') < 0;
            }
            // A minus sign is only meaningful in front of the number.
            return c == '-' && textField.getCursorPosition() == 0;
        }
    }
}
