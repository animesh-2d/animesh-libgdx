package com.jb.rts.mesh.blend;

import com.badlogic.gdx.utils.JsonValue;

/**
 * An indexed colour palette used by the mesh blending system.
 *
 * <p>Since the mesheditor now exports meshes that carry <em>colour indexes</em>
 * instead of per-vertex RGBA values, every {@link BlendableMesh} references a colour
 * through an integer index into a {@link ColorPalette}. The palette holds the actual
 * colours (named, RGBA in normalised {@code 0..1} space) and is fully <b>mutable</b>:
 *
 * <pre>{@code
 * MeshExport export = table.loadExport(Gdx.files.internal("meshes/tree_redwood-export.json"));
 * ColorPalette palette = export.palette;
 *
 * // Turn index 1 into a bright green – every mesh using that index updates instantly.
 * palette.setColor(1, 0.1f, 0.9f, 0.2f, 1f);
 * }</pre>
 *
 * <p>This is the mechanism that lets the user "replace the colours in the indexes": every renderer
 * resolves the indexes against this palette. {@code MeshBlenderGroup} re-uploads them as shader
 * uniforms ({@code refreshPalette()}), the VAT pipeline re-resolves its vertex buffer and its
 * per-frame colour strip ({@code VatInstancedMesh#refreshPalette(ColorPalette)}, which keeps the
 * per-vertex / per-frame indexes it was baked with). Neither path needs a re-bake, so a colour
 * picker can edit the palette live.
 *
 * <p><b>Colours may change between frames.</b> Most animations keep the same colour indexes in
 * every pose (only the positions move), but an export may override them per frame
 * ({@code animations[].frames[].colors}) — {@link MeshExportLoader} then gives that pose its own
 * {@code colorIndices}. The blend renderer mixes the colours of the blended poses, the VAT
 * pipeline does the same on the GPU through {@code VatFrameColorTexture}.
 *
 * <h3>Bounds</h3>
 * The palette is capped at {@value #MAX_COLORS} colours, matching the fixed uniform
 * budget of the generated renderer shaders.
 */
public final class ColorPalette {

    /**
     * Hard cap on the number of colours. Kept low so every colour can be uploaded as a
     * single {@code vec4} uniform (GLSL ES 2.0 has no dynamic uniform-array indexing, so the
     * generated shaders use an if-chain over exactly this many colours).
     */
    public static final int MAX_COLORS = 64;

    private final float[]   red   = new float[MAX_COLORS];
    private final float[]   green = new float[MAX_COLORS];
    private final float[]   blue  = new float[MAX_COLORS];
    private final float[]   alpha = new float[MAX_COLORS];
    private final String[]  names = new String[MAX_COLORS];
    private int size;

    // -----------------------------------------------------------------------
    // Building / editing
    // -----------------------------------------------------------------------

    /**
     * Appends a new colour to the palette and returns its index (which meshes may then
     * reference). Throws once the palette is full.
     *
     * @param name human-readable colour name (may be {@code null}/empty)
     * @param r    red   in {@code 0..1}
     * @param g    green in {@code 0..1}
     * @param b    blue  in {@code 0..1}
     * @param a    alpha in {@code 0..1}
     * @return the index of the new colour
     */
    public int addColor(String name, float r, float g, float b, float a) {
        if (size >= MAX_COLORS) {
            throw new IllegalStateException(
                "ColorPalette is full (" + MAX_COLORS + " colours). Cannot add another.");
        }
        int idx = size++;
        red[idx]   = r;
        green[idx] = g;
        blue[idx]  = b;
        alpha[idx] = a;
        names[idx] = name;
        return idx;
    }

    /**
     * Replaces an existing colour <em>in place</em> – the user-facing operation
     * described as "replace the colours in the indexes". All meshes that reference
     * {@code index} immediately render with the new colour once the palette is re-uploaded.
     *
     * @param index colour index to edit
     * @param name  new name (may be {@code null})
     * @param r     new red   in {@code 0..1}
     * @param g     new green in {@code 0..1}
     * @param b     new blue  in {@code 0..1}
     * @param a     new alpha in {@code 0..1}
     */
    public void setColor(int index, String name, float r, float g, float b, float a) {
        checkIndex(index);
        red[index]   = r;
        green[index] = g;
        blue[index]  = b;
        alpha[index] = a;
        names[index] = name;
    }

    /** Replaces just the RGBA of {@code index} (keeps its name). */
    public void setColor(int index, float r, float g, float b, float a) {
        setColor(index, names[index], r, g, b, a);
    }

    /** Renames a colour without touching its value. */
    public void setName(int index, String name) {
        checkIndex(index);
        names[index] = name;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Red channel of {@code index}, in {@code 0..1}. */
    public float red(int index)   { checkIndex(index); return red[index]; }
    /** Green channel of {@code index}, in {@code 0..1}. */
    public float green(int index) { checkIndex(index); return green[index]; }
    /** Blue channel of {@code index}, in {@code 0..1}. */
    public float blue(int index)  { checkIndex(index); return blue[index]; }
    /** Alpha channel of {@code index}, in {@code 0..1}. */
    public float alpha(int index) { checkIndex(index); return alpha[index]; }

    /** Name of {@code index} (may be {@code null}). */
    public String name(int index) { checkIndex(index); return names[index]; }

    /** Number of colours currently in the palette. */
    public int size() { return size; }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    /**
     * Builds a palette from the {@code colors} array of a mesheditor export.
     *
     * <p>Each entry is expected to look like:
     * <pre>{@code {"name":"fur","red":151,"green":95,"blue":49,"alpha":1}}</pre>
     * where {@code red/green/blue} are {@code 0..255} and {@code alpha} is {@code 0..1}
     * (RGB is normalised to {@code 0..1} on the way in).
     *
     * @param colorsNode the {@code "colors"} JSON array, or {@code null} for an empty palette
     * @return a new palette (never {@code null})
     */
    public static ColorPalette fromJson(JsonValue colorsNode) {
        ColorPalette palette = new ColorPalette();
        if (colorsNode == null) {
            return palette;
        }
        for (JsonValue c = colorsNode.child; c != null; c = c.next) {
            String name = c.getString("name", "");
            float r = c.getFloat("red",   0f) / 255f;
            float g = c.getFloat("green", 0f) / 255f;
            float b = c.getFloat("blue",  0f) / 255f;
            float a = c.getFloat("alpha", 1f);
            palette.addColor(name, r, g, b, a);
        }
        return palette;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                "Colour index " + index + " out of range [0," + size + ").");
        }
    }
}
