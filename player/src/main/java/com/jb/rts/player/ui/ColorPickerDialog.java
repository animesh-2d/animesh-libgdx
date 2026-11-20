package com.jb.rts.player.ui;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Window;

/**
 * Modal dialog around the {@link ColorPicker}, the shared Scene2D colour editor of the player's
 * UI toolkit.
 *
 * <p>The edited colour is <b>applied live</b> while the user drags in the picker, so any swatch
 * or value label the caller keeps in sync via the {@code onChanged} callback follows immediately.
 * <b>Cancel</b> (or ESC) restores the colour that was active when the dialog opened;
 * <b>Apply</b> (or ENTER) keeps the current one. The dialog only edits the {@link Color}
 * instance it was handed — what the owner does with it (and when it takes effect) is up to the
 * caller.</p>
 */
public final class ColorPickerDialog extends Dialog {

    /** Style names tried in order (a custom skin may not register the {@code dialog} style). */
    private static final String[] WINDOW_STYLES = {"dialog", "default"};

    private final Color target;
    private final Color original;
    private final Runnable onChanged;

    private ColorPickerDialog(Skin skin, String title, Color color, Runnable onChanged) {
        super(title, skin, windowStyle(skin));
        this.target = color;
        this.original = new Color(color);
        this.onChanged = onChanged;

        getContentTable().pad(14f);
        getContentTable().add(new ColorPicker(skin, color, this::apply));
        getButtonTable().defaults().width(150f).height(42f).pad(4f);
        button("Apply", Boolean.TRUE);
        button("Cancel", Boolean.FALSE);
        key(Keys.ENTER, Boolean.TRUE);
        key(Keys.ESCAPE, Boolean.FALSE);
        setMovable(true);
        setResizable(false);
    }

    /**
     * Opens the picker as a modal dialog on {@code stage}.
     *
     * @param skin      skin for the dialog, the picker and the buttons
     * @param title     dialog title, e.g. {@code "Innere Farbe"}
     * @param color     the live colour to edit (mutated while the user drags)
     * @param onChanged called after every change — use it to refresh swatch / hex labels
     */
    public static void open(Stage stage, Skin skin, String title, Color color,
                            Runnable onChanged) {
        if (stage == null || skin == null || color == null) return;
        new ColorPickerDialog(skin, title, color, onChanged).show(stage);
    }

    /** Live update: the picker's colour is copied into the config's live colour. */
    private void apply(Color picked) {
        target.set(picked);
        if (onChanged != null) onChanged.run();
    }

    /** {@code true} = Apply (already applied live), {@code false} = Cancel (restore). */
    @Override
    protected void result(Object object) {
        if (!Boolean.TRUE.equals(object)) {
            target.set(original);
            if (onChanged != null) onChanged.run();
        }
    }

    private static String windowStyle(Skin skin) {
        for (String name : WINDOW_STYLES) {
            if (skin.has(name, Window.WindowStyle.class)) return name;
        }
        throw new IllegalStateException("skin has no WindowStyle (tried dialog / default)");
    }
}
