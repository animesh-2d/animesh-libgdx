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
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.jb.rts.mesh.MetricUnit;
import com.jb.rts.mesh.blend.BlendableMesh;
import com.jb.rts.mesh.blend.ColorPalette;
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
import com.jb.rts.player.AnimationBlend;
import com.jb.rts.player.MeshFileResolver;
import com.jb.rts.player.InstanceField;
import com.jb.rts.player.ui.HudOverlay;
import com.jb.rts.player.ui.MeshPlayerControls;
import com.jb.rts.player.ui.NavBar;
import com.jb.rts.player.ui.PlayerScreen;
import com.jb.rts.player.ui.UiLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Mesh player: plays an <em>animesh mesh export</em> — one of the examples bundled into the jar on
 * startup, or any file the user opens. The current animation is drawn as a crowd of GPU instances.
 * The <b>export selector</b> in the navigation bar switches between the bundled examples (and
 * everything opened so far), the on-screen <b>◀ Prev / Next ▶</b> buttons (or the arrow keys)
 * step through the animations of the current file, <b>Load…</b> opens another export, and the
 * <b>Close</b> button (or <b>Esc</b>) exits the player.
 *
 * <p><b>Parameter panel</b> (top-left, collapsible, see {@link MeshPlayerControls}):</p>
 * <ul>
 *   <li><b>Count (Enter)</b> — how many copies are drawn. Type a number and confirm with
 *       <b>Enter</b>; every batch is pre-allocated for {@link #MAX_INSTANCE_COUNT} instances, so
 *       the change is instant. <b>I</b> focuses the field.</li>
 *   <li><b>Size deviation (%)</b> — each copy gets a random size within
 *       {@code 1 ± deviation} of the authored size.</li>
 *   <li><b>Placement</b> — a <b>grid</b> or a <b>random</b> scatter. Both are sorted by
 *       descending y, so lower copies are drawn in front ({@link InstanceField}).</li>
 *   <li><b>Blend with / Blend weight</b> — mixes the current animation with another clip of the
 *       same file (walk → run) using {@link com.jb.rts.mesh.vat.VatAnimationBlender}. Only the
 *       bone-matrix texture is re-uploaded while the weight changes
 *       ({@link VatInstancedMesh#updateAnimation}). <b>B</b> steps the blend target,
 *       <b>[</b> / <b>]</b> change the weight by 10&nbsp;%.</li>
 *   <li><b>Colours</b> — a table with one row per colour of the loaded file's palette (the JSON's
 *       {@code "colors"} array, see {@link com.jb.rts.mesh.blend.ColorPalette}). Every row opens the
 *       family's {@link com.jb.rts.player.ui.ColorPicker}; editing one recolours exactly the mesh
 *       parts that reference that colour, live while the picker is dragged
 *       ({@link VatInstancedMesh#refreshPalette(ColorPalette)}). A file without a palette
 *       ({@code .vat}) says so instead of showing rows.</li>
 * </ul>
 *
 * <p>The file name is shown at the top of the window. The bottom-left corner shows the current
 * animation (<em>e.g.</em> {@code 2/3  idle}, with the blend target and weight while blending),
 * followed by the number of objects drawn this frame and the current frame rate.</p>
 *
 * <p><b>UI:</b> the button bar, the panel and the overlay text come from the player's Scene2D
 * toolkit ({@link NavBar}, {@link MeshPlayerControls}, {@link HudOverlay},
 * {@link com.jb.rts.player.ui.UiLayout}) — the same layout the VAT demo and the rest of the
 * family use, edited in exactly one place.</p>
 *
 * <p><b>Failed loads</b> are reported in the window instead of the instance grid, but the
 * navigation bar stays visible: <b>Load…</b> can open another export and <b>Close</b> exits, so a
 * broken file never leaves the user with an unusable window.
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
     * The example exports <b>bundled into the player jar</b> (resources {@code meshes/…}, see
     * {@code player/build.gradle}), in the order the export selector offers them; the first one is
     * played on startup. An installed app-image has no {@code meshes} directory next to its
     * executable, so these are the meshes a user who downloads the player can actually watch.
     *
     * <p>The files are the real editor exports from the repository's {@code test-assets/meshes}
     * folder — Gradle copies them from there into the jar, so they are not duplicated in
     * {@code player/assets}. Adding an export to that folder therefore needs an entry here as
     * well; {@code BundledExamplesTest} fails when the two drift apart.
     *
     * <p>{@code fire-export.json} comes from {@code player/assets} (with its atlas
     * {@code fire_texture.png} next to it); the other paths are resolved against the classpath,
     * see {@link MeshFileResolver#handleFor(String)}.
     */
    static final String[] BUNDLED_EXAMPLES = {
        "meshes/fire-export.json",
        "meshes/dino_longneck-export.json",
        "meshes/tree_redwood-export.json",
        "meshes/test animation-export.json",
    };

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

    /**
     * Hard capacity of every VAT instance batch — the count typed into the panel (and confirmed
     * with Enter) may not exceed it. 40 bytes per instance, so 10 000 copies cost 400 KB of
     * instance buffer per animation, allocated once when the file loads.
     */
    static final int MAX_INSTANCE_COUNT = 10_000;

    /** Layout seed: the generated field is stable, so dragging a slider only changes its meaning. */
    private static final long LAYOUT_SEED = 0x5EEDL;

    /** Zoom multiplier of one mouse-wheel notch; a notch out divides by it (see {@link #zoomWorld}). */
    private static final float ZOOM_STEP = 1.15f;

    /**
     * Zoom bounds (see {@link #zoomWorld(float)}): closer than {@link #MIN_ZOOM} the grid collapses
     * into a dot, and beyond {@link #MAX_ZOOM} the near/far planes no longer cover the geometry.
     */
    private static final float MIN_ZOOM = 0.02f, MAX_ZOOM = 100f;

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
     * Editable copy of the loaded JSON export's palette (one entry per colour in the file's
     * {@code "colors"} array, in file order). The panel shows one row per entry; editing one
     * writes it back into {@link MeshExport#palette} and recolours every batch.
     */
    private final List<Color> paletteColors = new ArrayList<>();

    /** Row titles of {@link #paletteColors} — the JSON colour names (or {@code "Colour N"}). */
    private final List<String> paletteNames = new ArrayList<>();

    /** The parameter panel (top-left); {@code null} until the skin/stage exist. */
    private MeshPlayerControls controls;

    /** Where and how large the instanced copies are drawn (grid/random, y-sorted, scaled). */
    private InstanceField instances = InstanceField.empty();

    /** Placement mode selected in the panel. */
    private InstanceField.Layout placement = InstanceField.Layout.GRID;

    /** Largest random size deviation in percent ({@code 0} = all copies at their authored size). */
    private float sizeDeviationPercent;

    /** Index of the clip blended with the current one, or {@code -1} for "no blend". */
    private int blendTarget = -1;

    /** Blend weight in {@code 0..1} ({@code 0} = current clip, {@code 1} = the target clip). */
    private float blendWeight;

    /** The extra VAT batch that plays the blended clip (lazily built, re-uploaded per weight). */
    private final AnimationBlend blend = new AnimationBlend();

    /** True while the frame is drawn from {@link #blend} (used by the HUD). */
    private boolean blending;

    /** Last blend failure shown in the panel, so the status line is not rebuilt every frame. */
    private String reportedBlendError;

    // ── Camera interactive controls ──
    /**
     * Zoom multiplier (1 = no zoom).
     */
    private float cameraZoom = 1f;
    /**
     * Pan offset in world coordinates – added to the camera's target position.
     */
    private float cameraPanX, cameraPanY;

    /**
     * Scratch for the mouse position in stage coordinates ({@link #isPointerOverUi()}); reused so a
     * wheel event allocates nothing.
     */
    private final Vector2 tmpPointer = new Vector2();

    // -----------------------------------------------------------------------
    // Standalone (single-file) mode fields
    // -----------------------------------------------------------------------

    /**
     * Non-null when the player was started to display a single file (no directory scan).
     */
    private String standaloneFilePath;

    /**
     * Number of copies to render. The panel's <b>Count (Enter)</b> field changes it at runtime;
     * it is clamped to {@code 1 .. }{@link #MAX_INSTANCE_COUNT}.
     */
    private int instanceCount = DEFAULT_INSTANCE_COUNT;

    /**
     * Screen diagonal in model units (mm by convention). Controls the viewport size so the
     * model appears at a reasonable physical scale. Only used in standalone mode.
     */
    private float screenDiagonalMm = 500f;

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
        this.instanceCount = clampInstanceCount(copies);
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
        // Nothing to page through until a file loaded. Doing it here (and not only after a
        // successful load) also keeps Prev/Next dimmed when the initial load fails — the Load
        // button must stay usable in that case (see onRender).
        updateNavigationVisibility();

        // Parameter panel (top-left): instance count (Enter), size deviation, grid/random
        // placement, animation blending and the export's colour table. The panel sits below the
        // HUD title line so the file name stays readable.
        placement = standaloneFilePath != null
                ? InstanceField.Layout.RANDOM : InstanceField.Layout.GRID;
        controls = new MeshPlayerControls(skin, instanceCount, placement, new ControlsListener());
        controls.getRoot().padTop(UiLayout.PADDING + UiLayout.HUD_TITLE_BAND);
        stage.addActor(controls.getRoot());

        if (standaloneFilePath != null) {
            // ── Standalone mode: load one specific file ──
            Gdx.app.log("MeshPlayer", "Standalone mode – loading file: " + standaloneFilePath);
            java.io.File f = MeshFileResolver.resolveStandaloneFile(standaloneFilePath);
            if (f != null) {
                try {
                    exportPaths.add(f.getAbsolutePath());
                    loadFile(0);
                    if (loaded) {
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
        // directory (its assets live inside the jar), so the bundled examples are played then.
        java.io.File meshesDir = MeshFileResolver.resolveMeshesDir();
        if (meshesDir == null) {
            Gdx.app.log("MeshPlayer", "No 'meshes' directory found — playing the "
                    + BUNDLED_EXAMPLES.length + " example(s) bundled in the player.");
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
                    MeshExport e = MeshExportLoader.load(MeshFileResolver.handleFor(path));
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
            // A directory without a single playable export must not open an empty window either.
            if (exportPaths.isEmpty()) {
                Gdx.app.log("MeshPlayer", "Directory '" + meshesDir.getAbsolutePath()
                        + "' holds no playable export — falling back to the bundled examples.");
            }
        }
        // The bundled examples are offered as well: a distributed player (no meshes directory next
        // to the executable) has nothing else to play, and a checkout keeps them one click away.
        // A file that is on disk under the same name wins, see addBundledExamples().
        addBundledExamples();

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
     *             classpath is accepted too, see {@link MeshFileResolver#handleFor(String)})
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
            if (standaloneFilePath != null) rebuildInstanceField();
            updateCamera();
        }
    }

    /**
     * Plays the export at {@code index} of {@link #exportPaths} — the callback of the nav bar's
     * export selector. An out-of-range index, or the entry that is already on screen, is ignored.
     *
     * @param index position in {@link #exportPaths} (i.e. the selector's item index)
     */
    private void openExportAt(int index) {
        if (index < 0 || index >= exportPaths.size || index == currentIndex) return;
        openExport(exportPaths.get(index));
    }

    /**
     * Re-fills the nav bar's export selector with every known export — the bundled examples plus
     * whatever was opened with <b>Load…</b> / drag &amp; drop — and marks the one on screen.
     *
     * <p>It is the only way to reach the examples that ship inside the jar: unlike the animations
     * of one file they cannot be reached with Prev/Next.
     */
    private void refreshExportSelector() {
        if (navBar == null) return;
        Array<String> labels = new Array<>(exportPaths.size);
        for (String path : exportPaths) labels.add(MeshFileResolver.shortName(path));
        navBar.setExports(labels, currentIndex, this::openExportAt);
    }

    /**
     * Appends the examples bundled into the player jar (see {@link #BUNDLED_EXAMPLES}) to
     * {@link #exportPaths}, skipping any whose file name is already listed: a checkout that has the
     * same export in a scanned {@code meshes} directory keeps the disk copy, which was added first.
     */
    private void addBundledExamples() {
        for (String bundled : BUNDLED_EXAMPLES) {
            if (!isListed(bundled)) exportPaths.add(bundled);
        }
    }

    /** True when an export with the same file name is already in {@link #exportPaths}. */
    private boolean isListed(String path) {
        String name = MeshFileResolver.shortName(path);
        for (String known : exportPaths) {
            if (MeshFileResolver.shortName(known).equalsIgnoreCase(name)) return true;
        }
        return false;
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
                java.io.File dir = MeshFileResolver.resolveMeshesDir();
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

    /**
     * Routes the mouse wheel by cursor position, <b>before</b> the stage can take it.
     *
     * <p>Scene2D delivers a {@code scrolled} event to the stage's <em>scroll focus</em> — and a
     * {@link com.badlogic.gdx.scenes.scene2d.ui.ScrollPane} keeps that focus for good once it was
     * clicked. The parameter panel is part of the stage (the world is not), so without this router
     * the panel swallows the wheel of the whole window and the drawing area can no longer be zoomed
     * while the panel is expanded. {@link WheelRouter} hands the event to the UI only while the
     * cursor really is over a widget.</p>
     */
    @Override
    protected InputProcessor beforeStage() {
        return new WheelRouter();
    }

    /**
     * The world's keys and drag handling. It runs after the stage, so the buttons win over them.
     *
     * <p>The mouse wheel is <b>not</b> handled here: it has to run <i>before</i> the stage to take
     * the event away from the panel's scroll pane, see {@link #beforeStage()}.</p>
     */
    @Override
    protected InputProcessor afterStage() {
        return new MeshPlayerInput();
    }

    /**
     * Draws the instance grid and the HUD overlay. The {@link NavBar} is drawn on top of both by
     * {@link PlayerScreen}.
     *
     * <p><b>Error states always return {@code true}.</b> The message is drawn with the world's
     * {@link SpriteBatch}, but the Scene2D {@link NavBar} on the stage must stay visible: its
     * <b>Load…</b> and <b>Close</b> buttons are the only way out of a failed load, so hiding the
     * stage would leave the user with a dead window.
     *
     * @param delta frame time in seconds, already clamped by {@link PlayerScreen}
     * @return always {@code true} — even a load error keeps the navigation bar (Load…/Close) on
     *         screen so another export can be opened
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
                // already logged by MeshFileResolver.resolveStandaloneFile().
                drawMessage("Could not load '" + MeshFileResolver.shortName(standaloneFilePath)
                        + (loadError != null ? "': " + loadError : "'. See the log for the paths that were tried."));
                return true; // keep the nav bar (Load…/Close) visible
            }
            if (loadError != null) {
                drawMessage("Could not load '" + MeshFileResolver.shortName(exportPaths.size > 0
                        ? exportPaths.get(currentIndex) : ASSET_DIR) + "': " + loadError);
                return true; // keep the nav bar (Load…/Close) visible
            }
            java.io.File dir = MeshFileResolver.resolveMeshesDir();
            String p = dir != null ? dir.getAbsolutePath() : "meshes (directory not found)";
            drawMessage("No mesh exports found in '" + ASSET_DIR + "'. Looked in: " + p);
            return true; // keep the nav bar (Load…/Close) visible
        }

        if (camera == null) {
            Gdx.app.error("MeshPlayer", "camera is null – cannot render (directory scan mode needs updateCamera in create)");
            drawMessage("Camera not initialised. Use standalone mode (pass file path) or check create().");
            return true; // keep the nav bar (Load…/Close) visible
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
     * Fits the camera to the generated instance field so that every copy is visible, including
     * its random size. In standalone mode the viewport height is at least
     * {@link #screenDiagonalMm}.
     */
    private void updateCamera() {
        float objW = Math.max(1e-3f, bounds[2] - bounds[0]);
        float objH = Math.max(1e-3f, bounds[3] - bounds[1]);
        float cell = Math.max(objW, objH);
        float field = instances.size() > 0 ? Math.max(instances.extent(), cell) : cell;

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
            // smaller than the object itself, so we also compute the field-based
            // viewport and pick whichever is LARGER — the object should be visible.
            float physicalVpH = MetricUnit.MILLIMETER.convert(screenDiagonalMm, exportUnit());
            float fieldVpH = Math.max(field * margin, field * margin / ar);
            vpH = Math.max(physicalVpH, fieldVpH);
        } else {
            vpH = Math.max(field * margin, field * margin / ar);
        }
        float vpW = vpH * ar;

        if (camera == null) {
            camera = new OrthographicCamera(vpW / cameraZoom, vpH / cameraZoom);
        } else {
            // Reuse the camera: placement/size sliders call this on every tick.
            camera.viewportWidth = vpW / cameraZoom;
            camera.viewportHeight = vpH / cameraZoom;
        }
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
     * Renders the current animation — or its blend with the selected target clip — through one
     * GPU-instanced draw call.
     *
     * <p>Instances come from {@link #instances}: already placed (grid/random), already sized
     * ({@code 1 ± deviation}) and already sorted by descending y, so drawing them in array order
     * yields the correct z-order. While a blend is active the extra VAT batch is used and its
     * bone matrices are re-uploaded whenever the weight slider moved (see {@link AnimationBlend}).</p>
     */
    private void renderSingle() {
        drawnObjectCount = 0;
        blending = false;
        if (anims.isEmpty()) return;

        int animIndex = currentAnim;
        if (animIndex < 0 || animIndex >= anims.size()) animIndex = 0;
        Anim anim = anims.get(animIndex);
        if (anim.group == null) return;

        // Blend preview: current clip + selected target clip at the slider weight. Weight 0
        // means "off" — the plain batch is drawn and the preview batch is released again.
        VatInstancedMesh group = anim.group;
        if (blendTarget >= 0 && blendTarget < anims.size() && blendWeight > 0f) {
            Anim target = anims.get(blendTarget);
            if (target.group != null) {
                VatInstancedMesh preview = blend.select(anim.group, target.group, blendWeight);
                if (preview != null) {
                    group = preview;
                    blending = true;
                    reportedBlendError = null;
                } else if (blend.error() != null && controls != null
                        && !blend.error().equals(reportedBlendError)) {
                    reportedBlendError = blend.error();
                    controls.setStatus("Blend: " + reportedBlendError);
                }
            }
        } else {
            blend.clear();
            reportedBlendError = null;
        }

        // Loop the selected animation continuously, at the speed it was authored in the
        // mesheditor: the cycle length is the clip's own duration (time of its last keyframe,
        // taken from the export's "frameTimes"), not a fixed number of seconds.
        //
        // VAT skinning: the vertex shader fetches the bone matrices of frame floor(f) and
        // floor(f)+1 from the RGBA32F texture and mixes them by fract(f). Fractional frame
        // indexes therefore reproduce the CPU blend renderer exactly (see VatPoseBaker).
        float frame = frameIndex(group.animation(), time);

        // ---- Fill the instance buffer ---- then ONE draw call for all of them
        group.begin();
        for (int i = 0; i < instances.size(); i++) {
            group.add(instances.x(i), instances.y(i), frame, 0f, instances.scale(i));
        }
        group.render(camera.combined);
        drawnObjectCount = group.instanceCount();
    }

    /**
     * Rebuilds {@link #instances} from the loaded mesh, the selected layout, the instance count
     * and the size-deviation slider. Called after every load and whenever the panel changes one
     * of those values.
     */
    private void rebuildInstanceField() {
        if (!loaded) {
            instances = InstanceField.empty();
            return;
        }
        float centerX = (bounds[0] + bounds[2]) * 0.5f;
        float centerY = (bounds[1] + bounds[3]) * 0.5f;
        instances = InstanceField.build(placement, instanceCount, cellSize(), centerX, centerY,
            sizeDeviationPercent, LAYOUT_SEED);
    }

    /** Names of the loadable animations, in {@link #anims} order (for the blend selector). */
    private List<String> animationNames() {
        List<String> names = new ArrayList<>(anims.size());
        for (Anim anim : anims) names.add(anim.name);
        return names;
    }

    /**
     * Fills the panel's <b>colour table</b> from the loaded export's {@link ColorPalette}: one live
     * {@link Color} per entry of the file's {@code "colors"} array, in file order, plus a note when
     * there is nothing to edit or when edits cannot show.
     *
     * <p>The colours are copies — the palette is the model, the panel edits it through
     * {@link ControlsListener#paletteColorChanged(int, Color)}. Called after every load (and after a
     * failed one, which clears the stale rows of the previous file).</p>
     */
    private void loadPalette() {
        paletteColors.clear();
        paletteNames.clear();
        String note = null;
        if (export == null) {
            note = "This file carries no colour palette.";
        } else {
            for (int i = 0; i < export.palette.size(); i++) {
                paletteColors.add(new Color(export.palette.red(i), export.palette.green(i),
                    export.palette.blue(i), export.palette.alpha(i)));
                paletteNames.add(paletteTitle(export.palette, i));
            }
            if (paletteColors.isEmpty()) {
                note = "This export defines no colours.";
            } else if (exportTexture != null) {
                note = "Drawn with its texture — palette colours are unused.";
            }
        }
        if (controls != null) controls.setPalette(paletteColors, paletteNames, note);
    }

    /**
     * Pushes the (edited) palette into every VAT batch of the loaded export.
     *
     * <p>{@link VatInstancedMesh#refreshPalette(ColorPalette)} re-resolves the colours from the
     * per-vertex palette indexes and re-uploads them — no rebake, no shader compile — so this can
     * run on every tick of a colour-picker drag and only the affected mesh parts change colour.</p>
     */
    private void refreshPalette() {
        if (export == null) return;
        for (Anim anim : anims) {
            if (anim.group != null) anim.group.refreshPalette(export.palette);
        }
        // The blend preview bakes its own colour strip from the two clips (it has no palette
        // indexes of its own): drop it, the next rendered frame re-blends with the edited colours.
        blend.clear();
    }

    /**
     * Display title of palette entry {@code index}: the colour name the export wrote, or
     * {@code "Colour N"} (1-based) when it has none.
     *
     * <p>The panel uses this as the row label and as the colour picker's title, so a named palette
     * ({@code "fur"}, {@code "horn"}) reads exactly like in the mesheditor's colour panel.</p>
     *
     * @param palette palette the entry belongs to
     * @param index   zero-based entry index
     */
    static String paletteTitle(ColorPalette palette, int index) {
        String name = palette.name(index);
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return "Colour " + (index + 1);
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
        hud.title(MeshFileResolver.shortName(exportPaths.get(currentIndex)));

        // Bottom-left: current animation name (primary), followed by render statistics
        // (number of objects on screen and current frame rate). An empty primary label simply
        // makes the statistics start at the left margin.
        if (currentAnim >= 0 && currentAnim < anims.size()) {
            Anim anim = anims.get(currentAnim);
            String label = (currentAnim + 1) + "/" + anims.size() + "  " + anim.name;
            if (blending && blendTarget >= 0 && blendTarget < anims.size()) {
                label += "  +  " + anims.get(blendTarget).name + " "
                    + Math.round(blendWeight * 100f) + "%";
            }
            hud.primary(label);
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

    /**
     * Steps the blend target through {@code off} and every animation of the file (the {@code B}
     * key). Selecting a target while the weight is 0 also raises it to 50&nbsp;%, so one key press
     * shows a blended pose; the panel's selector and weight slider follow.
     */
    private void cycleBlend() {
        if (anims.size() < 2) return;
        int next = blendTarget + 1;
        if (next >= anims.size()) next = -1;
        blendTarget = next;
        blend.clear();
        if (next >= 0 && blendWeight <= 0f) blendWeight = 0.5f;
        if (controls != null) controls.setBlend(blendTarget, blendWeight);
    }

    /** Changes the blend weight by {@code delta} ({@code [} / {@code ]}), mirrored into the panel. */
    private void nudgeBlendWeight(float delta) {
        if (blendTarget < 0) return;
        blendWeight = Math.max(0f, Math.min(1f, blendWeight + delta));
        if (controls != null) controls.setBlend(blendTarget, blendWeight);
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    /**
     * Wheel routing of the world (registered before the stage, see {@link #beforeStage()}): over a
     * widget the UI keeps the event, anywhere else the wheel zooms this screen.
     */
    private class WheelRouter extends InputAdapter {
        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (isPointerOverUi()) return false; // the panel / nav bar owns this notch
            zoomWorld(amountY);
            return true;                        // consumed: the stage's scroll focus must not see it
        }
    }

    /**
     * True when the cursor is over an interactive widget of the {@link #stage} — the parameter
     * panel, the navigation bar, an open drop-down list.
     *
     * <p>The stage is hit-tested at the mouse position. Both fill-parent layout tables (the panel's
     * root and the {@link NavBar}) are {@code childrenOnly}, so beside their widgets the hit test
     * finds nothing and the event belongs to the world underneath.</p>
     */
    private boolean isPointerOverUi() {
        if (stage == null) return false;
        tmpPointer.set(Gdx.input.getX(), Gdx.input.getY());
        stage.screenToStageCoordinates(tmpPointer);
        return stage.hit(tmpPointer.x, tmpPointer.y, true) != null;
    }

    /**
     * Multiplies the camera zoom by {@link #ZOOM_STEP} per notch and clamps it to
     * {@code MIN_ZOOM .. MAX_ZOOM}; a scroll up ({@code amountY >= 0}) zooms out.
     */
    private void zoomWorld(float amountY) {
        float factor = (amountY < 0) ? ZOOM_STEP : 1f / ZOOM_STEP;
        cameraZoom = Math.max(MIN_ZOOM, Math.min(cameraZoom * factor, MAX_ZOOM));
        updateCamera();
    }

    /**
     * Keys of the world. It runs after the stage, so a focused widget (the count field) keeps the
     * keyboard; the mouse wheel is handled by {@link WheelRouter} instead.
     */
    private class MeshPlayerInput extends InputAdapter {
        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.ESCAPE) {
                Gdx.app.exit();
                return true;
            }
            // While the panel's count field owns the keyboard, letters belong to the field.
            if (controls != null && controls.hasTypingFocus()) return false;
            switch (keycode) {
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
                case Input.Keys.I:
                    if (controls != null) controls.focusInstanceCount();
                    return true;
                case Input.Keys.B:
                    cycleBlend();
                    return true;
                case Input.Keys.LEFT_BRACKET:
                    nudgeBlendWeight(-0.1f);
                    return true;
                case Input.Keys.RIGHT_BRACKET:
                    nudgeBlendWeight(0.1f);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void loadFile(int index) {
        for (Anim a : anims) if (a.group != null) a.group.dispose();
        disposeExportTexture();

        // The blend batch belongs to the previous file's geometry: drop it (and its pair).
        // The weight stays — the panel's slider keeps showing it, and it applies again as soon
        // as the user picks a target for the new file.
        blend.clear();
        blendTarget = -1;
        reportedBlendError = null;

        exportPaths = normalizedOrder(exportPaths);
        currentIndex = ((index % exportPaths.size) + exportPaths.size) % exportPaths.size;
        String path = exportPaths.get(currentIndex);

        // Keep the bar's export selector in step with the screen: the startup scan builds the
        // initial list, Load…/drag & drop appends to it.
        refreshExportSelector();

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
            instances = InstanceField.empty();
            loadPalette();
            if (controls != null) {
                controls.setAnimations(null);
                controls.setStatus("Could not load the file.");
            }
            // Nothing to page through any more: dim Prev/Next, keep Load…/Close usable.
            updateNavigationVisibility();
            return;
        }

        loadPalette();
        rebuildInstanceField();
        if (controls != null) {
            controls.setAnimations(animationNames());
            controls.setStatus(anims.isEmpty() ? "No animations in this file."
                : instances.size() + " " + (instances.size() == 1 ? "copy" : "copies")
                    + (placement == InstanceField.Layout.RANDOM ? " (random)" : " (grid)"));
        }
        updateNavigationVisibility();
        // A newly opened file starts at its first animation; the previous file's index may be
        // out of range (or point at an unrelated clip).
        currentAnim = 0;
        time = 0f;
        try {
            Gdx.graphics.setTitle("Mesh Player — " + MeshFileResolver.shortName(path));
        } catch (Throwable ignored) {
            Gdx.app.log("MeshPlayer", "Cannot set title: " + "Mesh Player — " + MeshFileResolver.shortName(path));
        }
        Gdx.app.log("MeshPlayer", "Loaded '" + path + "': " + anims.size() + " animation(s).");
    }

    /** True for baked {@code .vat} files (case-insensitive). */
    private static boolean isVatPath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".vat");
    }

    /** JSON export: load the poses and bake them through the skin (or pose fallback) path. */
    private void loadJsonExport(String path) {
        export = MeshExportLoader.load(MeshFileResolver.handleFor(path));
        if (export.skippedTriangles > 0) {
            // Damaged meshes (e.g. references to a removed vertex) must not stop the preview,
            // but the user should know that part of the geometry is missing.
            Gdx.app.log("MeshPlayer", "WARNING: '" + MeshFileResolver.shortName(path) + "' skipped "
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
            Gdx.app.log("MeshPlayer", "WARNING: '" + MeshFileResolver.shortName(exportPath) + "' declares texture '"
                + e.textureFile + "' but carries no uvVertices — drawing its palette colours.");
            return null;
        }
        // Same resolution as the export itself: next to the JSON on disk, or inside the jar.
        FileHandle textureFile = MeshFileResolver.handleFor(exportPath).parent().child(e.textureFile);
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
        VatFile.FileContent content = VatFile.loadAll(MeshFileResolver.handleFor(path));
        vatMesh = content.mesh();
        List<Anim> result = new ArrayList<>();
        for (VatAnimationData animation : content.animations()) {
            result.add(new Anim(animation.name(), animation.frameCount(),
                new VatInstancedMesh(vatMesh, animation, MAX_INSTANCE_COUNT), null));
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

    /**
     * Builds one {@link Anim} per pose group.
     *
     * <p>Skinned exports take the shared-skin path ({@link #buildSkinnedAnims}): all clips are
     * baked against one widened skin, so every animation shares its {@code VatMeshData} and its
     * matrix-row table — the precondition for blending. Exports without usable clips (legacy pose
     * exports, or a clip that cannot be aligned with its poses) fall back to
     * {@link #buildPoseAnims}, which bakes every group on its own.</p>
     */
    private List<Anim> buildAnims(MeshExport e) {
        Map<String, List<BlendableMesh>> byName = groupPoses(e);
        List<Anim> skinned = buildSkinnedAnims(e, byName);
        return skinned != null ? skinned : buildPoseAnims(e, byName);
    }

    /** Groups the export's poses by animation name, preserving their export order. */
    private static Map<String, List<BlendableMesh>> groupPoses(MeshExport e) {
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
        return byName;
    }

    /**
     * Skinned path: bakes <b>all clips against one shared skin</b> and reuses one
     * {@link VatMeshData} for every animation.
     *
     * @return the animations, or {@code null} when the export cannot use one shared skin (then
     *         {@link #buildPoseAnims} takes over; blending is impossible in that case)
     */
    private List<Anim> buildSkinnedAnims(MeshExport e, Map<String, List<BlendableMesh>> byName) {
        if (e.skin == null || e.meshes.isEmpty()) return null;
        BlendableMesh bindPose = e.meshes.get(0);

        // Collect the groups that have a baked clip; a hand-animated vertex adds a matrix row,
        // so baking each clip on its own gives every clip a different row count.
        List<MeshSkinClip> clips = new ArrayList<>();
        List<List<BlendableMesh>> framesPerClip = new ArrayList<>();
        List<String> groupNames = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            MeshSkinClip clip = clipNamed(e.skinClips, entry.getKey());
            if (clip == null) continue;
            groupNames.add(entry.getKey());
            clips.add(clip);
            framesPerClip.add(entry.getValue());
        }
        if (clips.isEmpty()) return null;

        List<VatVertexOffsetBaker.Result> patched;
        try {
            patched = VatVertexOffsetBaker.bakeAll(bindPose, e.skin, clips, framesPerClip,
                VatVertexOffsetBaker.DEFAULT_EPSILON);
        } catch (IllegalArgumentException ex) {
            Gdx.app.log("MeshPlayer", "WARNING: cannot bake one shared skin ("
                + ex.getMessage() + ") - falling back to per-group bakes; blending is disabled.");
            return null;
        }

        MeshSkin sharedSkin = patched.get(0).skin();
        VatMeshData meshData = VatSkinBaker.meshData(bindPose, sharedSkin, e.palette,
            e.stepVertices, e.uvs);
        List<Anim> result = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlendableMesh> frames = entry.getValue();
            int clipIndex = groupNames.indexOf(name);
            VatInstancedMesh group = null;
            String error = null;
            try {
                VatAnimationData animation;
                VatVertexOffsetBaker.Result patch = null;
                if (clipIndex >= 0) {
                    patch = patched.get(clipIndex);
                    animation = VatSkinBaker.animation(e.name + "_" + name, sharedSkin,
                        patch.clip(), frames, e.palette);
                } else if ("rest".equals(name)) {
                    animation = VatSkinBaker.restAnimation(sharedSkin, frameRate(1));
                } else {
                    // A group without a baked clip cannot share the skin: use the pose path.
                    return null;
                }
                group = new VatInstancedMesh(meshData, animation, MAX_INSTANCE_COUNT,
                    textureFor(meshData));
                Gdx.app.log("MeshPlayer", "  -> VAT skin group '" + name + "' created ("
                    + sharedSkin.boneCount() + " matrix rows, " + animation.frameCount()
                    + " frames" + colorInfo(group) + vertexOffsetInfo(patch) + ")");
            } catch (RuntimeException ex) {
                error = ex.getMessage();
                Gdx.app.error("MeshPlayer", "Cannot build VAT group for animation '"
                    + name + "': " + error);
            }
            result.add(new Anim(name,
                group != null ? group.animation().frameCount() : frames.size(), group, error));
        }
        Gdx.app.log("MeshPlayer", "Shared skin: " + sharedSkin.boneCount() + " matrix rows for "
            + clips.size() + " clip(s) - animations can be blended.");
        return result;
    }

    /**
     * Legacy / fallback path: bakes every pose group on its own ({@link VatPoseBaker}). Each group
     * gets its own {@link VatMeshData} and bone table, so blending is only possible when the
     * tables happen to have the same size (the player reports it otherwise).
     */
    private List<Anim> buildPoseAnims(MeshExport e, Map<String, List<BlendableMesh>> byName) {
        List<Anim> result = new ArrayList<>();
        for (Map.Entry<String, List<BlendableMesh>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlendableMesh> frames = entry.getValue();
            VatInstancedMesh group = null;
            String error = null;
            try {
                boolean[] attachmentSteps = e.animationStepFrames.get(name);
                if (attachmentSteps != null && attachmentSteps.length != frames.size()) {
                    attachmentSteps = null;
                }
                VatPoseBaker.Result baked = VatPoseBaker.bake(
                        e.name + "_" + name, frames, e.palette, frameRate(frames.size()),
                        e.stepVertices, attachmentSteps, e.uvs);
                group = new VatInstancedMesh(baked.mesh(), baked.animation(),
                        MAX_INSTANCE_COUNT, textureFor(baked.mesh()));
                Gdx.app.log("MeshPlayer", "  -> VAT pose group '" + name + "' created ("
                        + baked.mesh().vertexCount() + " vertices, "
                        + baked.animation().frameCount() + " frames" + colorInfo(group) + ")");
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

    /** Defensive copy of the export list, so iterating it can never be disturbed by a reload. */
    private static Array<String> normalizedOrder(Array<String> in) {
        Array<String> out = new Array<>();
        for (String s : in) out.add(s);
        return out;
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
        blend.dispose();
        for (Anim a : anims) if (a.group != null) a.group.dispose();
        disposeExportTexture();
        font.dispose();
        batch.dispose();
    }

    // -----------------------------------------------------------------------

    /**
     * Bridges the parameter panel to the screen. Every callback writes exactly one state field
     * and rebuilds only what that field affects (instance field + camera for placement/size,
     * the blend batch for animation changes, the VAT vertex colours for a palette edit).
     */
    private final class ControlsListener implements MeshPlayerControls.Listener {
        @Override
        public int instanceCountEntered(String text) {
            int parsed = parseInstanceCount(text);
            if (parsed < 0) {
                if (controls != null) {
                    controls.setStatus("Enter a whole number from 1 to "
                        + MAX_INSTANCE_COUNT + ".");
                }
                return -1;
            }
            int applied = clampInstanceCount(parsed);
            instanceCount = applied;
            if (loaded) {
                rebuildInstanceField();
                updateCamera();
            }
            if (controls != null) {
                controls.setStatus(applied < parsed
                    ? "Clamped to " + MAX_INSTANCE_COUNT + " copies." : "");
            }
            return applied;
        }

        @Override
        public void sizeDeviationChanged(float percent) {
            sizeDeviationPercent = percent;
            if (!loaded) return;
            rebuildInstanceField();
            updateCamera();
        }

        @Override
        public void placementChanged(InstanceField.Layout layout) {
            placement = layout;
            if (!loaded) return;
            rebuildInstanceField();
            updateCamera();
        }

        @Override
        public void blendTargetChanged(int animationIndex) {
            blendTarget = animationIndex;
            blend.clear();
        }

        @Override
        public void blendWeightChanged(float weight) {
            blendWeight = weight;
        }

        @Override
        public void paletteColorChanged(int index, Color color) {
            if (export == null || index < 0 || index >= export.palette.size()) return;
            export.palette.setColor(index, color.r, color.g, color.b, color.a);
            refreshPalette();
        }
    }

    /**
     * Parses the panel's count field.
     *
     * @param text the typed text (surrounding blanks are ignored)
     * @return the positive number, or {@code -1} for a blank/negative/non-numeric entry
     */
    static int parseInstanceCount(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return -1;
        try {
            int value = Integer.parseInt(trimmed);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Clamps a requested instance count to the batch capacity.
     *
     * @param requested the number the user typed
     * @return {@code requested} inside {@code 1 .. }{@link #MAX_INSTANCE_COUNT}
     */
    static int clampInstanceCount(int requested) {
        return Math.max(1, Math.min(MAX_INSTANCE_COUNT, requested));
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
