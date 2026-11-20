package com.jb.rts.player;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatFile;
import com.jb.rts.mesh.vat.VatFormat;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.mesh.vat.VatMeshData;

/**
 * Self-contained demo / smoke test for the VAT instancing pipeline.
 *
 * <p>It generates a small procedurally skinned "grass blade" mesh (7 bones, 48 baked frames),
 * uploads it through the exact same code path an exported {@code .vat} file uses
 * ({@link VatMeshData} + {@link VatAnimationData} + {@link VatInstancedMesh}) and draws
 * {@value #DEFAULT_INSTANCES} instances in a <b>single</b> {@code glDrawArraysInstanced} call.
 *
 * <p>Lives in the player module (not in {@code animesh-lib}) on purpose: the library ships no
 * demos.
 *
 * <h3>Run it</h3>
 * <pre>
 * ./gradlew :player:runVatDemo
 * ./gradlew :player:runVatDemo -Pvat.instances=200000 -Pvat.autoexit=5
 * </pre>
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code vat.instances} — instance count (default 100000)</li>
 *   <li>{@code vat.file} — preview an exported {@code .vat} instead of the procedural blade</li>
 *   <li>{@code vat.autoexit} — seconds after which the demo closes itself (0 = never)</li>
 *   <li>{@code vat.debug} — log the VAO attribute state at startup</li>
 *   <li>{@code vat.single} — render one huge instance instead of the field (pipeline smoke test)</li>
 *   <li>{@code vat.benchmark} — disable vsync / frame cap (used by the launcher)</li>
 * </ul>
 */
public class VatDemoApplication extends ApplicationAdapter {

    /** Instance count used when {@code -Dvat.instances} is not set. */
    public static final int DEFAULT_INSTANCES = 100_000;

    private static final int   BONES      = 7;
    private static final int   SEGMENTS   = BONES - 1;
    private static final int   COLUMNS    = 2;
    private static final int   FRAMES     = 48;
    private static final float HEIGHT     = 3f;
    private static final float HALF_WIDTH = 0.35f;

    private static final float FIELD_WIDTH  = 400f;
    private static final float FIELD_HEIGHT = 225f;

    private final int instanceCount;

    private OrthographicCamera camera;
    private SpriteBatch hudBatch;
    private BitmapFont font;
    private VatInstancedMesh batch;
    private VatMeshData meshData;
    private VatAnimationData animation;
    /** Bind-pose bounds {@code [minX, minY, maxX, maxY]} used to fit any mesh into a cell. */
    private float[] bounds;

    private float time;
    private float statsTimer;
    private int framesSinceStats;
    private boolean coverageChecked;
    private final float autoExitSeconds;

    public VatDemoApplication() {
        this(instanceCount());
    }

    /** Creates the demo with an explicit instance count (used by tests / benchmarks). */
    public VatDemoApplication(int instanceCount) {
        this.instanceCount = Math.max(1, instanceCount);
        this.autoExitSeconds = Float.parseFloat(System.getProperty("vat.autoexit", "0"));
    }

    private static int instanceCount() {
        String raw = System.getProperty("vat.instances");
        if (raw == null || raw.isBlank()) return DEFAULT_INSTANCES;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_INSTANCES;
        }
    }

    @Override
    public void create() {
        camera = new OrthographicCamera(FIELD_WIDTH, FIELD_HEIGHT);
        // The vertex shader forwards the mesh z (editor draw order) unchanged; a near plane of 0
        // would clip every vertex with a non-zero z. Depth testing is off, so a symmetric range
        // is safe and keeps arbitrary exported draw-order values visible.
        camera.near = -100_000f;
        camera.far = 100_000f;
        camera.position.set(FIELD_WIDTH * 0.5f, FIELD_HEIGHT * 0.5f, 0f);
        camera.update();

        String file = System.getProperty("vat.file");
        if (file != null && !file.isBlank()) {
            VatFile.Content content = VatFile.load(
                new com.badlogic.gdx.files.FileHandle(new java.io.File(file)));
            meshData = content.mesh();
            animation = content.animation();
        } else {
            meshData = buildBladeMesh();
            animation = buildSwayAnimation();
        }
        bounds = meshBounds(meshData);
        batch = new VatInstancedMesh(meshData, animation, instanceCount);

        hudBatch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(1.25f);

        Gdx.app.log("VatDemo", String.format(
            "Ready: %d instances, %d triangles per instance, 1 draw call per frame.",
            instanceCount, meshData.triangleCount()));

        Gdx.app.log("VatDemo", String.format(
            "Bind-pose bounds: x[%.2f..%.2f] y[%.2f..%.2f]", bounds[0], bounds[2],
            bounds[1], bounds[3]));

        if (Boolean.getBoolean("vat.debug")) {
            batch.logPipelineState();
        }
    }

    @Override
    public void render() {
        float delta = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        time += delta;

        if (Gdx.input.isKeyPressed(Keys.ESCAPE)) {
            Gdx.app.exit();
        }
        if (autoExitSeconds > 0f && time >= autoExitSeconds) {
            logStats();
            Gdx.app.exit();
        }

        camera.update();
        Gdx.gl.glClearColor(0.07f, 0.10f, 0.09f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // ---- build the instance stream: position + fractional frame only ----
        float baseFrame = animation.frameAt(time);
        int cols = Math.max(1, (int) Math.ceil(
            Math.sqrt(instanceCount * (FIELD_WIDTH / FIELD_HEIGHT))));
        int rows = Math.max(1, (instanceCount + cols - 1) / cols);
        float stepX = FIELD_WIDTH / cols;
        float stepY = FIELD_HEIGHT / rows;

        // Fit whatever mesh is loaded (procedural blade or an exported .vat) into one cell.
        float meshW = Math.max(1e-3f, bounds[2] - bounds[0]);
        float meshH = Math.max(1e-3f, bounds[3] - bounds[1]);
        float fit = Math.min(stepX * 0.95f / meshW, stepY * 0.95f / meshH);

        batch.begin();
        if (Boolean.getBoolean("vat.single")) {
            // Diagnostic: one huge instance in the middle of the screen.
            batch.add(FIELD_WIDTH * 0.5f, FIELD_HEIGHT * 0.5f, 0f, 0f, fit * 30f, fit * 30f,
                1f, 1f, 1f, 1f);
        } else {
            int index = 0;
            for (int row = 0; row < rows && index < instanceCount; row++) {
                for (int col = 0; col < cols && index < instanceCount; col++, index++) {
                    float x = (col + 0.5f) * stepX;
                    float y = (row + 0.5f) * stepY;

                    // Per-instance phase offset in frames + a little variation.
                    float phase = (col * 7.13f + row * 3.71f) % animation.frameCount();
                    float frame = animation.wrapFrame(baseFrame + phase);
                    float scale = fit * (0.88f + 0.12f * ((col * 31 + row * 17) % 7) / 7f);
                    float tint = 0.55f + 0.45f * ((col * 13 + row * 29) % 11) / 11f;

                    // Centre horizontally, put the mesh base on the cell floor.
                    float offsetX = -(bounds[0] + bounds[2]) * 0.5f * scale;
                    float offsetY = -bounds[1] * scale;

                    batch.add(x + offsetX, y + offsetY, frame, 0f, scale, scale,
                        tint, 1f, tint * 0.85f, 1f);
                }
            }
        }

        // ---- ONE draw call for all instances ----
        batch.render(camera.combined);

        // ---- HUD ----
        hudBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(
            0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
        hudBatch.begin();
        font.setColor(Color.WHITE);
        font.draw(hudBatch, String.format(
            "%d instances - 1 draw call - %d fps - frame %.1f/%d",
            instanceCount, Gdx.graphics.getFramesPerSecond(), baseFrame, animation.frameCount()),
            16, Gdx.graphics.getHeight() - 16);
        hudBatch.end();

        framesSinceStats++;
        statsTimer += delta;
        if (statsTimer >= 1f) {
            logStats();
            statsTimer = 0f;
        }
        if (!coverageChecked && time > 1.2f) {
            coverageChecked = true;
            logCoverage();
        }
    }

    /**
     * Reads the back buffer once and reports how much of it is covered by geometry. This makes
     * an unattended run fail loudly when the shader/instancing path silently renders nothing.
     */
    private void logCoverage() {
        int glError = Gdx.gl.glGetError();
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();
        com.badlogic.gdx.graphics.Pixmap pixels =
            com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, w, h);
        long lit = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = pixels.getPixel(x, y);
                int r = (rgba >> 24) & 0xff;
                int g = (rgba >> 16) & 0xff;
                int b = (rgba >> 8) & 0xff;
                if (r > 40 || g > 55 || b > 45) lit++;
            }
        }
        pixels.dispose();
        Gdx.app.log("VatDemo", String.format(
            "frame-buffer coverage %.1f%% (%d px), glGetError=0x%04X",
            100.0 * lit / (w * (double) h), lit, glError));
    }

    private void logStats() {
        if (Gdx.app == null) return;
        Gdx.app.log("VatDemo", String.format(
            "%d instances, 1 draw call, %d fps, %d frames sampled",
            instanceCount, Gdx.graphics.getFramesPerSecond(), framesSinceStats));
        framesSinceStats = 0;
    }

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = FIELD_WIDTH;
        camera.viewportHeight = FIELD_HEIGHT;
        camera.update();
    }

    @Override
    public void dispose() {
        if (batch != null) batch.dispose();
        if (hudBatch != null) hudBatch.dispose();
        if (font != null) font.dispose();
    }

    // =======================================================================
    // Procedural skinned blade (bind pose + influences)
    // =======================================================================

    /** Bind-pose bounds of a mesh: {@code [minX, minY, maxX, maxY]}. */
    private static float[] meshBounds(VatMeshData mesh) {
        float[] p = mesh.positions();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 2 < p.length; i += 3) {
            minX = Math.min(minX, p[i]);
            maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]);
            maxY = Math.max(maxY, p[i + 1]);
        }
        if (minX > maxX) return new float[]{-0.5f, 0f, 0.5f, 1f};
        return new float[]{minX, minY, maxX, maxY};
    }

    private VatMeshData buildBladeMesh() {
        int sourceCols = COLUMNS + 1;
        int sourceRows = SEGMENTS + 1;
        int sourceCount = sourceCols * sourceRows;

        float[] sx = new float[sourceCount];
        float[] sy = new float[sourceCount];
        for (int row = 0; row < sourceRows; row++) {
            for (int col = 0; col < sourceCols; col++) {
                int i = row * sourceCols + col;
                sx[i] = -HALF_WIDTH + (2f * HALF_WIDTH) * col / COLUMNS;
                sy[i] = HEIGHT * row / SEGMENTS;
            }
        }

        // Two influences per vertex: the bone at the vertex height and the next one up.
        int[] ids = new int[sourceCount * VatFormat.INFLUENCES];
        float[] weights = new float[sourceCount * VatFormat.INFLUENCES];
        for (int i = 0; i < sourceCount; i++) {
            float t = sy[i] / (HEIGHT / SEGMENTS);
            int bone = Math.min((int) Math.floor(t), BONES - 1);
            int next = Math.min(bone + 1, BONES - 1);
            float frac = bone == next ? 0f : t - bone;
            ids[i * VatFormat.INFLUENCES] = bone;
            ids[i * VatFormat.INFLUENCES + 1] = next;
            weights[i * VatFormat.INFLUENCES] = 1f - frac;
            weights[i * VatFormat.INFLUENCES + 1] = frac;
        }

        // Expand each triangle corner (the runtime expects an implicit triangle list).
        int triangles = COLUMNS * SEGMENTS * 2;
        int vertexCount = triangles * 3;
        float[] positions = new float[vertexCount * 3];
        float[] colors = new float[vertexCount * 4];
        int[] outIds = new int[vertexCount * VatFormat.INFLUENCES];
        float[] outWeights = new float[vertexCount * VatFormat.INFLUENCES];

        String[] boneNames = new String[BONES];
        for (int b = 0; b < BONES; b++) boneNames[b] = "blade" + b;

        int v = 0;
        for (int col = 0; col < COLUMNS; col++) {
            for (int row = 0; row < SEGMENTS; row++) {
                int v00 = row * sourceCols + col;
                int v10 = v00 + 1;
                int v01 = v00 + sourceCols;
                int v11 = v01 + 1;
                int[][] trianglesOfCell = {{v00, v10, v11}, {v00, v11, v01}};
                for (int[] tri : trianglesOfCell) {
                    for (int c = 0; c < 3; c++) {
                        int src = tri[c];
                        positions[v * 3] = sx[src];
                        positions[v * 3 + 1] = sy[src];
                        positions[v * 3 + 2] = 0f;

                        float shade = 0.35f + 0.65f * (sy[src] / HEIGHT);
                        colors[v * 4] = 0.15f * shade;
                        colors[v * 4 + 1] = 0.85f * shade;
                        colors[v * 4 + 2] = 0.25f * shade;
                        colors[v * 4 + 3] = 1f;

                        System.arraycopy(ids, src * VatFormat.INFLUENCES,
                            outIds, v * VatFormat.INFLUENCES, VatFormat.INFLUENCES);
                        System.arraycopy(weights, src * VatFormat.INFLUENCES,
                            outWeights, v * VatFormat.INFLUENCES, VatFormat.INFLUENCES);
                        v++;
                    }
                }
            }
        }

        return new VatMeshData(positions, colors, outIds, outWeights, null, boneNames);
    }

    /** Bakes a sine "sway" animation: every bone rotates around its own base. */
    private VatAnimationData buildSwayAnimation() {
        float[] matrices = new float[VatFormat.matrixFloatCount(BONES, FRAMES)];
        Matrix4 m = new Matrix4();
        for (int frame = 0; frame < FRAMES; frame++) {
            float phase = (float) (frame / (double) FRAMES * Math.PI * 2.0);
            for (int b = 0; b < BONES; b++) {
                float baseY = b * (HEIGHT / SEGMENTS);
                float angle = 0.38f * (float) Math.sin(phase - b * 0.45f)
                    * (0.35f + 0.65f * b / (float) (BONES - 1));
                m.idt()
                    .translate(0f, baseY, 0f)
                    .rotateRad(0f, 0f, 1f, angle)
                    .translate(0f, -baseY, 0f);
                System.arraycopy(m.val, 0, matrices,
                    VatFormat.matrixFloatOffset(b, frame, FRAMES), VatFormat.FLOATS_PER_MATRIX);
            }
        }
        return new VatAnimationData("sway", BONES, FRAMES, 30f, true, null, matrices);
    }
}