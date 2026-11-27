package com.jb.rts.player.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.jb.rts.player.AnimationRule;
import com.jb.rts.player.PlayerAnimationFsm;

import java.util.List;

/**
 * The <b>FSM section</b> of the mesh player's parameter panel: a state trigger button per loaded
 * animation, the crossfade spinner, the playback transport ({@code Pause}, {@code +1 Frame},
 * {@code Reset}, time scale) and the live crossfade readout.
 *
 * <p>Split out of {@link MeshPlayerControls} so the panel stays readable. It is a plain
 * {@link Table} the panel adds as one block; it holds callbacks only, never the screen or the
 * machine — {@link Listener} is the whole contract, and {@link #sync} mirrors the machine into the
 * widgets without firing them.</p>
 */
public final class FsmControlsSection extends Table {

    /** Every user action of the FSM section. */
    public interface Listener {
        /**
         * A state trigger button was pressed.
         *
         * @param index index into the loaded animation list
         */
        void stateRequested(int index);

        /** The crossfade spinner changed (seconds). */
        void fadeSecondsChanged(float seconds);

        /** Pause / Resume was pressed. */
        void pauseToggled();

        /** {@code +1 Frame} was pressed. */
        void stepFrameRequested();

        /** The time-scale slider moved ({@code 0..2}). */
        void timeScaleChanged(float scale);

        /** Reset was pressed: the machine returns to its start state. */
        void resetRequested();
    }

    /** Colour of the active state's button (same green as the graph node). */
    private static final Color ACTIVE_COLOR = new Color(0.62f, 0.95f, 0.68f, 1f);

    private final Listener listener;
    /** The skin the section was built with ({@code Table#skin} itself is private). */
    private final Skin uiSkin;
    /** The grid the state buttons are laid out in; rebuilt per loaded file. */
    private final Table buttonGrid;
    /** One trigger button per animation, in animation order. */
    private final Array<TextButton> buttons = new Array<>();

    private final NumberSpinner fadeSpinner;
    private final Slider timeScaleSlider;
    private final TextButton pauseButton;
    private final Label readout;
    private final Label ruleLine;

    /** True while {@link #sync} refills widgets, so no listener fires. */
    private boolean refilling;

    /**
     * @param skin     the shared toolkit skin
     * @param listener the callbacks into the screen
     */
    public FsmControlsSection(Skin skin, Listener listener) {
        super(skin);
        if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
        this.listener = listener;
        this.uiSkin = skin;
        top().left();
        defaults().left();

        buttonGrid = new Table(uiSkin);
        buttonGrid.top().left();
        add(buttonGrid).growX().row();

        fadeSpinner = spinnerRow("  Crossfade (s)", PlayerAnimationFsm.MAX_FADE_SECONDS,
            PlayerAnimationFsm.FADE_STEP_SECONDS, 1,
            PlayerAnimationFsm.DEFAULT_FADE_SECONDS, listener::fadeSecondsChanged);

        Table transport = new Table(uiSkin);
        pauseButton = transportButton(transport, "Pause", true, this::togglePause);
        transportButton(transport, "+1 Frame", true, listener::stepFrameRequested);
        transportButton(transport, "Reset", false, listener::resetRequested);
        add(transport).growX().padTop(6f).row();

        timeScaleSlider = sliderRow("  Time scale (x)", 0f, 2f, 1f, false,
            listener::timeScaleChanged);

        readout = new Label("", uiSkin);
        readout.setWrap(true);
        readout.setAlignment(Align.left);
        add(readout).growX().padTop(6f).row();

        ruleLine = new Label("", uiSkin);
        ruleLine.setColor(UiLayout.HUD_STAT_COLOR);
        ruleLine.setWrap(true);
        ruleLine.setAlignment(Align.left);
        add(ruleLine).growX().padTop(4f).row();
    }

    /** Builds one transport button; {@code padRight} adds the gap to its right neighbour. */
    private TextButton transportButton(Table table, String text, boolean padRight,
                                       Runnable action) {
        TextButton button = new TextButton(text, uiSkin);
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (refilling) return;
                action.run();
            }
        });
        table.add(button).height(UiLayout.BUTTON_HEIGHT).growX();
        if (padRight) table.getCells().peek().padRight(6f);
        return button;
    }

    private void togglePause() {
        listener.pauseToggled();
    }

    /**
     * Adds one {@code label | spinner | unit} row — the numeric entry used where an exact value
     * matters (the crossfade length: 0 – 60 s in 100 ms steps).
     *
     * @param labelText the row's label
     * @param max       highest accepted value
     * @param step      increment of one button press
     * @param decimals  digits shown in the field
     * @param value     initial value
     * @param onChange  receives the new value
     */
    private NumberSpinner spinnerRow(String labelText, float max, float step, int decimals,
                                     float value, FloatSink onChange) {
        Label name = new Label(labelText, uiSkin);
        NumberSpinner spinner = new NumberSpinner(uiSkin, 0f, max, step, decimals, value,
            onChange::accept);
        add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        add(spinner).width(UiLayout.FORM_WIDGET_WIDTH).left();
        add().width(UiLayout.FORM_VALUE_WIDTH);
        row();
        return spinner;
    }

    /**
     * Adds one {@code label | slider | value} row and forwards every move to the caller.
     *
     * @param labelText the row's label
     * @param min       slider minimum
     * @param max       slider maximum
     * @param value     initial value
     * @param integer   {@code true} to show the value as a whole number
     * @param onChange  receives the new value
     */
    private Slider sliderRow(String labelText, float min, float max, float value,
                             boolean integer, FloatSink onChange) {
        Label name = new Label(labelText, uiSkin);
        Slider slider = new Slider(min, max, 0.01f, false,
            ControlPanel.sliderStyle(uiSkin));
        slider.setValue(value);
        Label valueLabel = new Label(format(value, integer), uiSkin);
        valueLabel.setAlignment(Align.right);

        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                float v = slider.getValue();
                valueLabel.setText(format(v, integer));
                if (refilling) return;
                onChange.accept(v);
            }
        });

        add(name).width(UiLayout.FORM_LABEL_WIDTH).left();
        add(slider).width(UiLayout.FORM_WIDGET_WIDTH).left();
        add(valueLabel).width(UiLayout.FORM_VALUE_WIDTH).right();
        row();
        return slider;
    }

    /** Formats a slider value the way the rest of the panel does. */
    private static String format(float value, boolean integer) {
        return integer ? Integer.toString(Math.round(value))
            : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Receives a slider value (avoids importing a java.util.function type just for this). */
    private interface FloatSink {
        void accept(float value);
    }

    // ------------------------------------------------------------------ state mirroring

    /**
     * Rebuilds one trigger button per animation (called when a file loads).
     *
     * <p>The buttons are laid out three per row and labelled with the animation name, so
     * {@code Idle Walk Run â€¦} reads like the loaded file's clip list. {@code 1}..{@code 9} on the
     * keyboard press the first nine of them.</p>
     *
     * @param names the loaded animations, in animation order ({@code null} clears the grid)
     */
    public void setStates(List<String> names) {
        buttons.clear();
        buttonGrid.clearChildren();
        if (names == null) return;
        int perRow = 3;
        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            String label = (i + 1) + "  " + names.get(i);
            TextButton button = new TextButton(label, uiSkin);
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (refilling) return;
                    listener.stateRequested(index);
                }
            });
            buttonGrid.add(button).height(UiLayout.BUTTON_HEIGHT).growX().pad(2f);
            if ((i + 1) % perRow == 0) buttonGrid.row();
            buttons.add(button);
        }
    }

    /** Number of trigger buttons currently shown. */
    public int stateButtonCount() {
        return buttons.size;
    }

    /**
     * Presses the trigger button of state {@code index} as if the user clicked it — the callback of
     * the {@code 1}..{@code 9} hotkeys.
     *
     * @param index index into the loaded animation list
     * @return {@code false} when there is no such button
     */
    public boolean pressState(int index) {
        if (index < 0 || index >= buttons.size) return false;
        listener.stateRequested(index);
        return true;
    }

    /**
     * Mirrors the machine into the widgets without firing a callback: active/legal/illegal button
     * tints, the crossfade spinner, the time-scale slider, the pause label and the readouts.
     *
     * <p>Call it once per frame from the screen; it only touches the widgets when a value actually
     * changed, so it costs nothing while the machine is idle.</p>
     *
     * @param fsm the machine to mirror (may be {@code null} when nothing is loaded)
     */
    public void sync(PlayerAnimationFsm fsm) {
        if (fsm == null) return;
        refilling = true;
        try {
            boolean[] allowed = fsm.allowedTargets(null);
            for (int i = 0; i < buttons.size && i < fsm.stateCount(); i++) {
                TextButton button = buttons.get(i);
                Color tint;
                if (i == fsm.currentIndex()) {
                    tint = ACTIVE_COLOR;
                } else {
                    // Same coding as the graph: green = the rule allows it, red = it does not.
                    tint = allowed[i] ? UiLayout.FSM_LEGAL_COLOR : UiLayout.FSM_ILLEGAL_COLOR;
                }
                button.getLabel().setColor(tint);
            }
            fadeSpinner.setValue(fsm.fadeSeconds());
            timeScaleSlider.setValue(fsm.timeScale());
            pauseButton.setText(fsm.isPaused() ? "Resume" : "Pause");
            readout.setText(readoutText(fsm));
            ruleLine.setText(ruleLineText(fsm));
        } finally {
            refilling = false;
        }
    }

    /** {@code "Current State  Â·  Outgoing -> Incoming 42%  Â·  0.31 / 1.00 s"}. */
    private static String readoutText(PlayerAnimationFsm fsm) {
        String current = fsm.current() != null ? fsm.current().name() : "-";
        StringBuilder text = new StringBuilder(current);
        if (fsm.outgoing() != null) {
            text.append("  \u2190  ").append(fsm.outgoing().name())
                .append(' ').append(Math.round(fsm.crossfadeProgress() * 100f)).append('%');
        }
        text.append(String.format(java.util.Locale.ROOT, "  \u00b7  %.2f / %.2f s",
            fsm.localTime(), fsm.currentDuration()));
        return text.toString();
    }

    /** The rule of the state on screen, plus a note when it was overridden by hand. */
    private static String ruleLineText(PlayerAnimationFsm fsm) {
        int index = fsm.currentIndex();
        if (index < 0) return "";
        AnimationRule rule = fsm.rule(index);
        String text = "Rule: " + rule.label();
        if (fsm.hasRuleOverride(index)) text += "  (overridden)";
        return text + "  \u00b7  every state can be entered";
    }
}


