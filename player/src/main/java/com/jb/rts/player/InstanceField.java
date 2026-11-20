package com.jb.rts.player;

/**
 * The <b>placements and sizes</b> of the instanced copies the mesh player draws — a small,
 * GL-free value object that can be generated and tested without a window.
 *
 * <p>Two layouts are supported:</p>
 * <ul>
 *   <li>{@link Layout#GRID} — a square-ish grid with {@code spacing = cell * }{@value #SPACING},
 *       exactly like the classic player view;</li>
 *   <li>{@link Layout#RANDOM} — uniformly scattered inside the same area, the layout the
 *       mesheditor asks for when it opens the standalone player.</li>
 * </ul>
 *
 * <p><b>Y is the z-order.</b> The renderer draws instances in array order in a single instanced
 * draw, so the field is sorted by <b>descending y</b>: an instance further up is drawn first
 * (behind), one further down last (on top). The grid is generated from its top row down and the
 * random scatter is sorted once, so callers can simply iterate {@code 0 .. size()-1}.</p>
 *
 * <p>Every instance also gets a random <b>scale</b> in {@code [1 - deviation, 1 + deviation]}.
 * The generator is deterministic for a given {@code seed}, so the same layout can be reproduced
 * (tests rely on this; the player re-seeds when the user changes a layout control).</p>
 */
public final class InstanceField {

    /** Distance between two grid cells, as a multiple of the mesh's bounding-box cell. */
    public static final float SPACING = 1.2f;

    /** Placement strategies the player's panel offers. */
    public enum Layout {
        /** Rows and columns of equal spacing. */
        GRID("Grid"),
        /** Uniform random positions inside the grid's area. */
        RANDOM("Random");

        private final String label;

        Layout(String label) {
            this.label = label;
        }

        /** Human-readable name for the UI (e.g. {@code "Grid"}). */
        public String label() {
            return label;
        }
    }

    private final float[] x;
    private final float[] y;
    private final float[] scale;
    private final float extent;

    private InstanceField(float[] x, float[] y, float[] scale, float extent) {
        this.x = x;
        this.y = y;
        this.scale = scale;
        this.extent = extent;
    }

    /**
     * Builds a field.
     *
     * @param layout             grid or random
     * @param count              number of instances (negative values become 0)
     * @param cell               size of one mesh in world units (the larger bounding-box side)
     * @param centerX            world x the field is centred on
     * @param centerY            world y the field is centred on
     * @param maxDeviationPercent largest relative size change, e.g. {@code 30} for ±30 %
     * @param seed               seed of the size/position generator (deterministic layouts)
     * @return the field, sorted by descending y
     */
    public static InstanceField build(Layout layout, int count, float cell, float centerX,
                                      float centerY, float maxDeviationPercent, long seed) {
        int n = Math.max(0, count);
        float cellSize = Math.max(1e-3f, cell);
        float spacing = cellSize * SPACING;
        float deviation = Math.max(0f, maxDeviationPercent) / 100f;

        float[] px = new float[n];
        float[] py = new float[n];
        float[] ps = new float[n];
        java.util.Random rng = new java.util.Random(seed);

        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
        int rows = n == 0 ? 0 : (n + columns - 1) / columns;
        if (layout == Layout.GRID) {
            float ox = centerX - columns * spacing * 0.5f;
            float oy = centerY - rows * spacing * 0.5f;
            // Top row first: the array is already z-sorted (y descending).
            for (int i = 0; i < n; i++) {
                int row = i / columns;
                int column = i % columns;
                px[i] = ox + (column + 0.5f) * spacing;
                py[i] = oy + (rows - row - 0.5f) * spacing;
                ps[i] = 1f + deviation * (rng.nextFloat() * 2f - 1f);
            }
        } else {
            float area = columns * spacing;
            float usable = Math.max(0f, area - cellSize);
            for (int i = 0; i < n; i++) {
                px[i] = centerX + (rng.nextFloat() - 0.5f) * usable;
                py[i] = centerY + (rng.nextFloat() - 0.5f) * usable;
                ps[i] = 1f + deviation * (rng.nextFloat() * 2f - 1f);
            }
            sortByDescendingY(px, py, ps, n);
        }

        // The camera has to fit the field including the largest possible copy.
        float extent = Math.max(columns, Math.max(rows, 1)) * spacing + cellSize;
        return new InstanceField(px, py, ps, extent * (1f + deviation));
    }

    /** An empty field (before the first export loads). */
    public static InstanceField empty() {
        return new InstanceField(new float[0], new float[0], new float[0], 1f);
    }

    /** Number of instances in the field. */
    public int size() {
        return x.length;
    }

    /** World x of instance {@code index}. */
    public float x(int index) {
        return x[index];
    }

    /** World y of instance {@code index} (also its z-order: descending). */
    public float y(int index) {
        return y[index];
    }

    /** Uniform scale of instance {@code index} ({@code 1 ± deviation}). */
    public float scale(int index) {
        return scale[index];
    }

    /**
     * Side length of the square the field occupies, including the largest possible copy — the
     * camera fits this value so the whole crowd stays visible.
     */
    public float extent() {
        return extent;
    }

    // ------------------------------------------------------------------ sorting

    /**
     * Sorts the three parallel arrays by descending y (higher = further back = drawn first).
     * A hand-written quicksort keeps the payload in primitive arrays — no boxing for a field of
     * thousands of instances.
     */
    private static void sortByDescendingY(float[] px, float[] py, float[] ps, int n) {
        if (n > 1) quickSort(px, py, ps, 0, n - 1);
    }

    private static void quickSort(float[] px, float[] py, float[] ps, int low, int high) {
        int lo = low;
        int hi = high;
        float pivot = py[(low + high) >>> 1];
        while (lo <= hi) {
            while (py[lo] > pivot) lo++;
            while (py[hi] < pivot) hi--;
            if (lo <= hi) {
                swap(px, py, ps, lo, hi);
                lo++;
                hi--;
            }
        }
        if (low < hi) quickSort(px, py, ps, low, hi);
        if (lo < high) quickSort(px, py, ps, lo, high);
    }

    private static void swap(float[] px, float[] py, float[] ps, int a, int b) {
        float t = px[a]; px[a] = px[b]; px[b] = t;
        t = py[a]; py[a] = py[b]; py[b] = t;
        t = ps[a]; ps[a] = ps[b]; ps[b] = t;
    }
}
