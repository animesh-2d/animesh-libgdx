package com.jb.rts;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Array;
import com.jb.rts.mesh.MetricUnit;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.blend.MeshSkin;
import com.jb.rts.mesh.blend.MeshSkinClip;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatFile;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.mesh.vat.VatMeshData;
import com.jb.rts.mesh.vat.VatPoseBaker;
import com.jb.rts.mesh.vat.VatSkinBaker;
import com.jb.rts.mesh.vat.VatVertexOffsetBaker;
import com.jb.rts.player.ui.HudOverlay;
import com.jb.rts.player.ui.NavBar;
import com.jb.rts.player.ui.PlayerScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Mesh player: plays an <em>animesh mesh export</em> — the bundled example
 * {@code meshes/fire-export.json} on startup, or any file the user opens. Every animation of the
 * file is played in an instance grid. On-screen <b>◀ Prev / Next ▶</b> buttons (or the arrow
 * keys) step through the animations, <b>Load…</b> opens another export, and the
 * <b>Close</b> button (or <b>Esc</b>) exits the player.
 *
 * <p>Each animation occupies a row of a small grid of instances; every instance in that row
 * plays the animation, offset by a phase, so you can watch all animations of the file side by
 * side. The currently playing file is shown at the top of the window. The bottom-left corner
 * shows the current animation (<em>e.g.</em> {@code 2/3  idle}) followed by the number of
 * objects drawn this frame and the current frame rate.
 *
 * <p><b>UI:</b> the button bar and the overlay text come from the player's Scene2D toolkit
 * ({@link NavBar}, {@link HudOverlay}, {@link com.jb.rts.player.ui.UiLayout}) — the same layout
 * the VAT demo and the rest of the family use, edited in exactly one place.
 *
 * <p>Started by {@code com.jb.rts.player.desktop.MeshPlayerLauncher} ({@code ./gradlew :player:run}).
 *
 * <p><b>Rendering — GPU skinning with Vertex Animation Textures (VAT).</b> Each animation is
 * baked once at load time ({@link VatPoseBaker}) into one {@code RGBA32F} bone-matrix texture and
 * drawn with the hardware-instanced VAT pipeline ({@link VatInstancedMesh}). The CPU writes only
 * position + fractional frame index per instance, so the whole grid is a single
 * {@code glDrawArraysInstanced} call — see {@code animesh-lib/docs/VAT_INSTANCING.md}.
 *
 * <p><b>Colour blending</b> is preserved: when an export overrides vertex colours per animation
 * frame ({@code frames[].colors}, e.g. fire flicker), those overrides are baked into an
 * {@code RGBA8} colour strip and the shader mixes the colours of the two surrounding frames with
 * the same weight it uses for the positions — exactly what the old CPU blend renderer did.
 *
 * <p><b>Model textures.</b> An export created from a texture-painted mesh (version 6) names its
 * atlas in {@code "textureFile"} and carries per-vertex {@code "uvVertices"}; the exporter copies
 * the picture next to the JSON. The player loads it once per file and hands it to every animation
 * batch, so the fragment shader samples the atlas instead of the Gouraud corner colours
 * ({@link VatInstancedMesh}). Exports without a texture are drawn exactly as before.
 *
 * <p><b>Attachments</b> are baked per frame by the mesheditor (placed on their slot or collapsed
 * to size 0). Their vertices are named by the export's {@code "stepVertices"} array and uploaded
 * as a {@link com.jb.rts.mesh.vat.VatStepTexture}; the shader snaps their frame index instead of
 * blending, so putting an attachment into a slot or removing it is instantaneous.
 *
 * <p><b>Coordinate system:</b> world space is the standard math system — <b>+x points right</b>
 * and <b>+y points up</b>. The JavaFX mesheditor's y axis grows down, but its exporter already
 * negates y (cartesian y-up, {@code "yUp": true}) — such exports are used unchanged. Only
 * legacy y-down exports ({@code "yUp": false}) are mirrored on y once, see
 * {@link #convertExportToWorldYUp(MeshExport)}. No camera rotation or mirrored placement is
 * involved. Physical sizes use {@link MetricUnit} and the export's {@code measurement} field.
 */
public class MeshPlayer extends PlayerScreen {

    /** Classpath directory (in the jar) and working-directory directory scanned for exports. */
    private static final String ASSET_DIR = "meshes";

    /**
     * The example export that ships inside the player jar, {@code <assets>/meshes/fire-export.json}
     * with its atlas {@code fire_texture.png} next to it.
     *
     * <p>Played when no {@code meshes} directory is found next to the working directory — which is
     * exactly the situation of an installed app-image, where the assets live inside the jar. The
     * path is then resolved against the classpath, see {@link #handleFor(String)}.
     */
    static final String EXAMPLE_EXPORT = "meshes/fire-export.json";

    /**
     * How many directories above {@code user.dir} are searched for relative asset paths. This
     * lets a repo-relative path such as {@code assets/meshes/foo.json} resolve when the player
     * was started from a module directory such as {@code mesheditor} or {@code desktop}.
     */
    private static final int MAX_PARENT_LEVELS = 4;

    /**
     * Fallback cycle length in seconds for animations that carry <b>no timing of their own</b>
     * (single-frame {@code rest} poses, legacy pose bakes without keyframe times). Every animation
     * that has keyframe times — i.e. everything the mesheditor exports — is played at its authored
     * length; see {@link #frameIndex(VatAnimationData, float)}.
     */
    private static final float ANIM_SECONDS = 4f;

    /**
     * Default number of GPU-instanced copies per animation row.
     */
    private static final int DEFAULT_INSTANCE_COUNT = 100;

    // -----------------------------------------------------------------------
    private Array<String> exportPaths = new Array<>();
    private int currentIndex = 0;

    private MeshExport export;
    private List<Anim> anims = new ArrayList<>();
    private float[] bounds = new float[4]; // minX, minY, maxX, maxY

    /**
     * Z extent of the loaded geometry. The mesh {@code z} is the editor's draw-order coordinate and
     * is passed through to {@code gl_Position} unchanged, so the camera's near/far plane must cover
     * it (otherwise vertices with a tiny non-zero z are clipped away).
     */
    private float boundsMinZ, boundsMaxZ;

    /** True once a data set (JSON export or {@code .vat} file) loaded successfully. */
    private boolean loaded;

    /** Non-null when the last load failed; shown instead of the "no exports" hint. */
    private String loadError;

    /** Static mesh of a loaded {@code .vat} file; {@code null} for JSON exports. */
    private VatMeshData vatMesh;

    /**
     * Model texture of the loaded JSON export (export version 6), or {@code null} when the mesh
     * has none. One picture serves every animation batch of the export, so the player owns and
     * disposes it — {@link VatInstancedMesh} never does.
     */
    private Texture exportTexture;

    private OrthographicCamera camera;
    private SpriteBatch batch;
    private BitmapFont font;
    private float time = 0f;

    /**
     * The navigation bar built on the stage/skin that {@link PlayerScreen} owns, so the
     * {@link NavBar} stays anchored to the bottom-right corner at every window size and its layout
     * lives in {@link com.jb.rts.player.ui.UiLayout}.
     */
    private NavBar navBar;

    /**
     * Text overlay from the shared {@code :ui} toolkit: file name (top-left), animation label +
     * statistics (bottom-left) and the zoom factor (top-right).
     */
    private final HudOverlay hud = new HudOverlay();

    /**
     * Number of objects (GPU instances) actually submitted for drawing in the last frame; shown
     * in the bottom-left overlay next to the current animation label.
     */
    private int drawnObjectCount;

    // Screen-space UI layout (pixels, GL bottom-left origin).
    private float viewW, viewH;

    /**
     * GPU-only shader test mode: 0 = normal colours, 1 = magenta override, 2 = white override.
     */
    private int testMode;

    // ── Camera interactive controls ──
    /**
     * Zoom multiplier (1 = no zoom).
     */
    private float cameraZoom = 1f;
    /**
     * Pan offset in world coordinates – added to the camera's target position.
     */
    private float cameraPanX, cameraPanY;

    // -----------------------------------------------------------------------
    // Standalone (single-file) mode fields
    // -----------------------------------------------------------------------

    /**
     * Non-null when the player was started to display a single file (no directory scan).
     */
    private String standaloneFilePath;

    /**
     * Number of copies to render (overrides {@link #DEFAULT_INSTANCE_COUNT} when in standalone mode).
     */
    private int instanceCount = DEFAULT_INSTANCE_COUNT;

    /**
     * Screen diagonal in model units (mm by convention). Controls the viewport size so the
     * model appears at a reasonable physical scale. Only used in standalone mode.
     */
    private float screenDiagonalMm = 500f;

    /**
     * Cached random positions for standalone mode – generated once in {@link #create()}.
     */
    private float[][] standalonePositions;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor – scans the assets/meshes directory (original behaviour).
     */
    public MeshPlayer() {
    }

    /**
     * Standalone constructor – loads a single export file and displays N copies at random
     * positions sorted by y-coordinate for z-ordering.
     *
     * @param filePath   absolute path to the .json mesh export
     * @param copies     number of GPU-instanced copies to display
     * @param screenDiag screen diagonal in millimetres; converted to the export's
     *                   measurement unit to size the viewport so the model appears life-size
     *                   on a display of that diagonal
     */
    public MeshPlayer(String filePath, int copies, float screenDiag) {
        this.standaloneFilePath = filePath;
        this.instanceCount = Math.max(1, copies);
        this.screenDiagonalMm = Math.max(1f, screenDiag);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate() {
        font = new BitmapFont();
        batch = new SpriteBatch();

        // Scene2D navigation UI: the stage and the skinned font come from PlayerScreen (assets/skin/
        // uiskin.json or the programmatic fallback), NavBar/UiLayout own the layout, so this bar
        // looks exactly like every other screen using the toolkit.
        navBar = new NavBar(skin)
                .onLoad(this::chooseExportFile)
                .onPrev(this::prevAnim)
                .onNext(this::nextAnim)
                .onClose(() -> Gdx.app.exit())
                .attachTo(stage);

        if (standaloneFilePath != null) {
            // ── Standalone mode: load one specific file ──
            Gdx.app.log("MeshPlayer", "Standalone mode – loading file: " + standaloneFilePath);
            java.io.File f = resolveStandaloneFile(standaloneFilePath);
            if (f != null) {
                try {
                    exportPaths.add(f.getAbsolutePath());
                    loadFile(0);
                    if (loaded) {
                        // Generate stable random positions once; sorted by y (z-order).
                        generateStandalonePositions();
                        // Initialise the camera now so renderSingle has a valid projection.
                        updateCamera();
                    }
                } catch (Exception e) {
                    // Keep the render loop from treating a half-initialised export as valid:
                    // render() reports the failure instead of scanning for other files.
                    export = null;
                    loaded = false;
                    exportPaths.clear();
                    Gdx.app.error("MeshPlayer", "Failed to load standalone file: "
                            + f.getAbsolutePath(), e);
                }
            } else {
                Gdx.app.error("MeshPlayer", "Standalone file not found: " + standaloneFilePath);
            }
            return;
        }

        // ── Directory mode: scan the meshes directory next to the working directory ──
        // In the repository this finds player/assets/meshes; an installed app-image has no such
        // directory (its assets live inside the jar), so the bundled example is played then.
        java.io.File meshesDir = resolveMeshesDir();
        if (meshesDir == null) {
            Gdx.app.log("MeshPlayer", "No 'meshes' directory found — playing the bundled example '"
                    + EXAMPLE_EXPORT + "'.");
            exportPaths.add(EXAMPLE_EXPORT);
        } else {
            java.io.File[] listing = meshesDir.listFiles();
            if (listing == null) listing = new java.io.File[0];
            Gdx.app.log("MeshPlayer", "Scanning directory: " + meshesDir.getAbsolutePath()
                    + " (" + listing.length + " entries)");
            Array<String> files = new Array<>();
            for (java.io.File f : listing) {
                if (!f.isFile()) continue;
                String fileName = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (fileName.endsWith(".json")) {
                    files.add(f.getAbsolutePath());
                } else if (fileName.endsWith(".vat")) {
                    // Baked VAT files are shader-ready; no validation needed beyond the header.
                    exportPaths.add(f.getAbsolutePath());
                    Gdx.app.log("MeshPlayer", "  candidate (vat): " + f.getAbsolutePath());
                }
            }
            for (String path : files) {
                Gdx.app.log("MeshPlayer", "  candidate: " + path);
                try {
                    MeshExport e = MeshExportLoader.load(handleFor(path));
                    if (e.meshes != null && !e.meshes.isEmpty()) {
                        exportPaths.add(path); // parses as a mesh export
                        Gdx.app.log("MeshPlayer", "  -> parsed as mesh export ("
                                + e.meshes.size() + " poses)");
                    }
                } catch (Exception ignore) {
                    Gdx.app.log("MeshPlayer", "Ignoring file " + path);
                }
            }
            Gdx.app.log("MeshPlayer", "Found " + exportPaths.size + " mesh export(s) in "
                    + meshesDir.getAbsolutePath());
            // A directory without a single playable export still gets the example, so the player
            // never opens an empty window.
            if (exportPaths.isEmpty()) exportPaths.add(EXAMPLE_EXPORT);
        }

        if (!exportPaths.isEmpty()) {
            loadFile(0);
        }

        // Ensure the camera is always initialised – even in directory-scanning mode,
        // because renderSingle() uses camera.combined. The resize() callback only
        // re-calculates when camera != null, so we must create it here.
        if (camera == null && loaded) {
            updateCamera();
        }
    }

    // -----------------------------------------------------------------------
    // Loading another export (Load… button / drag & drop)
    // -----------------------------------------------------------------------

    /**
     * Plays the export at {@code path} — a {@code .json} mesh export or a baked {@code .vat} file —
     * and remembers it next to the exports that are already known, so the example stays reachable.
     * Nothing happens for a {@code null}/blank path.
     *
     * <p>Call it on the render thread; the {@link com.jb.rts.player.desktop.MeshPlayerLauncher}
     * forwards drag &amp; dropped files here through {@code Gdx.app.postRunnable}.
     *
     * @param path absolute path of the file (a path relative to the working directory or the
     *             classpath is accepted too, see {@link #handleFor(String)})
     */
    public void openExport(String path) {
        if (path == null || path.isBlank()) return;
        exportPaths = normalizedOrder(exportPaths);
        int index = exportPaths.indexOf(path, false);
        if (index < 0) {
            exportPaths.add(path);
            index = exportPaths.size - 1;
        }
        loadFile(index);
        if (loaded) {
            // The bounds changed, so the viewport has to be re-fitted; in standalone mode the
            // scattered copies are regenerated to stay inside the new bounds.
            cameraPanX = 0f;
            cameraPanY = 0f;
            if (standaloneFilePath != null) generateStandalonePositions();
            updateCamera();
        }
    }

    /**
     * Opens the system file chooser and plays the export the user picks.
     *
     * <p>LWJGL3 has no native file dialog, so Swing's {@link JFileChooser} is used. It must
     * <b>not</b> run on the render thread (that would freeze the window while the dialog is open),
     * so it is opened on a daemon thread and the load is handed back to the render thread with
     * {@code Gdx.app.postRunnable}. The jpackage app-image bundles {@code java.desktop} for this,
     * see {@code player/build.gradle}.
     */
    private void chooseExportFile() {
        Thread chooser = new Thread(() -> {
            try {
                JFileChooser dialog = new JFileChooser();
                dialog.setDialogTitle("Open mesh export");
                dialog.setFileFilter(new FileNameExtensionFilter(
                        "Mesh export (*.json, *.vat)", "json", "vat"));
                java.io.File dir = resolveMeshesDir();
                if (dir != null) dialog.setCurrentDirectory(dir);
                if (dialog.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
                java.io.File chosen = dialog.getSelectedFile();
                if (chosen == null) return;
                String path = chosen.getAbsolutePath();
                Gdx.app.postRunnable(() -> openExport(path));
            } catch (RuntimeException e) {
                Gdx.app.error("MeshPlayer", "File chooser failed: " + e.getMessage(), e);
            }
        }, "animesh-export-chooser");
        chooser.setDaemon(true);
        chooser.start();
    }

    /** The player's keys and drag handling run after the stage, so the buttons win over them. */
    @Override
    protected InputProcessor afterStage() {
        return new MeshPlayerInput();
    }

    /**
     * Draws the instance grid and the HUD overlay. The {@link NavBar} is drawn on top of both by
     * {@link PlayerScreen}.
     *
     * @param delta frame time in seconds, already clamped by {@link PlayerScreen}
     * @return {@code true} when the UI should be drawn; {@code false} while only a load error is on
     *         screen (there is nothing to page through then)
     */
    @Override
    protected boolean onRender(float delta) {
        time += delta;
        viewW = Gdx.graphics.getWidth();
        viewH = Gdx.graphics.getHeight();

        Gdx.gl.glClearColor(0.10f, 0.11f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!loaded) {
            if (standaloneFilePath != null) {
                // Do not fall back to the directory scan here: in standalone mode the only
                // relevant file is the one that was passed in, and its path resolution was
                // already logged by resolveStandaloneFile().
                drawMessage("Could not load '" + shortName(standaloneFilePath)
                        + (loadError != null ? "': " + loadError : "'. See the log for the paths that were tried."));
                return false;
            }
            if (loadError != null) {
                drawMessage("Could not load '" + shortName(exportPaths.size > 0
                        ? exportPaths.get(currentIndex) : ASSET_DIR) + "': " + loadError);
                return false;
            }
            java.io.File dir = resolveMeshesDir();
            String p = dir != null ? dir.getAbsolutePath() : "meshes (directory not found)";
            drawMessage("No mesh exports found in '" + ASSET_DIR + "'. Looked in: " + p);
            return false;
        }

        if (camera == null) {
            Gdx.app.error("MeshPlayer", "camera is null – cannot render (directory scan mode needs updateCamera in create)");
            drawMessage("Camera not initialised. Use standalone mode (pass file path) or check create().");
            return false;
        }

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        try {
            renderSingle();
        } catch (Exception e) {
            Gdx.app.error("MeshPlayer", "renderSingle() threw: " + e.getMessage(), e);
        }
        try {
            drawOverlay();
        } catch (Exception e) {
            Gdx.app.error("MeshPlayer", "drawOverlay() threw: " + e.getMessage(), e);
        }
        // The nav bar (drawn on top of the overlay text by PlayerScreen) is the last thing on screen.
        return true;
    }

    /**
     * Holds the currently playing animation index so the overlay can label it.
     */
    private int currentAnim = 0;

    /**
     * Fits the camera to the instance grid so that all {@code instanceCount} copies are visible.
     * In standalone mode the viewport height is set to {@link #screenDiagonalMm}.
     */
    private void updateCamera() {
        float objW = Math.max(1e-3f, bounds[2] - bounds[0]);
        float objH = Math.max(1e-3f, bounds[3] - bounds[1]);
        float cell = Math.max(objW, objH);
        float spacing = cell * 1.2f;
        int cols = (int) Math.sqrt(instanceCount);
        float gridW = cols * spacing + cell;
        float gridH = cols * spacing + cell;

        // During create() the window dimensions may not be available yet; fall back
        // to a 16:9 aspect ratio so the camera is never degenerate.
        float w = Math.max(viewW, 1f);
        float h = Math.max(viewH, 1f);
        float ar = w / h;
        float margin = 1.15f;
        float vpH;
        if (standaloneFilePath != null) {
            // Physical-scale viewport: screen diagonal expressed in model units
            // (e.g. 500 mm / 1000 mm·m⁻¹ = 0.5 m).  This is almost always much
            // smaller than the object itself, so we also compute the grid-based
            // viewport and pick whichever is LARGER — the object should be visible.
            float physicalVpH = MetricUnit.MILLIMETER.convert(screenDiagonalMm, exportUnit());
            float gridVpH = Math.max(gridH * margin, gridW * margin / ar);
            vpH = Math.max(physicalVpH, gridVpH);
        } else {
            vpH = Math.max(gridH * margin, gridW * margin / ar);
        }
        float vpW = vpH * ar;

        camera = new OrthographicCamera(vpW / cameraZoom, vpH / cameraZoom);
        // The VAT shader forwards a_position.z (draw order) unchanged; a near plane of 0 clips
        // every vertex with a tiny non-zero z. Cover the data's whole z range instead.
        float zLimit = Math.max(Math.abs(boundsMinZ), Math.abs(boundsMaxZ)) + 10f;
        camera.near = -zLimit;
        camera.far = zLimit;
        float targetX = (bounds[0] + bounds[2]) * 0.5f + cameraPanX;
        float targetY = (bounds[1] + bounds[3]) * 0.5f + cameraPanY;
        camera.position.set(targetX, targetY, 0f);
        // World space is the standard math coordinate system: +x right, +y up. y-up exports are
        // used as-is; only y-down ones are mirrored when they are loaded (see
        // convertExportToWorldYUp), so no camera rotation or mirrored instance positions are
        // needed.
        camera.update();
    }

    /**
     * Renders the selected animation (via {@link #currentAnim}) via GPU instancing.
     * The animation loops continuously; use Prev/Next to pick another animation.
     */
    private void renderSingle() {
        drawnObjectCount = 0;
        if (anims.isEmpty()) return;

        int animIndex = currentAnim;
        if (animIndex < 0 || animIndex >= anims.size()) animIndex = 0;
        Anim anim = anims.get(animIndex);
        if (anim.group == null) return;

        // Loop the selected animation continuously, at the speed it was authored in the
        // mesheditor: the cycle length is the clip's own duration (time of its last keyframe,
        // taken from the export's "frameTimes"), not a fixed number of seconds.
        //
        // VAT skinning: the vertex shader fetches the bone matrices of frame floor(f) and
        // floor(f)+1 from the RGBA32F texture and mixes them by fract(f). Fractional frame
        // indexes therefore reproduce the CPU blend renderer exactly (see VatPoseBaker).
        float frame = frameIndex(anim.group.animation(), time);

        // ---- Fill the instance buffer ----
        anim.group.begin();

        if (standaloneFilePath != null && instanceCount > 1) {
            // ── Standalone mode: stable pre-generated random positions sorted by y (z-order) ──
            float[][] pos = standalonePositions;
            if (pos != null) {
                for (int i = 0; i < pos.length && i < instanceCount; i++) {
                    anim.group.add(pos[i][0], pos[i][1], frame);
                }
            }
        } else {
            // ── Grid layout (original / fallback) ──
            float spacing = cellSize() * 1.2f;
            int cols = (int) Math.sqrt(instanceCount);
            float ox = -(cols * spacing) * 0.5f;
            float oy = -(cols * spacing) * 0.5f;
            for (int i = 0; i < instanceCount; i++) {
                float x = ox + (i % cols) * spacing + spacing * 0.5f;
                float y = oy + (i / cols) * spacing + spacing * 0.5f;
                anim.group.add(x, y, frame);
            }
        }

        // ---- ONE draw call for all instances ----
        anim.group.render(camera.combined);
        drawnObjectCount = anim.group.instanceCount();
    }

    /**
     * Generates stable random positions for standalone mode, sorted by y (z-order).
     * World space is +x right / +y up, matching the loaded (y-up) mesh coordinates.
     */
    private void generateStandalonePositions() {
        // The screen diagonal is specified in millimetres; convert it to the export's unit
        // with MetricUnit so models appear at their intended physical scale.
        float diagModelUnits = MetricUnit.MILLIMETER.convert(screenDiagonalMm, exportUnit());
        float range = diagModelUnits * 0.45f; // spread within ~90 % of the viewport
        float cx = (bounds[0] + bounds[2]) * 0.5f;
        float cy = (bounds[1] + bounds[3]) * 0.5f;
        java.util.Random rng = new java.util.Random();
        float[][] pos = new float[instanceCount][2];
        for (int i = 0; i < instanceCount; i++) {
            pos[i][0] = cx + (rng.nextFloat() - 0.5f) * 2f * range;
            pos[i][1] = cy + (rng.nextFloat() - 0.5f) * 2f * range;
        }
        // Sort by y descending — higher y drawn first (behind), lower y drawn last (on top).
        java.util.Arrays.sort(pos, (a, b) -> Float.compare(b[1], a[1]));
        standalonePositions = pos;
    }

    /**
     * Measurement unit of the loaded export. The mesheditor writes the unit into the export
     * ({@code mm}, {@code cm}, {@code m} or {@code km}); missing/unknown values default to
     * millimetres (see {@link MeshExportLoader}).
     */
    private MetricUnit exportUnit() {
        if (export == null || export.measurement == null) return MetricUnit.MILLIMETER;
        return switch (export.measurement.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "km" -> MetricUnit.KILOMETER;
            case "m"  -> MetricUnit.METER;
            case "cm" -> MetricUnit.CENTIMETER;
            default   -> MetricUnit.MILLIMETER;
        };
    }

    /**
     * Maps the player's wall-clock time onto an animation's own timeline.
     *
     * <p>The cycle length is {@link VatAnimationData#duration()} — the time of the clip's last
     * keyframe, which {@code MeshExportLoader} reads from the export's {@code "frameTimes"}
     * (seconds). Those times come straight from the mesheditor timeline, so the player now runs
     * every animation at exactly the speed the editor previews it (the dino's {@code walk} is
     * authored as 1&nbsp;s, not 4&nbsp;s). {@link VatAnimationData#frameAt(float)} then converts
     * the time into a <b>fractional frame index</b> using the same keyframe times, so clips with
     * unevenly spaced keyframes (e.g. {@code die}: 18 frames in the first second, then a
     * 1&nbsp;s hold) keep their internal timing instead of being stretched uniformly over the frame
     * count.
     *
     * <p>{@link #ANIM_SECONDS} is only used when the clip carries no usable timing — a single-frame
     * {@code rest} pose ({@code duration() == 0}) or a legacy pose bake baked without keyframe
     * times.
     *
     * <p>The loop point is safe for both looping and non-looping clips: the vertex shader wraps
     * {@code floor(f) + 1} back to frame 0 with a blend weight of {@code 0}, so the last frame is
     * shown in full and the clip never interpolates from the last pose back to the first.
     *
     * @param animation baked animation of the row being drawn
     * @param seconds   time since the row was selected, in seconds (may be any non-negative value)
     * @return fractional frame index for {@code a_instanceFrame} ({@code 0 .. frameCount-1})
     */
    static float frameIndex(VatAnimationData animation, float seconds) {
        float duration = animation.duration();
        if (!(duration > 1e-6f)) duration = ANIM_SECONDS; // covers NaN and single-frame clips
        float localTime = seconds <= 0f ? 0f : seconds % duration;
        return animation.frameAt(localTime);
    }

    /**
     * Fills and draws the shared {@link HudOverlay} (positions, colours and scales come from
     * {@link com.jb.rts.player.ui.UiLayout}): file name on top, the current animation plus the render statistics at
     * the bottom and the zoom factor in the top-right corner.
     */
    private void drawOverlay() {
        // Top line: file name.
        hud.title(shortName(exportPaths.get(currentIndex)));

        // Bottom-left: current animation name (primary), followed by render statistics
        // (number of objects on screen and current frame rate). An empty primary label simply
        // makes the statistics start at the left margin.
        if (currentAnim >= 0 && currentAnim < anims.size()) {
            Anim anim = anims.get(currentAnim);
            hud.primary((currentAnim + 1) + "/" + anims.size() + "  " + anim.name);
        } else {
            hud.primary(null);
        }
        hud.stats(drawnObjectCount + (drawnObjectCount == 1 ? " object" : " objects"));
        hud.info(Gdx.graphics.getFramesPerSecond() + " fps");

        // Zoom indicator (top-right corner) — only while the camera is actually zoomed.
        hud.corner(Math.abs(cameraZoom - 1f) > 0.01f
                ? String.format("x%.1f", cameraZoom) : null);

        hud.draw(batch, font, viewW, viewH);
    }

    /** Enables Prev/Next only when there is more than one animation; Close is always enabled. */
    private void updateNavigationVisibility() {
        if (navBar == null) return;
        navBar.setNavEnabled(anims.size() > 1);
    }

    private void drawMessage(String msg) {
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, msg, 20, viewH * 0.6f);
        batch.end();
    }

    // ---- Animation navigation ---- //

    private void nextAnim() {
        if (anims.isEmpty()) {
            currentAnim = 0;
            return;
        }
        currentAnim = (currentAnim + 1) % anims.size();
        time = 0f;
    }

    private void prevAnim() {
        if (anims.isEmpty()) {
            currentAnim = 0;
            return;
        }
        currentAnim--;
        if (currentAnim < 0) currentAnim = anims.size() - 1;
        time = 0f;
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private class MeshPlayerInput extends InputAdapter {
        @Override
        public boolean keyDown(int keycode) {
            switch (keycode) {
                case Input.Keys.ESCAPE:
                    Gdx.app.exit();
                    return true;
                case Input.Keys.RIGHT:
                case Input.Keys.N:
                case Input.Keys.SPACE:
                    nextAnim();
                    return true;
                case Input.Keys.LEFT:
                case Input.Keys.P:
                    prevAnim();
                    return true;
                case Input.Keys.A:
                    cameraPanX -= cellSize() * 0.2f;
                    updateCamera();
                    return true;
                case Input.Keys.D:
                    cameraPanX += cellSize() * 0.2f;
                    updateCamera();
                    return true;
                case Input.Keys.W:
                case Input.Keys.UP:
                    cameraPanY += cellSize() * 0.2f;
                    updateCamera();
                    return true;
                case Input.Keys.S:
                case Input.Keys.DOWN:
                    cameraPanY -= cellSize() * 0.2f;
                    updateCamera();
                    return true;
                case Input.Keys.C:
                    testMode = (testMode + 1) % 3;
                    applyTestMode();
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            float factor = (amountY < 0) ? 1.15f : 1f / 1.15f;
            cameraZoom = Math.max(0.02f, Math.min(cameraZoom * factor, 100f));
            updateCamera();
            return true;
        }
    }

    private void loadFile(int index) {
        for (Anim a : anims) if (a.group != null) a.group.dispose();
        disposeExportTexture();

        exportPaths = normalizedOrder(exportPaths);
        currentIndex = ((index % exportPaths.size) + exportPaths.size) % exportPaths.size;
        String path = exportPaths.get(currentIndex);

        export = null;
        vatMesh = null;
        anims = new ArrayList<>();
        loaded = false;
        loadError = null;

        try {
            if (isVatPath(path)) {
                loadVat(path);
            } else {
                loadJsonExport(path);
            }
            loaded = true;
        } catch (RuntimeException e) {
            loadError = e.getMessage() != null ? e.getMessage() : e.toString();
            Gdx.app.error("MeshPlayer", "Failed to load '" + path + "': " + loadError, e);
            for (Anim a : anims) if (a.group != null) a.group.dispose();
            disposeExportTexture();
            anims = new ArrayList<>();
            export = null;
            vatMesh = null;
            return;
        }

        applyTestMode();
        updateNavigationVisibility();
        time = 0f;
        try {
            Gdx.graphics.setTitle("Mesh Player — " + shortName(path));
        } catch (Throwable ignored) {
            Gdx.app.log("MeshPlayer", "Cannot set title: " + "Mesh Player — " + shortName(path));
        }
        Gdx.app.log("MeshPlayer", "Loaded '" + path + "': " + anims.size() + " animation(s).");
    }

    /** True for baked {@code .vat} files (case-insensitive). */
    private static boolean isVatPath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".vat");
    }

    /** JSON export: load the poses and bake them through the skin (or pose fallback) path. */
    private void loadJsonExport(String path) {
        export = MeshExportLoader.load(handleFor(path));
        if (export.skippedTriangles > 0) {
            // Damaged meshes (e.g. references to a removed vertex) must not stop the preview,
            // but the user should know that part of the geometry is missing.
            Gdx.app.log("MeshPlayer", "WARNING: '" + shortName(path) + "' skipped "
                    + export.skippedTriangles
                    + " triangle(s) that reference non-existent vertices.");
        }
        convertExportToWorldYUp(export);
        exportTexture = loadExportTexture(path, export);
        anims = buildAnims(export);
        computeBounds();
        Gdx.app.log("MeshPlayer", export.skin != null
            ? "JSON export: " + export.skin.boneCount() + " bone row(s), "
                + export.skinClips.size() + " baked clip(s)"
            : "JSON export without bones: using the per-vertex pose fallback");
    }

    /**
     * Loads the export's model texture (version 6) from the JSON file's directory, or returns
     * {@code null} when the export has none.
     *
     * <p>Failures never abort the load: an export that names a texture the checkout does not
     * contain still plays with its per-corner palette colours, which is exactly how the player
     * behaved before textures were exported.
     */
    private Texture loadExportTexture(String exportPath, MeshExport e) {
        if (!e.hasTexture()) return null;
        if (!e.hasUvs()) {
            Gdx.app.log("MeshPlayer", "WARNING: '" + shortName(exportPath) + "' declares texture '"
                + e.textureFile + "' but carries no uvVertices — drawing its palette colours.");
            return null;
        }
        // Same resolution as the export itself: next to the JSON on disk, or inside the jar.
        FileHandle textureFile = handleFor(exportPath).parent().child(e.textureFile);
        if (!textureFile.exists()) {
            Gdx.app.error("MeshPlayer", "Texture '" + e.textureFile + "' not found at "
                + textureFile.path() + " — drawing the palette colours instead.");
            return null;
        }
        Texture texture = new Texture(textureFile);
        // The atlas is authored at a fixed resolution and drawn at arbitrary scale.
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        Gdx.app.log("MeshPlayer", "Model texture: " + textureFile.name() + " ("
            + texture.getWidth() + "x" + texture.getHeight() + ")");
        return texture;
    }

    /** Releases the export texture (owned by the player, shared by all animation batches). */
    private void disposeExportTexture() {
        if (exportTexture != null) {
            exportTexture.dispose();
            exportTexture = null;
        }
    }

    /** Baked VAT file: every clip becomes one shader-ready instanced batch. */
    private void loadVat(String path) {
        VatFile.FileContent content = VatFile.loadAll(handleFor(path));
        vatMesh = content.mesh();
        List<Anim> result = new ArrayList<>();
        for (VatAnimationData animation : content.animations()) {
            result.add(new Anim(animation.name(), animation.frameCount(),
                new VatInstancedMesh(vatMesh, animation, Math.max(1, instanceCount)), null));
        }
        anims = result;
        computeBounds();
        Gdx.app.log("MeshPlayer", "VAT file: " + vatMesh.vertexCount() + " vertices, "
            + content.animations().size() + " clip(s)");
    }

    /**
     * Re-orients the loaded poses into the player's world space, the standard math system
     * (<b>+x right, +y up</b>).
     *
     * <p>The mesheditor is a JavaFX editor whose y axis grows <b>downward</b>, but its exporter
     * negates every written y value by default ({@code JsonExportOptions.cartesianYUp}, the
     * <em>Cartesian coordinates (y up)</em> checkbox, on by default) and records that in the
     * export's {@code "yUp"} flag. Such an export is <b>already</b> in the player's convention
     * and must be used as-is — mirroring it again makes the mesh appear upside-down/backwards.
     * Only exports that explicitly kept the editor's y-down screen space
     * ({@code "yUp": false}) are mirrored once here.
     *
     * <p>When a mirror is required, the base positions and the per-frame offsets are both
     * negated so that the reconstruction {@code defaultPositions + positions} stays consistent
     * for every animation frame.
     *
     * <p>Every pose of one export shares the <b>same</b> {@code defaultPositions} array
     * (see {@link MeshExportLoader}), so the base array must be flipped exactly once instead of
     * once per pose — flipping it once per pose would cancel out for an even pose count while
     * leaving the offsets mirrored.
     */
    static void convertExportToWorldYUp(MeshExport e) {
        if (e.yUp) return;
        // Identity-based set: poses of one export share the same base array instance.
        java.util.Set<float[]> flippedBase =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlendableMesh m : e.meshes) {
            if (flippedBase.add(m.defaultPositions)) {
                flipY(m.defaultPositions);
            }
            flipY(m.positions);
        }
        // The baked skin must be mirrored the same way: bind transforms for tooling and the
        // affine matrices (m01, m10 and m13 change sign under y -> -y).
        if (e.skin != null) e.skin.flipY();
        for (MeshSkinClip clip : e.skinClips) clip.flipY();
    }

    /** Negates the y component of an interleaved {@code [x, y, z, …]} array. */
    private static void flipY(float[] xyz) {
        for (int i = 1; i < xyz.length; i += BlendableMesh.POSITION_FLOATS) {
            xyz[i] = -xyz[i];
        }
    }

    private List<Anim> buildAnims(MeshExport e) {
        // Group the poses by their animation name (second name segment), preserving order.
        Map<String, List<BlendableMesh>> byName = new LinkedHashMap<>();
        for (BlendableMesh m : e.meshes) {
            String anim = m.poseName();
            if (anim == null || anim.isEmpty()) anim = "rest";
            byName.computeIfAbsent(anim, k -> new ArrayList<>()).add(m);
        }

        Gdx.app.log("MeshPlayer", "buildAnims: " + byName.size() + " groups from "
                + e.meshes.size() + " meshes");
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            Gdx.app.log("MeshPlayer", "  group '" + entry.getKey() + "': "
                    + entry.getValue().size() + " frames");
        }

        List<Anim> result = new ArrayList<>();
        MeshSkin skin = e.skin;
        BlendableMesh bindPose = e.meshes.isEmpty() ? null : e.meshes.get(0);
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlendableMesh> frames = entry.getValue();
            VatInstancedMesh group = null;
            String error = null;
            try {
                if (skin != null && bindPose != null) {
                    // Real bones: the export's bone table drives the shader, so the texture only
                    // needs one row per deforming bone instead of one per expanded vertex.
                    MeshSkinClip clip = clipNamed(e.skinClips, name);
                    MeshSkin groupSkin = skin;
                    MeshSkinClip groupClip = clip;
                    VatVertexOffsetBaker.Result patched = null;
                    if (clip != null) {
                        // Vertices dragged by hand in the animation panel are not part of the bone
                        // matrices; VatVertexOffsetBaker appends one matrix row per such vertex so
                        // the shader reproduces the authored pose exactly (bones + hand edits).
                        patched = VatVertexOffsetBaker.bake(bindPose, skin, clip, frames);
                        if (patched != null) {
                            groupSkin = patched.skin();
                            groupClip = patched.clip();
                        }
                    }
                    VatMeshData meshData = VatSkinBaker.meshData(bindPose, groupSkin, e.palette,
                            e.stepVertices, e.uvs);
                    VatAnimationData animation;
                    if (groupClip != null) {
                        // The frames carry the export's per-frame colours, so the animation can
                        // blend them exactly like the CPU blend renderer did (VatFrameColorTexture).
                        animation = VatSkinBaker.animation(e.name + "_" + name, groupSkin, groupClip,
                                frames, e.palette);
                    } else if ("rest".equals(name)) {
                        animation = VatSkinBaker.restAnimation(groupSkin, frameRate(1));
                    } else {
                        animation = null; // declared but not baked: use the pose fallback below
                    }
                    if (animation != null) {
                        group = new VatInstancedMesh(meshData, animation,
                                Math.max(1, instanceCount), textureFor(meshData));
                        Gdx.app.log("MeshPlayer", "  -> VAT skin group '" + name + "' created ("
                                + groupSkin.boneCount() + " matrix rows, " + animation.frameCount()
                                + " frames" + colorInfo(group) + vertexOffsetInfo(patched) + ")");
                    }
                }
                if (group == null) {
                    // Legacy export without bone weights: one translation bone per expanded
                    // vertex reproduces the authored snapshots exactly (see VatPoseBaker).
                    boolean[] attachmentSteps = e.animationStepFrames.get(name);
                    if (attachmentSteps != null && attachmentSteps.length != frames.size()) {
                        attachmentSteps = null;
                    }
                    VatPoseBaker.Result baked = VatPoseBaker.bake(
                            e.name + "_" + name, frames, e.palette, frameRate(frames.size()),
                            e.stepVertices, attachmentSteps, e.uvs);
                    group = new VatInstancedMesh(baked.mesh(), baked.animation(),
                            Math.max(1, instanceCount), textureFor(baked.mesh()));
                    Gdx.app.log("MeshPlayer", "  -> VAT pose group '" + name + "' created ("
                            + baked.mesh().vertexCount() + " vertices, "
                            + baked.animation().frameCount() + " frames" + colorInfo(group) + ")");
                }
            } catch (RuntimeException ex) {
                error = ex.getMessage();
                Gdx.app.error("MeshPlayer", "Cannot build VAT group for animation '"
                        + name + "': " + error);
            }
            result.add(new Anim(name,
                group != null ? group.animation().frameCount() : frames.size(), group, error));
        }
        return result;
    }

    /**
     * The export's model texture, but only for mesh data that actually carries UVs. The check
     * keeps a mismatched export (texture without {@code uvVertices}) from failing the whole
     * group build — it simply draws its palette colours instead.
     */
    private Texture textureFor(VatMeshData meshData) {
        return exportTexture != null && meshData.hasUvs() ? exportTexture : null;
    }

    /** Returns the baked clip called {@code name}, or {@code null}. */
    private static MeshSkinClip clipNamed(List<MeshSkinClip> clips, String name) {
        for (MeshSkinClip clip : clips) {
            if (clip.name.equals(name)) return clip;
        }
        return null;
    }

    /**
     * Log suffix describing the per-frame colour strip of a group ({@code ""} when the animation
     * needs none, because every frame uses the bind-pose colours).
     */
    private static String colorInfo(VatInstancedMesh group) {
        if (group == null || group.frameColorTexture() == null) return "";
        return ", per-frame colours "
            + group.frameColorTexture().width() + "x" + group.frameColorTexture().height();
    }

    /**
     * Log suffix describing the hand-animated vertices restored by
     * {@link VatVertexOffsetBaker} ({@code ""} when the clip is purely bone-driven).
     */
    private static String vertexOffsetInfo(VatVertexOffsetBaker.Result patched) {
        if (patched == null) return "";
        return ", " + patched.rowCount() + " hand-animated vertex row(s), max offset "
            + String.format(java.util.Locale.ROOT, "%.4f", patched.maxResidual());
    }

    /**
     * Nominal frame rate for the <b>pose-fallback</b> bake only (exports without a skin block).
     * Those poses carry no keyframe times, so the baked {@link VatAnimationData} gets
     * {@code frameTimes == null} and {@link VatAnimationData#duration()} would be
     * {@code (frameCount - 1) / fps}; choosing {@code fps} this way keeps the fallback at
     * {@link #ANIM_SECONDS} instead of an arbitrary length. Skinned exports are unaffected — they
     * always carry the authored keyframe times.
     */
    private static float frameRate(int frameCount) {
        return frameCount > 1 ? (frameCount - 1) / ANIM_SECONDS : 1f;
    }

    /**
     * Applies the current shader-test colour override to every animation group.
     */
    private void applyTestMode() {
        for (Anim a : anims) {
            if (a.group == null) continue;
            switch (testMode) {
                case 1 -> a.group.setOverrideColor(1f, 0f, 1f, 1f, true);   // magenta
                case 2 -> a.group.setOverrideColor(1f, 1f, 1f, 1f, true);   // white
                default -> a.group.setOverrideColor(0f, 0f, 0f, 1f, false); // normal
            }
        }
    }

    private void computeBounds() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        if (vatMesh != null) {
            float[] positions = vatMesh.positions();
            for (int i = 0; i + 2 < positions.length; i += 3) {
                float x = positions[i];
                float y = positions[i + 1];
                float z = positions[i + 2];
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (z > maxZ) maxZ = z;
            }
        }
        if (export != null) {
            for (BlendableMesh m : export.meshes) {
                for (int i = 0; i < m.positions.length; i += BlendableMesh.POSITION_FLOATS) {
                    float x = m.defaultPositions[i] + m.positions[i];
                    float y = m.defaultPositions[i + 1] + m.positions[i + 1];
                    float z = m.defaultPositions[i + 2] + m.positions[i + 2];
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (z > maxZ) maxZ = z;
                }
            }
        }
        if (minZ == Float.MAX_VALUE) minZ = 0f;
        if (maxZ == -Float.MAX_VALUE) maxZ = 0f;
        boundsMinZ = minZ;
        boundsMaxZ = maxZ;
        // Guard against degenerate/empty bounds.
        if (maxX - minX < 1e-4f) {
            maxX = minX + 1;
        }
        if (maxY - minY < 1e-4f) {
            maxY = minY + 1;
        }
        bounds[0] = minX;
        bounds[1] = minY;
        bounds[2] = maxX;
        bounds[3] = maxY;
    }

    private float cellSize() {
        return Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]);
    }

    /**
     * Locates the assets {@code meshes} directory, printing the <b>complete paths</b> it tries
     * so it is easy to debug why a file was (or was not) found.
     *
     * <p>Resolution order: an explicit {@code -DmeshPlayer.assetsDir=<path>} override, then
     * several sensible paths relative to {@code user.dir} (the working directory). This avoids
     * relying on {@code Gdx.files.internal(...)} which, on the desktop backend, only resolves
     * against the working directory/classpath root and may miss {@code assets/meshes}.
     *
     * @return the resolved directory, or {@code null} if none of the candidates exists
     */
    private java.io.File resolveMeshesDir() {
        java.io.File cwd = new java.io.File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        Gdx.app.log("MeshPlayer", "user.dir = " + cwd.getAbsolutePath());

        String override = System.getProperty("meshPlayer.assetsDir");
        if (override != null && !override.isBlank()) {
            java.io.File d = new java.io.File(override).getAbsoluteFile();
            Gdx.app.log("MeshPlayer", "meshPlayer.assetsDir override -> " + d.getAbsolutePath());
            if (d.isDirectory()) return d;
        }

        java.io.File dir = cwd;
        for (int level = 0; level <= MAX_PARENT_LEVELS; level++) {
            // At the working directory itself also accept a bare "meshes" folder (the
            // desktop gradle tasks run from the repo root, where "assets/meshes" lives).
            String[] rels = level == 0
                    ? new String[]{"meshes", "assets/meshes"}
                    : new String[]{"assets/meshes"};
            for (String rel : rels) {
                java.io.File d = new java.io.File(dir, rel).getAbsoluteFile();
                Gdx.app.log("MeshPlayer", "  checking: " + d.getAbsolutePath()
                        + (d.isDirectory() ? "  [exists]" : "  [missing]"));
                if (d.isDirectory()) return d;
            }
            java.io.File parent = dir.getParentFile();
            if (parent == null) break;
            dir = parent;
        }
        return null;
    }

    /**
     * Resolves the single export file passed in standalone mode and logs every candidate it
     * tries. Absolute paths are used as given. Relative paths are resolved against the working
     * directory first and then against up to {@link #MAX_PARENT_LEVELS} parent directories, so a
     * repo-relative path like {@code assets/meshes/dino_longneck-export.json} is found even when
     * the player was started from a sub-project directory (e.g. {@code mesheditor}).
     *
     * @return the existing file, or {@code null} if no candidate exists
     */
    private java.io.File resolveStandaloneFile(String path) {
        java.io.File cwd = new java.io.File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        if (new java.io.File(path).isAbsolute()) {
            Gdx.app.log("MeshPlayer", "Standalone path is absolute (user.dir = "
                    + cwd.getAbsolutePath() + ")");
        } else {
            Gdx.app.log("MeshPlayer", "user.dir = " + cwd.getAbsolutePath()
                    + " (relative path; also searching up to " + MAX_PARENT_LEVELS
                    + " parent directories)");
        }
        return findExistingFile(cwd, path, MAX_PARENT_LEVELS,
                f -> Gdx.app.log("MeshPlayer", "  checking: " + f.getAbsolutePath()
                        + (f.isFile() ? "  [exists]" : "  [missing]")));
    }

    /**
     * Resolves an export path to a readable {@link FileHandle}.
     *
     * <p>A file that exists on disk — an absolute path, or a path relative to the working
     * directory (the repository checkout, where {@code player/assets/meshes} lives) — is used as
     * it is. Otherwise the path is resolved against the <b>classpath</b>, which is where the
     * packaged example lives inside the player jar: libGDX's internal handles fall back to
     * {@code getResourceAsStream}, so an installed app-image can play its own assets.
     *
     * @param path path of the export (or of one of its auxiliary files, relative to the export)
     * @return a handle whose {@code read()} / {@code exists()} work in both situations
     */
    static FileHandle handleFor(String path) {
        FileHandle onDisk = Gdx.files.absolute(new java.io.File(path).getAbsolutePath());
        if (onDisk.exists() || new java.io.File(path).isAbsolute()) return onDisk;
        return Gdx.files.internal(path);
    }

    /**
     * Testable core of {@link #resolveStandaloneFile(String)}: returns the first existing file
     * for {@code path}. Absolute paths are checked once; relative paths are checked against
     * {@code startDir} and then each of its up to {@code maxParents} parent directories.
     *
     * @param startDir    directory to resolve relative paths against (usually {@code user.dir})
     * @param path        the (possibly relative) file path
     * @param maxParents  how many parent directories to try for relative paths
     * @param onCandidate optional observer called for every candidate that is tested
     * @return the existing file with an absolute path, or {@code null}
     */
    static java.io.File findExistingFile(java.io.File startDir, String path, int maxParents,
                                         java.util.function.Consumer<java.io.File> onCandidate) {
        java.io.File given = new java.io.File(path);
        if (given.isAbsolute()) {
            java.io.File candidate = given.getAbsoluteFile();
            if (onCandidate != null) onCandidate.accept(candidate);
            return candidate.isFile() ? candidate : null;
        }
        java.io.File dir = startDir.getAbsoluteFile();
        for (int level = 0; level <= maxParents; level++) {
            java.io.File candidate = new java.io.File(dir, path).getAbsoluteFile();
            if (onCandidate != null) onCandidate.accept(candidate);
            if (candidate.isFile()) return candidate;
            java.io.File parent = dir.getParentFile();
            if (parent == null) break;
            dir = parent;
        }
        return null;
    }

    private static Array<String> normalizedOrder(Array<String> in) {
        // keep as-is; just defensive copy
        Array<String> out = new Array<>();
        for (String s : in) out.add(s);
        return out;
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @Override
    protected void onResize(int width, int height) {
        viewW = width;
        viewH = height;
        // The HUD batch uses its own pixel projection; keep it in sync with the window,
        // otherwise the overlay text drifts/scales incorrectly after a resize or a
        // fullscreen resolution change.
        if (batch != null) {
            batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        }
        // Recalculate the camera now that we have real window dimensions – during
        // create() the camera was set up with viewW=viewH=0, giving a degenerate projection.
        if (camera != null) {
            updateCamera();
        }
    }

    /** The stage/skin are disposed by {@link PlayerScreen}, right after this. */
    @Override
    protected void onDispose() {
        for (Anim a : anims) if (a.group != null) a.group.dispose();
        disposeExportTexture();
        font.dispose();
        batch.dispose();
    }

    // -----------------------------------------------------------------------

    /**
     * One playable animation: its name / frame count plus the VAT batch that draws it.
     */
    private static final class Anim {
        final String name;
        final int frameCount;
        final VatInstancedMesh group;
        final String error; // non-null if the VAT batch could not be built

        Anim(String name, int frameCount, VatInstancedMesh group, String error) {
            this.name = name;
            this.frameCount = frameCount;
            this.group = group;
            this.error = error;
        }
    }
}
