package com.jb.rts.player.ui;

import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;

import java.util.Locale;

/**
 * A Scene2D <b>colour picker</b>: a saturation/value square with a vertical hue bar, a preview
 * swatch and an editable {@code #RRGGBB} field.
 *
 * <p>libGDX has no colour picker of its own, so this widget draws the two gradients itself:</p>
 * <ul>
 *   <li>the <b>SV square</b> is a hue-tinted {@code white} drawable of the skin, covered by a
 *       white → transparent overlay (left to right, {@code 1 - saturation}) and a transparent →
 *       black overlay (top to bottom, {@code 1 - value}). Compositing those three layers yields
 *       exactly {@code HSV(h, s, v)};</li>
 *   <li>the <b>hue bar</b> is a pre-generated 1 × {@value #HUE_STEPS} texture with hue
 *       {@code 0..360} from top to bottom.</li>
 * </ul>
 *
 * <p>Dragging either area (or typing a hex value) updates the picker's live {@link Color} and
 * calls the {@link Listener} on <b>every</b> change, so callers can preview the colour while the
 * user drags. The picker itself never touches a config — the owning dialog decides whether to
 * keep or revert the colour (see {@link ColorPickerDialog}).</p>
 *
 * <p>The gradient textures are created lazily once and shared by every picker; like the shared
 * {@link Skin} they live for the lifetime of the application (they are not per-dialog
 * disposables).</p>
 */
public final class ColorPicker extends Table {

    /** Receives every colour change; the picker's live {@link Color} instance is passed. */
    public interface Listener {
        void changed(Color color);
    }

    /** Side length of the saturation/value square, in pixels. */
    private static final float SV_SIZE = 210f;
    /** Width of the vertical hue bar, in pixels. */
    private static final float HUE_WIDTH = 26f;
    /** Diameter of the selection ring drawn on both areas, in pixels. */
    private static final float MARKER_DIAMETER = 16f;
    /** Rows of the static hue gradient. */
    private static final int HUE_STEPS = 128;
    /** Steps of the white / black alpha gradients. */
    private static final int SHADE_STEPS = 256;
    /** Pixel size of the generated selection-ring texture. */
    private static final int MARKER_SIZE = 16;

    private final Listener listener;
    private final Color color;
    /** Hue in degrees (0..360), saturation and value in 0..1. */
    private final float[] hsv = new float[3];
    private final Drawable base;
    /** Scratch colour for the hue-tinted base of the SV square. */
    private final Color hueColor = new Color();

    private final SVArea svArea;
    private final HueBar hueBar;
    private final Image preview;
    private final TextField hexField;
    private final Label rgb;
    /** Guards the hex field against the programmatic update loop. */
    private boolean updating;

    /**
     * @param skin     skin providing {@code white} and the standard label / text-field styles
     * @param initial  colour to start from (copied, never shared)
     * @param listener called on every change — may be {@code null}
     */
    public ColorPicker(Skin skin, Color initial, Listener listener) {
        super(skin);
        this.listener = listener;
        this.color = new Color(initial);
        this.color.toHsv(hsv);
        this.base = skin.getDrawable("white");

        Textures.ensure();
        svArea = new SVArea();
        hueBar = new HueBar();

        preview = new Image(skin.getDrawable("white"));
        hexField = new TextField("", skin);
        hexField.setMaxLength(7);
        hexField.setAlignment(Align.center);
        hexField.setMessageText("#RRGGBB");
        hexField.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (updating) return;
                Integer rgb888 = parseHex(hexField.getText());
                if (rgb888 == null) return;                 // partial / invalid input
                color.set(((rgb888 >> 16) & 0xFF) / 255f,
                    ((rgb888 >> 8) & 0xFF) / 255f, (rgb888 & 0xFF) / 255f, color.a);
                color.toHsv(hsv);
                refresh(false);                             // keep the text being typed
            }
        });

        rgb = new Label("", skin);
        rgb.setColor(0.72f, 0.76f, 0.84f, 1f);

        Table gradients = new Table();
        gradients.add(svArea).size(SV_SIZE, SV_SIZE);
        gradients.add(hueBar).width(HUE_WIDTH).growY().padLeft(8f);

        Table frame = new Table();
        frame.setBackground(skin.newDrawable("white", new Color(0.08f, 0.09f, 0.11f, 1f)));
        frame.add(preview).grow().pad(2f);

        Table info = new Table();
        info.add(frame).size(84f, 26f).left();
        info.add(new Label("Hex", skin)).padLeft(12f);
        info.add(hexField).width(110f).height(30f).left();
        info.add(rgb).padLeft(12f).expandX().left();

        defaults().left();
        add(gradients).left().row();
        add(info).growX().padTop(10f).row();

        refresh(true);
    }

    /** The live colour; mutated in place by every interaction. */
    public Color getColor() {
        return color;
    }

    /** Formats {@code color} as {@code #RRGGBB}; alpha is ignored. */
    public static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            byte255(color.r), byte255(color.g), byte255(color.b));
    }

    /** {@code 0..1} float channel to a {@code 0..255} integer, clamped against rounding. */
    private static int byte255(float value) {
        return MathUtils.clamp(Math.round(value * 255f), 0, 255);
    }

    /**
     * Parses {@code #RRGGBB} or {@code RRGGBB} (case-insensitive, surrounding whitespace is
     * ignored) into an {@code 0xRRGGBB} integer.
     *
     * @return the colour, or {@code null} if the text is not exactly six hex digits
     */
    public static Integer parseHex(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return null;
        int value = 0;
        for (int i = 0; i < 6; i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) return null;
            value = (value << 4) | digit;
        }
        return value;
    }

    // ------------------------------------------------------------------ state

    private void setHsv(float h, float s, float v) {
        hsv[0] = h;
        hsv[1] = MathUtils.clamp(s, 0f, 1f);
        hsv[2] = MathUtils.clamp(v, 0f, 1f);
        refresh(true);
    }

    /** Recomputes RGB from {@link #hsv} and updates preview, RGB label and listener. */
    private void refresh(boolean syncHex) {
        color.fromHsv(hsv[0], hsv[1], hsv[2]);
        if (syncHex) {
            updating = true;
            hexField.setText(hex(color));
            updating = false;
        }
        preview.setColor(color);
        rgb.setText("R " + byte255(color.r)
            + "   G " + byte255(color.g)
            + "   B " + byte255(color.b));
        if (listener != null) listener.changed(color);
    }

    private static void drawMarker(Batch batch, float x, float y, float parentAlpha) {
        batch.setColor(1f, 1f, 1f, parentAlpha);
        float half = MARKER_DIAMETER / 2f;
        batch.draw(Textures.marker, x - half, y - half, MARKER_DIAMETER, MARKER_DIAMETER);
    }

    // ------------------------------------------------------------------ areas

    /**
     * The saturation/value square: {@code x = 0} is saturation 0, {@code y = height} is value 1.
     * The three drawing layers are exactly the HSV composition, see the class comment.
     */
    private final class SVArea extends Actor {

        private SVArea() {
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer,
                                         int button) {
                    if (button != Buttons.LEFT) return false;
                    pick(x, y);
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    pick(x, y);
                }
            });
        }

        private void pick(float x, float y) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            // Scene2D's local y grows upwards and the square is bright at the top: value 1 is
            // the top edge, value 0 the bottom edge — same axis as the marker in draw().
            setHsv(hsv[0], MathUtils.clamp(x / w, 0f, 1f),
                MathUtils.clamp(y / h, 0f, 1f));
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            Color old = batch.getColor();
            float oldR = old.r, oldG = old.g, oldB = old.b, oldA = old.a;
            float x = getX(), y = getY(), w = getWidth(), h = getHeight();

            // 1) hue base, 2) white overlay (1 - s, left to right), 3) black overlay (1 - v).
            hueColor.fromHsv(hsv[0], 1f, 1f);
            batch.setColor(hueColor.r, hueColor.g, hueColor.b, parentAlpha);
            base.draw(batch, x, y, w, h);
            batch.setColor(1f, 1f, 1f, parentAlpha);
            batch.draw(Textures.whiteShade, x, y, w, h);
            batch.draw(Textures.blackShade, x, y, w, h);

            drawMarker(batch, x + hsv[1] * w, y + hsv[2] * h, parentAlpha);
            batch.setColor(oldR, oldG, oldB, oldA);
        }
    }

    /** The vertical hue bar; hue {@code 0} is at the top, {@code 360} at the bottom. */
    private final class HueBar extends Actor {

        private HueBar() {
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer,
                                         int button) {
                    if (button != Buttons.LEFT) return false;
                    pick(y);
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    pick(y);
                }
            });
        }

        private void pick(float y) {
            float h = getHeight();
            if (h <= 0f) return;
            float hue = MathUtils.clamp(1f - y / h, 0f, 1f) * 360f;
            setHsv(Math.min(hue, 359.99f), hsv[1], hsv[2]);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            Color old = batch.getColor();
            float oldR = old.r, oldG = old.g, oldB = old.b, oldA = old.a;
            float x = getX(), y = getY(), w = getWidth(), h = getHeight();

            batch.setColor(1f, 1f, 1f, parentAlpha);
            batch.draw(Textures.hue, x, y, w, h);
            float markerY = y + h * (1f - MathUtils.clamp(hsv[0] / 360f, 0f, 1f));
            drawMarker(batch, x + w / 2f, markerY, parentAlpha);

            batch.setColor(oldR, oldG, oldB, oldA);
        }
    }

    // ------------------------------------------------------------------ textures

    /**
     * Lazily generated, shared gradient textures. They need a live GL context, so they are
     * created on the first {@link ColorPicker} (never in a static initialiser) and — like the
     * shared {@link Skin} — live for the lifetime of the application.
     */
    private static final class Textures {

        /** Vertical hue gradient: row 0 = hue 0, last row = hue 360 (red again). */
        private static Texture hue;
        /** Horizontal white overlay: alpha 1 at the left edge, 0 at the right edge. */
        private static Texture whiteShade;
        /** Vertical black overlay: alpha 0 at the top edge, 1 at the bottom edge. */
        private static Texture blackShade;
        /** Selection ring: black outline with a white core. */
        private static Texture marker;

        private Textures() {}

        private static void ensure() {
            if (hue != null) return;
            hue = createHueBar();
            whiteShade = createWhiteShade();
            blackShade = createBlackShade();
            marker = createMarker();
        }

        private static Texture createHueBar() {
            Pixmap pm = new Pixmap(1, HUE_STEPS, Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            Color c = new Color();
            for (int y = 0; y < HUE_STEPS; y++) {
                c.fromHsv(360f * y / (HUE_STEPS - 1f), 1f, 1f);
                pm.setColor(c.r, c.g, c.b, 1f);
                pm.drawPixel(0, y);
            }
            return toTexture(pm);
        }

        private static Texture createWhiteShade() {
            Pixmap pm = new Pixmap(SHADE_STEPS, 1, Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < SHADE_STEPS; x++) {
                pm.setColor(1f, 1f, 1f, 1f - x / (SHADE_STEPS - 1f));
                pm.drawPixel(x, 0);
            }
            return toTexture(pm);
        }

        private static Texture createBlackShade() {
            Pixmap pm = new Pixmap(1, SHADE_STEPS, Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            for (int y = 0; y < SHADE_STEPS; y++) {
                pm.setColor(0f, 0f, 0f, y / (SHADE_STEPS - 1f));
                pm.drawPixel(0, y);
            }
            return toTexture(pm);
        }

        private static Texture createMarker() {
            Pixmap pm = new Pixmap(MARKER_SIZE, MARKER_SIZE, Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            float center = (MARKER_SIZE - 1) / 2f;
            for (int y = 0; y < MARKER_SIZE; y++) {
                for (int x = 0; x < MARKER_SIZE; x++) {
                    float dx = x - center;
                    float dy = y - center;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d <= 4.6f) {
                        pm.setColor(1f, 1f, 1f, 1f);       // white core
                    } else if (d <= 6.8f) {
                        pm.setColor(0f, 0f, 0f, 1f);       // black outline
                    } else {
                        continue;                          // transparent
                    }
                    pm.drawPixel(x, y);
                }
            }
            return toTexture(pm);
        }

        private static Texture toTexture(Pixmap pm) {
            Texture texture = new Texture(pm);
            texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
            pm.dispose();
            return texture;
        }
    }
}
