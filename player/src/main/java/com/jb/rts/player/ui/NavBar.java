package com.jb.rts.player.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

/**
 * The shared bottom-right navigation bar: <b>{@code ◀ Prev} / {@code Next ▶} / {@code Close}</b>,
 * plus an optional <b>{@code Load…}</b> button on its left.
 *
 * <p>The {@link Table} fills the stage and is anchored bottom-right, so the bar stays in the
 * corner at every window size and fullscreen resolution — no manual pixel arithmetic that can
 * drift on resize. All sizes, gaps and labels come from {@link UiLayout}, so changing the look
 * of the bar in one place changes it on every screen that uses it.
 *
 * <p>Usage (any {@code ApplicationAdapter} / screen):
 * <pre>
 *   NavBar bar = new NavBar(skin)
 *           .onLoad(this::openFileDialog)   // optional — the button only appears when wired
 *           .onPrev(this::previous)
 *           .onNext(this::next)
 *           .onClose(() -&gt; Gdx.app.exit())
 *           .attachTo(stage);      // adds the bar to the stage
 *   ...
 *   bar.setNavEnabled(items.size() &gt; 1);   // dim Prev/Next when there is nothing to page
 * </pre>
 *
 * <p>Remembers only callbacks ({@link Runnable}s), never the calling screen, so the same bar
 * can be re-wired at any time via {@link #onLoad(Runnable)} / {@link #onPrev(Runnable)} /
 * {@link #onNext(Runnable)} / {@link #onClose(Runnable)}.
 */
public final class NavBar {

    private final Table table;
    private final TextButton loadButton;
    private final TextButton prevButton;
    private final TextButton nextButton;
    private final TextButton closeButton;

    /**
     * Cell of the Load button. It is collapsed to width {@code 0} while no action is wired, so a
     * screen that does not load files gets the classic three-button bar without a gap.
     */
    private final Cell<TextButton> loadCell;

    /** Callbacks invoked by the buttons; {@code null} means "do nothing". */
    private Runnable loadAction;
    private Runnable prevAction;
    private Runnable nextAction;
    private Runnable closeAction;

    /** The standard bar with the toolkit's default labels. */
    public NavBar(Skin skin) {
        this(skin, UiLayout.PREV_LABEL, UiLayout.NEXT_LABEL, UiLayout.CLOSE_LABEL);
    }

    /**
     * A bar with custom labels (same layout), for screens whose "prev/next" means something
     * else — e.g. {@code "< File"} / {@code "File >"}.
     */
    public NavBar(Skin skin, String prevLabel, String nextLabel, String closeLabel) {
        table = new Table();
        table.setFillParent(true);
        table.bottom().right().pad(UiLayout.PADDING);

        loadButton = new TextButton(UiLayout.LOAD_LABEL, skin);
        prevButton = new TextButton(prevLabel, skin);
        nextButton = new TextButton(nextLabel, skin);
        closeButton = new TextButton(closeLabel, skin);

        loadButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (loadAction != null) loadAction.run();
            }
        });
        prevButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (prevAction != null) prevAction.run();
            }
        });
        nextButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (nextAction != null) nextAction.run();
            }
        });
        closeButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (closeAction != null) closeAction.run();
            }
        });

        // Hidden and 0 px wide until onLoad(...) wires an action.
        loadButton.setVisible(false);
        loadCell = table.add(loadButton).width(0f).height(UiLayout.BUTTON_HEIGHT).padRight(0f);
        table.add(prevButton).width(UiLayout.NAV_BUTTON_WIDTH).height(UiLayout.BUTTON_HEIGHT)
                .padRight(UiLayout.NAV_BUTTON_GAP);
        table.add(nextButton).width(UiLayout.NAV_BUTTON_WIDTH).height(UiLayout.BUTTON_HEIGHT);
        table.add(closeButton).width(UiLayout.CLOSE_BUTTON_WIDTH).height(UiLayout.BUTTON_HEIGHT)
                .padLeft(UiLayout.CLOSE_BUTTON_GAP);
        table.setVisible(true);
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

    /** Action for {@code ◀ Prev}. */
    public NavBar onPrev(Runnable action) {
        prevAction = action;
        return this;
    }

    /** Action for {@code Next ▶}. */
    public NavBar onNext(Runnable action) {
        nextAction = action;
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

    /**
     * Enables ({@code true}) or dims/disables ({@code false}) the Prev / Next buttons — with
     * only one item to page through they are useless, so they are disabled and drawn at
     * {@link UiLayout#DISABLED_ALPHA}. <b>Close</b> always stays enabled.
     */
    public NavBar setNavEnabled(boolean enabled) {
        prevButton.setDisabled(!enabled);
        nextButton.setDisabled(!enabled);
        float alpha = enabled ? 1f : UiLayout.DISABLED_ALPHA;
        prevButton.setColor(1f, 1f, 1f, alpha);
        nextButton.setColor(1f, 1f, 1f, alpha);
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

    public TextButton prevButton() {
        return prevButton;
    }

    public TextButton nextButton() {
        return nextButton;
    }

    public TextButton closeButton() {
        return closeButton;
    }
}
