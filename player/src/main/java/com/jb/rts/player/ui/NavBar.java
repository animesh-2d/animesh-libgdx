package com.jb.rts.player.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;

import java.util.function.IntConsumer;

/**
 * The shared bottom-right navigation bar: an <b>export selector</b> (a drop-down of the files that
 * can be played, see {@link #setExports}), an optional <b>{@code Load…}</b> button and <b>Close</b>.
 *
 * <p>The {@link Table} fills the stage and is anchored bottom-right, so the bar stays in the
 * corner at every window size and fullscreen resolution — no manual pixel arithmetic that can
 * drift on resize. All sizes, gaps and labels come from {@link UiLayout}, so changing the look
 * of the bar in one place changes it on every screen that uses it.
 *
 * <p>Usage (any {@code ApplicationAdapter} / screen):
 * <pre>
 *   NavBar bar = new NavBar(skin)
 *           .setExports(fileNames, currentIndex, this::playFile)  // optional drop-down
 *           .onLoad(this::openFileDialog)   // optional — the button only appears when wired
 *           .onClose(() -&gt; Gdx.app.exit())
 *           .attachTo(stage);      // adds the bar to the stage
 * </pre>
 *
 * <p>Remember it only holds callbacks ({@link Runnable}s / an {@link IntConsumer}), never the
 * calling screen, so the same bar can be re-wired at any time via {@link #onLoad(Runnable)} /
 * {@link #onClose(Runnable)} and refilled through {@link #setExports(Array, int, IntConsumer)}.
 */
public final class NavBar {

    private final Table table;
    private final SelectBox<String> exportBox;
    private final TextButton loadButton;
    private final TextButton closeButton;

    /**
     * Cell of the export selector. Collapsed to width {@code 0} while no export list is set, so a
     * screen that only pages through items gets the classic three-button bar without a gap.
     */
    private final Cell<SelectBox<String>> exportCell;

    /**
     * Cell of the Load button. It is collapsed to width {@code 0} while no action is wired, so a
     * screen that does not load files gets the classic three-button bar without a gap.
     */
    private final Cell<TextButton> loadCell;

    /** Callbacks invoked by the buttons; {@code null} means "do nothing". */
    private IntConsumer exportAction;
    /** True while {@link #setExports} refills the list, so the refill cannot call back. */
    private boolean refillingExports;
    private Runnable loadAction;
    private Runnable closeAction;

    /** The standard bar with the toolkit's labels. */
    public NavBar(Skin skin) {
        this(skin, UiLayout.CLOSE_LABEL);
    }

    /**
     * A bar with a custom close label (same layout), for a screen whose exit button means something
     * else — e.g. {@code "Back"}.
     */
    public NavBar(Skin skin, String closeLabel) {
        table = new Table();
        table.setFillParent(true);
        table.bottom().right().pad(UiLayout.PADDING);

        // Export selector (drop-down). A refill must not report itself as a user pick, otherwise
        // a screen that refreshes the list after each load would loop back into a load.
        exportBox = new SelectBox<>(skin);
        exportBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (refillingExports || exportAction == null) return;
                exportAction.accept(exportBox.getSelectedIndex());
            }
        });

        loadButton = new TextButton(UiLayout.LOAD_LABEL, skin);
        closeButton = new TextButton(closeLabel, skin);

        loadButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (loadAction != null) loadAction.run();
            }
        });
        closeButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (closeAction != null) closeAction.run();
            }
        });

        // Hidden and 0 px wide until setExports(...) / onLoad(...) wire them.
        exportBox.setVisible(false);
        exportCell = table.add(exportBox).width(0f).height(UiLayout.BUTTON_HEIGHT).padRight(0f);
        loadButton.setVisible(false);
        loadCell = table.add(loadButton).width(0f).height(UiLayout.BUTTON_HEIGHT).padRight(0f);
        table.add(closeButton).width(UiLayout.CLOSE_BUTTON_WIDTH).height(UiLayout.BUTTON_HEIGHT);
        table.setVisible(true);
    }

    /**
     * Fills the optional <b>export selector</b> — a drop-down listing the files the screen can
     * play, leftmost in the bar. It appears as soon as a non-empty list is passed and disappears
     * again for {@code null} / an empty list, so a screen without files pays no width for it.
     *
     * <p>Only a pick made by the user calls {@code onSelect} (with the new index); refilling the
     * list with {@code selected} is silent, so a screen may call this from inside its own load
     * routine without recursing.
     *
     * @param labels     item labels (usually file names) in the order the screen knows them
     * @param selected   index of the active item, or {@code -1} for none
     * @param onSelect   called with the index the user picked; {@code null} disables the selector
     * @return this bar, for chaining
     */
    public NavBar setExports(Array<String> labels, int selected, IntConsumer onSelect) {
        exportAction = onSelect;
        boolean used = onSelect != null && labels != null && labels.size > 0;
        refillingExports = true;
        try {
            if (used) {
                exportBox.setItems(labels);
                if (selected >= 0 && selected < labels.size) exportBox.setSelectedIndex(selected);
            } else {
                exportBox.clearItems();
            }
        } finally {
            refillingExports = false;
        }
        exportBox.setVisible(used);
        exportCell.width(used ? UiLayout.EXPORT_SELECT_WIDTH : 0f)
                .padRight(used ? UiLayout.NAV_BUTTON_GAP : 0f);
        return this;
    }

    /**
     * Action for the optional <b>Load…</b> button. Passing {@code null} removes the button again;
     * a non-null action makes it appear as the leftmost button of the bar.
     */
    public NavBar onLoad(Runnable action) {
        loadAction = action;
        boolean used = action != null;
        loadButton.setVisible(used);
        loadCell.width(used ? UiLayout.LOAD_BUTTON_WIDTH : 0f)
                .padRight(used ? UiLayout.NAV_BUTTON_GAP : 0f);
        return this;
    }

    /** Action for {@code Close}. */
    public NavBar onClose(Runnable action) {
        closeAction = action;
        return this;
    }

    /** Adds the bar to {@code stage} (on top of everything drawn before it). */
    public NavBar attachTo(Stage stage) {
        if (stage != null) stage.addActor(table);
        return this;
    }

    /** Removes the bar from its stage again (no-op when it is not attached). */
    public NavBar detach() {
        table.remove();
        return this;
    }

    /** Shows/hides the whole bar (it keeps its stage parent while hidden). */
    public NavBar setVisible(boolean visible) {
        table.setVisible(visible);
        return this;
    }

    /** The table to add to a stage manually (equivalent to {@link #attachTo(Stage)}). */
    public Table actor() {
        return table;
    }

    public TextButton loadButton() {
        return loadButton;
    }

    public TextButton closeButton() {
        return closeButton;
    }
}
