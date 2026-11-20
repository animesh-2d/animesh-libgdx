package com.jb.rts.player.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

/**
 * Builds the {@link Skin} shared by every screen of the toolkit ({@link NavBar},
 * {@link HudOverlay}, the {@link NavBar}, …).
 *
 * <p>If a real skin is provided it is loaded from the internal path
 * <b>{@code skin/uiskin.json}</b> (place it in the repo-root {@code assets/}, so it resolves
 * via {@code Gdx.files.internal}). Every screen works out of the box via a small
 * <b>programmatic fallback skin</b> (a 1-px white texture + the <b>Noto Sans</b> TrueType
 * font generated with <b>gdx-freetype</b>, falling back to the arial font bundled in the gdx
 * jar when the font file / natives are missing).
 *
 * <p>All widget styles are registered under the conventional name {@code "default"}, matching
 * libGDX's standard {@code uiskin.json}.
 *
 * <p>Layout (positions, sizes, scales, HUD colours) is <b>not</b> part of the skin — see
 * {@link UiLayout}, the single source of truth for the chrome that {@link NavBar} and
 * {@link HudOverlay} draw.
 */
public final class UiSkin {

    private UiSkin() {}

    /** Bundle path of the Noto Sans TrueType font (repo-root {@code assets/fonts}). */
    public static final String NOTO_FONT_PATH = "fonts/NotoSans-Regular.ttf";

    /** Characters beyond FreeType's ASCII default that the UI strings use. */
    private static final String EXTRA_CHARS = "äöüÄÖÜß°…·←→↑↓◀▶▲▼–—‘’„“";

    /**
     * A {@link BitmapFont} generated from the bundled Noto Sans via gdx-freetype. Falls back
     * to the arial-15 font bundled in the gdx jar when the TTF is missing or the FreeType
     * natives are unavailable, so the screens still run without the asset.
     */
    public static BitmapFont load(int size, Color color) {
        FreeTypeFontGenerator generator = null;
        try {
            if (Gdx.files.internal(NOTO_FONT_PATH).exists()) {
                generator = new FreeTypeFontGenerator(Gdx.files.internal(NOTO_FONT_PATH));
                FreeTypeFontParameter p = new FreeTypeFontParameter();
                p.size = size;
                p.color = color;
                p.kerning = true;
                p.minFilter = Texture.TextureFilter.Linear;
                p.magFilter = Texture.TextureFilter.Linear;
                p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + EXTRA_CHARS;
                return generator.generateFont(p);
            }
        } catch (Throwable t) {
            Gdx.app.log("UiSkin", "FreeType/Noto font failed (" + t.getMessage()
                + ") — using arial fallback");
        } finally {
            if (generator != null) generator.dispose();
        }
        return new BitmapFont();
    }

    /**
     * Loads {@code skin/uiskin.json} if present, otherwise builds the programmatic fallback.
     *
     * <p>The example skin references the fonts by name; gdx cannot generate FreeType fonts from
     * JSON, so the Noto fonts are injected here BEFORE the styles are read (the styles resolve
     * the names from the skin's resources). A sibling {@code uiskin.atlas} provides the drawable
     * regions — the 1×1 {@code white} region that the {@code Skin$TintedDrawable}s tint.</p>
     */
    public static Skin load() {
        FileHandle json = Gdx.files.internal("skin/uiskin.json");
        if (json.exists()) {
            try {
                Skin skin = new Skin();
                FileHandle atlasFile = Gdx.files.internal("skin/uiskin.atlas");
                if (atlasFile.exists()) skin.addRegions(new TextureAtlas(atlasFile));
                // Fonts first — the json styles reference these names.
                skin.add("default-font", load(15, Color.WHITE), BitmapFont.class);
                skin.add("title-font",   load(24, Color.WHITE), BitmapFont.class);
                skin.load(json);
                applyWidgetMetrics(skin);
                applyWindowTitleHeight(skin);
                Gdx.app.log("UiSkin", "skin/uiskin.json loaded");
                return skin;
            } catch (Exception e) {
                Gdx.app.log("UiSkin", "skin/uiskin.json failed (" + e.getMessage()
                    + ") — using programmatic fallback skin");
            }
        } else {
            Gdx.app.log("UiSkin", "no skin/uiskin.json — using programmatic fallback skin");
        }
        return makeDefault();
    }

    /** A 2×2 white texture, the base for every tinted drawable. */
    private static Texture whiteTexture() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }

    /**
     * A white drawable tinted to {@code c}. {@link TextureRegionDrawable#tint(Color)} returns a
     * NEW {@link Drawable} (a {@code SpriteDrawable} in gdx 1.13 — never cast it back to
     * {@code TextureRegionDrawable}) and does not mutate the original; its min size must be
     * re-applied, otherwise every background stays white.
     */
    private static Drawable tinted(Texture white, Color c) {
        TextureRegionDrawable base = new TextureRegionDrawable(new TextureRegion(white));
        base.setMinWidth(2);
        base.setMinHeight(2);
        Drawable tinted = base.tint(c);
        tinted.setMinWidth(2);
        tinted.setMinHeight(2);
        return tinted;
    }

    private static Drawable tinted(Texture white, float r, float g, float b, float a) {
        return tinted(white, new Color(r, g, b, a));
    }

    /** Builds a functional minimal skin with {@code "default"} styles for every widget used. */
    public static Skin makeDefault() {
        Skin skin = new Skin();

        BitmapFont font      = load(15, Color.WHITE);   // widgets (Noto Sans via FreeType)
        BitmapFont titleFont = load(24, Color.WHITE);      // section/win titles
        skin.add("default-font", font, BitmapFont.class);
        skin.add("default", font, BitmapFont.class);
        skin.add("title-font", titleFont, BitmapFont.class);

        Texture white = whiteTexture();
        skin.add("white", white);

        // Label
        skin.add("default", new Label.LabelStyle(font, Color.WHITE));
        skin.add("title", new Label.LabelStyle(titleFont, new Color(0.9f, 0.93f, 1f, 1f)));

        // Window — dark grey panel so the form reads clearly; `dialog` for Dialog(...).
        {
            Window.WindowStyle ws = new Window.WindowStyle();
            ws.titleFont = font;
            ws.titleFontColor = Color.WHITE;
            ws.background = tinted(white, 0.18f, 0.18f, 0.18f, 1f);
            skin.add("default", ws);

            Window.WindowStyle dialog = new Window.WindowStyle();
            dialog.titleFont = titleFont;
            dialog.titleFontColor = new Color(0.9f, 0.93f, 1f, 1f);
            dialog.background = tinted(white, 0.16f, 0.17f, 0.20f, 1f);
            skin.add("dialog", dialog);
        }

        // Button — image-less base style (also used by Button groups / toggles).
        Button.ButtonStyle bs = new Button.ButtonStyle();
        bs.up = tinted(white, 0.30f, 0.30f, 0.30f, 1f);
        bs.over = tinted(white, 0.40f, 0.40f, 0.40f, 1f);
        bs.down = tinted(white, 0.22f, 0.22f, 0.22f, 1f);
        bs.checked = tinted(white, 0.24f, 0.34f, 0.50f, 1f);
        bs.checkedOver = tinted(white, 0.28f, 0.40f, 0.58f, 1f);
        bs.disabled = tinted(white, 0.20f, 0.20f, 0.20f, 0.55f);
        skin.add("default", bs);

        // TextButton — dark grey (up/over/down) + checked/disabled so toggles work.
        {
            TextButton.TextButtonStyle tb = new TextButton.TextButtonStyle();
            tb.font = font;
            tb.fontColor = Color.WHITE;
            tb.downFontColor = Color.LIGHT_GRAY;
            tb.checkedFontColor = new Color(0.35f, 0.62f, 0.95f, 1f);
            tb.disabledFontColor = new Color(0.45f, 0.47f, 0.52f, 1f);
            tb.up = tinted(white, 0.30f, 0.30f, 0.30f, 1f);
            tb.over = tinted(white, 0.40f, 0.40f, 0.40f, 1f);
            tb.down = tinted(white, 0.22f, 0.22f, 0.22f, 1f);
            tb.checked = tinted(white, 0.24f, 0.34f, 0.50f, 1f);
            tb.checkedOver = tinted(white, 0.28f, 0.40f, 0.58f, 1f);
            tb.disabled = tinted(white, 0.20f, 0.20f, 0.20f, 0.55f);
            skin.add("default", tb);

            // `toggle` is the same look, but named so a caller can say so explicitly:
            //   new TextButton("Audio", skin, "toggle") + setChecked(true)
            TextButton.TextButtonStyle toggle = new TextButton.TextButtonStyle(tb);
            skin.add("toggle", toggle);
        }

        // TextField
        {
            TextField.TextFieldStyle tf = new TextField.TextFieldStyle();
            tf.font = font;
            tf.fontColor = Color.WHITE;
            tf.messageFontColor = Color.GRAY;
            tf.background = tinted(white, 0.16f, 0.16f, 0.16f, 1f);
            tf.focusedBackground = tinted(white, 0.22f, 0.22f, 0.22f, 1f);
            tf.cursor = tinted(white, Color.WHITE);
            tf.selection = tinted(white, 0.30f, 0.40f, 0.60f, 1f);
            skin.add("default", tf);
        }

        // Slider — the standard scene2d names ("default-horizontal"/"default-vertical") plus the
        // short aliases older code uses. Horizontal and vertical get SEPARATE drawables: the
        // track thickness differs per orientation and applyWidgetMetrics() sizes the instances.
        {
            Slider.SliderStyle h = new Slider.SliderStyle();
            h.background = tinted(white, 0.14f, 0.15f, 0.18f, 1f);
            h.knobBefore = tinted(white, 0.34f, 0.52f, 0.76f, 1f);
            h.knob = tinted(white, 0.82f, 0.88f, 0.97f, 1f);
            h.knobOver = tinted(white, 0.95f, 0.97f, 1f, 1f);
            h.knobDown = tinted(white, 0.62f, 0.76f, 0.95f, 1f);
            skin.add("default-horizontal", h);
            skin.add("default-h", h);
            skin.add("default", h);

            Slider.SliderStyle v = new Slider.SliderStyle();
            v.background = tinted(white, 0.14f, 0.15f, 0.18f, 1f);
            v.knobBefore = tinted(white, 0.34f, 0.52f, 0.76f, 1f);
            v.knob = tinted(white, 0.82f, 0.88f, 0.97f, 1f);
            v.knobOver = tinted(white, 0.95f, 0.97f, 1f, 1f);
            v.knobDown = tinted(white, 0.62f, 0.76f, 0.95f, 1f);
            skin.add("default-vertical", v);
        }

        // ProgressBar — same standard names; knobBefore is the filled part (no knob: the bar
        // reads as a solid fill), separate instances per orientation for the thickness.
        {
            ProgressBar.ProgressBarStyle ph = new ProgressBar.ProgressBarStyle();
            ph.background = tinted(white, 0.16f, 0.17f, 0.20f, 1f);
            ph.knobBefore = tinted(white, 0.30f, 0.48f, 0.70f, 1f);
            skin.add("default-horizontal", ph);
            skin.add("default", ph);

            ProgressBar.ProgressBarStyle pv = new ProgressBar.ProgressBarStyle();
            pv.background = tinted(white, 0.16f, 0.17f, 0.20f, 1f);
            pv.knobBefore = tinted(white, 0.30f, 0.48f, 0.70f, 1f);
            skin.add("default-vertical", pv);
        }

        // CheckBox — radio groups are CheckBox + ButtonGroup (scene2d has no RadioButton class).
        {
            CheckBox.CheckBoxStyle cb = new CheckBox.CheckBoxStyle();
            cb.font = font;
            cb.fontColor = Color.WHITE;
            cb.disabledFontColor = new Color(0.45f, 0.47f, 0.52f, 1f);
            cb.checkboxOn = tinted(white, 0.32f, 0.72f, 0.45f, 1f);
            cb.checkboxOff = tinted(white, 0.24f, 0.28f, 0.38f, 1f);
            cb.checkboxOver = tinted(white, 0.34f, 0.39f, 0.52f, 1f);
            cb.checkboxOnOver = tinted(white, 0.36f, 0.78f, 0.50f, 1f);
            cb.checkboxOnDisabled = tinted(white, 0.22f, 0.45f, 0.30f, 1f);
            cb.checkboxOffDisabled = tinted(white, 0.20f, 0.22f, 0.28f, 1f);
            skin.add("default", cb);
        }

        // ScrollPane + List + SelectBox (multi-part, registered on the same skin)
        {
            ScrollPane.ScrollPaneStyle sp = new ScrollPane.ScrollPaneStyle();
            sp.background = tinted(white, 0.15f, 0.15f, 0.15f, 1f);
            // Separate instances: a vertical and a horizontal bar need different min sizes.
            sp.vScroll = tinted(white, 0.26f, 0.31f, 0.42f, 1f);
            sp.vScrollKnob = tinted(white, 0.52f, 0.62f, 0.82f, 1f);
            sp.hScroll = tinted(white, 0.26f, 0.31f, 0.42f, 1f);
            sp.hScrollKnob = tinted(white, 0.52f, 0.62f, 0.82f, 1f);
            skin.add("default", sp);

            List.ListStyle ls = new List.ListStyle();
            ls.font = font;
            ls.fontColorSelected = Color.WHITE;
            ls.fontColorUnselected = new Color(0.75f, 0.78f, 0.85f, 1f);
            ls.selection = tinted(white, 0.22f, 0.30f, 0.44f, 1f);
            ls.background = tinted(white, 0.12f, 0.13f, 0.16f, 1f);
            skin.add("default", ls);

            SelectBox.SelectBoxStyle sb = new SelectBox.SelectBoxStyle();
            sb.font = font;
            sb.fontColor = Color.WHITE;
            sb.background = tinted(white, 0.22f, 0.26f, 0.35f, 1f);
            sb.scrollStyle = sp;
            sb.listStyle = ls;
            skin.add("default", sb);
        }

        applyWidgetMetrics(skin);
        applyWindowTitleHeight(skin);
        return skin;
    }

    /**
     * Applies the widget geometry that a JSON skin <b>cannot</b> express: a
     * {@code Skin$TintedDrawable} only carries a name and a colour, so every tinted 1×1 atlas
     * region keeps its 1×1 min size. Without this the slider track is 2 px thin and the knob is
     * an invisible 2×2 dot.
     *
     * <p>The numbers live in {@link UiLayout} and are applied to <b>whichever</b> skin was loaded
     * (JSON or programmatic), so both look identical: a {@link UiLayout#TRACK_THICKNESS} thick
     * track, a {@link UiLayout#SLIDER_KNOB} square knob (normal / hover / dragging), a
     * {@link UiLayout#PROGRESS_THICKNESS} thick progress bar and
     * {@link UiLayout#SCROLLBAR_THICKNESS} wide scroll bars.
     */
    /**
     * Gives every {@code Window} style a real <b>title bar</b>.
     *
     * <p>Scene2D sizes the title band from the window background's top padding
     * ({@code Window.getPadTop()}), and both skins paint windows with a 1×1 px tinted texture whose
     * padding is 0 — so the band would be 0 px tall: the title label would be clipped away and
     * nothing inside the title bar (like {@link ControlPanel}'s collapse button) would be visible or
     * clickable. Setting {@link UiLayout#WINDOW_TITLE_HEIGHT} as the background's top height fixes
     * both, for the JSON <b>and</b> the fallback skin.</p>
     */
    private static void applyWindowTitleHeight(Skin skin) {
        for (String name : new String[]{"default", "dialog"}) {
            Window.WindowStyle style = skin.optional(name, Window.WindowStyle.class);
            if (style == null || style.background == null) continue;
            style.background.setTopHeight(UiLayout.WINDOW_TITLE_HEIGHT);
        }
    }

    private static void applyWidgetMetrics(Skin skin) {
        // --- Slider: horizontal is thick in y, vertical is thick in x ---
        for (String name : new String[]{"default-horizontal", "default-h", "default"}) {
            Slider.SliderStyle s = skin.optional(name, Slider.SliderStyle.class);
            if (s == null) continue;
            size(s.background, 0f, UiLayout.TRACK_THICKNESS);
            size(s.knobBefore, 0f, UiLayout.TRACK_THICKNESS);
            size(s.knobAfter, 0f, UiLayout.TRACK_THICKNESS);
            size(s.knob, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
            size(s.knobOver, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
            size(s.knobDown, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
        }
        Slider.SliderStyle vertical = skin.optional("default-vertical", Slider.SliderStyle.class);
        if (vertical != null) {
            size(vertical.background, UiLayout.TRACK_THICKNESS, 0f);
            size(vertical.knobBefore, UiLayout.TRACK_THICKNESS, 0f);
            size(vertical.knobAfter, UiLayout.TRACK_THICKNESS, 0f);
            size(vertical.knob, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
            size(vertical.knobOver, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
            size(vertical.knobDown, UiLayout.SLIDER_KNOB, UiLayout.SLIDER_KNOB);
        }

        // --- ProgressBar (no knob: the bar reads as a solid fill) ---
        // Skin#optional looks the type up exactly, so these are the ProgressBar styles even
        // though a Slider style is registered under the same style NAME.
        for (String name : new String[]{"default-horizontal", "default"}) {
            ProgressBar.ProgressBarStyle s = skin.optional(name, ProgressBar.ProgressBarStyle.class);
            if (s == null) continue;
            size(s.background, 0f, UiLayout.PROGRESS_THICKNESS);
            size(s.knobBefore, 0f, UiLayout.PROGRESS_THICKNESS);
        }
        ProgressBar.ProgressBarStyle progressVertical =
                skin.optional("default-vertical", ProgressBar.ProgressBarStyle.class);
        if (progressVertical != null) {
            size(progressVertical.background, UiLayout.PROGRESS_THICKNESS, 0f);
            size(progressVertical.knobBefore, UiLayout.PROGRESS_THICKNESS, 0f);
        }

        // --- ScrollPane: vertical bars grow in x, horizontal bars grow in y ---
        ScrollPane.ScrollPaneStyle sp = skin.optional("default", ScrollPane.ScrollPaneStyle.class);
        if (sp != null) {
            size(sp.vScroll, UiLayout.SCROLLBAR_THICKNESS, 0f);
            size(sp.vScrollKnob, UiLayout.SCROLLBAR_THICKNESS, 0f);
            size(sp.hScroll, 0f, UiLayout.SCROLLBAR_THICKNESS);
            size(sp.hScrollKnob, 0f, UiLayout.SCROLLBAR_THICKNESS);
        }
    }

    /**
     * Sets the min size of {@code d} on the given axes; {@code 0} leaves that axis untouched (a
     * vertical scroll bar must keep its full height, it only needs a width).
     */
    private static void size(Drawable d, float width, float height) {
        if (d == null) return;
        if (width > 0f) d.setMinWidth(width);
        if (height > 0f) d.setMinHeight(height);
    }
}
