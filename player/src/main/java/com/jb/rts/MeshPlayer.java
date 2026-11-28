package com.jb.rts;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.utils.Array;
import com.jb.rts.mesh.MetricUnit;
import com.jb.rts.mesh.blend.ColorPalette;
import com.jb.rts.mesh.blend.MeshExport;
import com.jb.rts.mesh.blend.MeshExportLoader;
import com.jb.rts.mesh.vat.VatAnimationData;
import com.jb.rts.mesh.vat.VatInstancedMesh;
import com.jb.rts.mesh.vat.VatMeshData;
import com.jb.rts.mesh.vat.VatPoseBaker;
import com.jb.rts.player.AnimationBlend;
import com.jb.rts.player.ExportSelection;
import com.jb.rts.player.FsmController;
import com.jb.rts.player.MeshClipLoader;
import com.jb.rts.player.MeshFileResolver;
import com.jb.rts.player.InstanceField;
import com.jb.rts.player.PlayerAnimationFsm;
import com.jb.rts.player.PlayerClip;
import com.jb.rts.player.ViewPan;
import com.jb.rts.player.ui.FsmControlsSection;
import com.jb.rts.player.ui.HudOverlay;
import com.jb.rts.player.ui.MeshPlayerControls;
import com.jb.rts.player.ui.NavBar;
import com.jb.rts.player.ui.PlayerScreen;
import com.jb.rts.player.ui.UiLayout;

import java.util.ArrayList;
import java.util.List;


/**
 * Mesh player: plays an <em>animesh mesh export</em> — one of the examples bundled into the jar on
 * startup, or any file the user opens. The current animation is drawn as a crowd of GPU instances
 * and driven by a <b>gdx-ai state machine</b> (see {@link com.jb.rts.player.PlayerAnimationFsm} and
 * {@code animesh-lib/docs/FSM_VISUALIZER.md}): the state graph in the lower-left corner shows every
 * clip of the file, a click on a node crossfades to it, and the panel's FSM block holds the
 * trigger buttons and the transport. The <b>export selector</b> in the navigation bar switches
 * between the bundled examples (and everything opened so far), <b>Load…</b> opens another export,
 * and the <b>Close</b> button (or <b>Esc</b>) exits the player.
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
 *   <li><b>FSM</b> — the state machine drives the animation: one trigger button per loaded clip
 *       ({@code 1}…{@code 9} on the keyboard press the first nine), the <b>Crossfade (s)</b> spinner
 *       (0 – 60 s in 100 ms steps), <b>Pause</b> / <b>+1 Frame</b> / <b>Reset</b>, the time scale
 *       and the live readout. Every transition is a crossfade between the two clips, rendered by
 *       {@link com.jb.rts.mesh.vat.VatAnimationBlender}.</li>
 *   <li><b>Colours</b> — a table with one row per colour of the loaded file's palette (the JSON's
 *       {@code "colors"} array, see {@link com.jb.rts.mesh.blend.ColorPalette}). Every row opens the
 *       family's {@link com.jb.rts.player.ui.ColorPicker}; editing one recolours exactly the mesh
 *       parts that reference that colour, live while the picker is dragged
 *       ({@link VatInstancedMesh#refreshPalette(ColorPalette)}). A file without a palette
 *       ({@code .vat}) says so instead of showing rows.</li>
 * </ul>
 *
 * <p>The file name is shown at the top of the window. The bottom-left corner shows the current
 * animation (<em>e.g.</em> {@code 2/3  idle}, with the clip being faded out while a transition
 * runs), followed by the number of objects drawn this frame and the current frame rate.</p>
 *
 * <p><b>Camera:</b> the mouse wheel zooms the drawing area and a <b>left-drag</b> pans it — the
 * grabbed copy stays under the cursor ({@link ViewPan}); {@code W}/{@code A}/{@code S}/{@code D}
 * and the arrow keys pan as well. Only the instance grid moves: the FSM graph and the HUD are drawn
 * in screen pixels, the panel and the navigation bar live on the stage, so neither is affected by
 * the pan. A drag only starts where no widget and no graph node took the press.</p>
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
    /** The known exports (bundled examples + everything opened) and the file chooser. */
    private ExportSelection exports;
    private int currentIndex = 0;

    private MeshExport export;
    private List<PlayerClip> anims = new ArrayList<>();
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
     * Owner of the current file's baked clips and model texture ({@code null} after a failed load).
     * Replaced on every {@link #loadFile(int)}, which disposes the previous one.
     */
    private MeshClipLoader clipLoader;

    /**
     * Model texture of the loaded JSON export (export version 6), or {@code null} when the mesh
     * has none. One picture serves every animation batch of the export, so the screen owns and
     * disposes it (through {@link #clipLoader}) — {@link VatInstancedMesh} never does.
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

    /**
     * Share of the {@link #blendTarget} (outgoing) clip in {@code 0..1}: {@code 1} when a crossfade
     * starts, {@code 0} when it has finished. Written by the state machine only.
     */
    private float blendWeight;

    /** The extra VAT batch that plays the blended clip (lazily built, re-uploaded per weight). */
    private final AnimationBlend blend = new AnimationBlend();

    /** True while the frame is drawn from {@link #blend} (used by the HUD). */
    private boolean blending;

    /** Last blend failure shown in the panel, so the status line is not rebuilt every frame. */
    private String reportedBlendError;

    // ── FSM visualiser (see docs/FSM_VISUALIZER.md) ──
    /**
     * The animation state machine, its graph overlay and its shape renderer. It writes
     * {@link #currentAnim}, {@link #blendTarget}, {@link #blendWeight} and {@link #time}, so the
     * VAT pipeline stays exactly as it was. {@code null} until {@link #onCreate()}.
     */
    private FsmController fsmController;

    /**
     * Temporary diagnostics switch for the graph click path (<b>TODO</b>: remove once the input
     * routing is confirmed). Enabled with the <b>F9</b> key or by starting the app with
     * {@code -Danimesh.traceFsm=true}; every step of a left click is then logged with the
     * {@code FsmTrace} tag, so a click that vanishes can be pinned to one gate.
     */
    private boolean fsmTrace = Boolean.getBoolean("animesh.traceFsm");

    // ── Camera interactive controls ──
    /**
     * Zoom multiplier (1 = no zoom).
     */
    private float cameraZoom = 1f;
    /**
     * Pan offset in world coordinates (added to the camera's target position) and the left-drag
     * gesture that moves it — shared by {@link #updateCamera()} and {@link MeshPlayerInput}.
     */
    private final ViewPan pan = new ViewPan();

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
     * Creates the export list. Done in the constructor (not in {@code onCreate()}) so that
     * {@link #openExport(String)} and the load path can run on a screen that was never created —
     * the launcher does that for drag &amp; dropped files.
     */
    private void initializeExports() {
        exports = new ExportSelection(BUNDLED_EXAMPLES, this::openExport);
    }

    /**
     * Default constructor – scans the assets/meshes directory (original behaviour).
     */
    public MeshPlayer() {
        initializeExports();
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
        initializeExports();
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
                .onClose(() -> Gdx.app.exit())
                .attachTo(stage);

        // Parameter panel (top-left): instance count (Enter), size deviation, grid/random
        // placement, animation blending and the export's colour table. The panel sits below the
        // HUD title line so the file name stays readable.
        placement = standaloneFilePath != null
                ? InstanceField.Layout.RANDOM : InstanceField.Layout.GRID;
        controls = new MeshPlayerControls(skin, instanceCount, placement, new ControlsListener());
        controls.getRoot().padTop(UiLayout.PADDING + UiLayout.HUD_TITLE_BAND);
        stage.addActor(controls.getRoot());

        // FSM visualiser: the graph overlay draws in the world space (bottom-left, clear of the
        // panel and the nav bar) and the panel block drives it. Both are created once; the machine
        // itself is rebuilt per loaded file (see loadFile).
        TextureRegion white = skin.has("white", Texture.class)
                ? new TextureRegion(skin.get("white", Texture.class)) : null;
        fsmController = new FsmController(new FsmTarget(), white);
        controls.setFsmListener(new FsmControlsListener());

        if (standaloneFilePath != null) {
            // ── Standalone mode: load one specific file ──
            Gdx.app.log("MeshPlayer", "Standalone mode – loading file: " + standaloneFilePath);
            java.io.File f = MeshFileResolver.resolveStandaloneFile(standaloneFilePath);
            if (f != null) {
                try {
                    exports.add(f.getAbsolutePath());
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
                    exports.paths().clear();
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
                    exports.add(f.getAbsolutePath());
                    Gdx.app.log("MeshPlayer", "  candidate (vat): " + f.getAbsolutePath());
                }
            }
            for (String path : files) {
                Gdx.app.log("MeshPlayer", "  candidate: " + path);
                try {
                    MeshExport e = MeshExportLoader.load(MeshFileResolver.handleFor(path));
                    if (e.meshes != null && !e.meshes.isEmpty()) {
                        exports.paths().add(path); // parses as a mesh export
                        Gdx.app.log("MeshPlayer", "  -> parsed as mesh export ("
                                + e.meshes.size() + " poses)");
                    }
                } catch (Exception ignore) {
                    Gdx.app.log("MeshPlayer", "Ignoring file " + path);
                }
            }
            Gdx.app.log("MeshPlayer", "Found " + exports.size() + " mesh export(s) in "
                    + meshesDir.getAbsolutePath());
            // A directory without a single playable export must not open an empty window either.
            if (exports.size() == 0) {
                Gdx.app.log("MeshPlayer", "Directory '" + meshesDir.getAbsolutePath()
                        + "' holds no playable export — falling back to the bundled examples.");
            }
        }
        // The bundled examples are offered as well: a distributed player (no meshes directory next
        // to the executable) has nothing else to play, and a checkout keeps them one click away.
        // A file that is on disk under the same name wins, see addBundledExamples().
        exports.addBundledExamples();

        if (exports.size() > 0) {
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
        
        int index = exports.indexOf(path);
        if (index < 0) {
            exports.add(path);
            index = exports.size() - 1;
        }
        loadFile(index);
        if (loaded) {
            // The bounds changed, so the viewport has to be re-fitted; in standalone mode the
            // scattered copies are regenerated to stay inside the new bounds.
            pan.reset();
            if (standaloneFilePath != null) rebuildInstanceField();
            updateCamera();
        }
    }

    /** The nav bar selector callback: opens the export at the given index. */
    private void openExportAt(int index) {
        exports.openAt(index, currentIndex);
    }

    /** Re-fills the nav bar export selector and marks the export on screen. */
    private void refreshExportSelector() {
        if (navBar == null || exports == null) return;
        navBar.setExports(exports.labels(), currentIndex, this::openExportAt);
    }

    /** Opens the system file chooser; the picked file is played on the render thread. */
    private void chooseExportFile() {
        ExportSelection.chooseFile(this::openExport);
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
        // The state machine owns the animation fields, so it has to run before the world is drawn:
        // it advances the clip clocks, the crossfade and the one-shot auto-return.
        updateFsm(delta);
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
                drawMessage("Could not load '" + MeshFileResolver.shortName(exports.size() > 0
                        ? exports.pathAt(currentIndex) : ASSET_DIR) + "': " + loadError);
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
        try {
            drawFsmGraph();
        } catch (Exception e) {
            Gdx.app.error("MeshPlayer", "drawFsmGraph() threw: " + e.getMessage(), e);
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
        float targetX = (bounds[0] + bounds[2]) * 0.5f + pan.offsetX();
        float targetY = (bounds[1] + bounds[3]) * 0.5f + pan.offsetY();
        camera.position.set(targetX, targetY, 0f);
        // World space is the standard math coordinate system: +x right, +y up. y-up exports are
        // used as-is; only y-down ones are mirrored when they are loaded (see
        // convertExportToWorldYUp), so no camera rotation or mirrored instance positions are
        // needed.
        camera.update();
    }

    /**
     * Renders the current animation — or its crossfade with the outgoing clip — through one
     * GPU-instanced draw call.
     *
     * <p>Instances come from {@link #instances}: already placed (grid/random), already sized
     * ({@code 1 ± deviation}) and already sorted by descending y, so drawing them in array order
     * yields the correct z-order. While the state machine crossfades, the extra VAT batch is used
     * and its bone matrices are re-uploaded whenever the fade weight changed (see
     * {@link AnimationBlend}).</p>
     */
    private void renderSingle() {
        drawnObjectCount = 0;
        blending = false;
        if (anims.isEmpty()) return;

        int animIndex = currentAnim;
        if (animIndex < 0 || animIndex >= anims.size()) animIndex = 0;
        PlayerClip anim = anims.get(animIndex);
        if (anim.group == null) return;

        // Crossfade: the clip on screen + the outgoing clip at the machine's fade weight. Weight 0
        // means "off" — the plain batch is drawn and the preview batch is released again.
        VatInstancedMesh group = anim.group;
        if (blendTarget >= 0 && blendTarget < anims.size() && blendWeight > 0f) {
            PlayerClip target = anims.get(blendTarget);
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
        for (PlayerClip anim : anims) names.add(anim.name);
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
        for (PlayerClip anim : anims) {
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
        hud.title(MeshFileResolver.shortName(exports.pathAt(currentIndex)));

        // Bottom-left: current animation name (primary), followed by render statistics
        // (number of objects on screen and current frame rate). An empty primary label simply
        // makes the statistics start at the left margin.
        if (currentAnim >= 0 && currentAnim < anims.size()) {
            PlayerClip anim = anims.get(currentAnim);
            String label = (currentAnim + 1) + "/" + anims.size() + "  " + anim.name;
            if (blending && blendTarget >= 0 && blendTarget < anims.size()) {
                // blendWeight is the outgoing clip's share, so 1 - it is how far the fade has run
                // into the clip that is now on screen.
                label += "  \u2190  " + anims.get(blendTarget).name + " "
                    + Math.round((1f - blendWeight) * 100f) + "%";
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

    private void drawMessage(String msg) {
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, msg, 20, viewH * 0.6f);
        batch.end();
    }

    // ---- Animation navigation ---- //

    /**
     * Selects the animation the player draws as a hard cut, <b>and</b> lets the state machine
     * follow.
     *
     * <p>Keyboard paging is not a transition: the graph still has to move with it, otherwise it
     * keeps highlighting the clip it thinks is active and a click on the visible node does
     * nothing. Everything that changes {@link #currentAnim} outside the machine goes through
     * here.</p>
     *
     * @param index index into {@link #anims}; ignored when out of range
     */
    private void selectAnimation(int index) {
        if (index < 0 || index >= anims.size()) return;
        currentAnim = index;
        time = 0f;
        if (fsmController != null) fsmController.follow(index);
        if (controls != null && controls.fsmSection() != null && fsmController != null) {
            controls.fsmSection().sync(fsmController.fsm());
        }
    }

    private void nextAnim() {
        selectAnimation(anims.isEmpty() ? 0 : (currentAnim + 1) % anims.size());
    }

    private void prevAnim() {
        selectAnimation(anims.isEmpty() ? 0
            : (currentAnim - 1 + anims.size()) % anims.size());
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
     * panel, the navigation bar, an open drop-down list. Uses the cached mouse position (wheel
     * routing), see {@link #isPointerOverUi(float, float)} for input events.
     *
     * <p>The stage is hit-tested at the mouse position. Both fill-parent layout tables (the panel's
     * root and the {@link NavBar}) are {@code childrenOnly}, so beside their widgets the hit test
     * finds nothing and the event belongs to the world underneath.</p>
     */
    private boolean isPointerOverUi() {
        return isPointerOverUi(Gdx.input.getX(), Gdx.input.getY());
    }

    /**
     * True when the stage has a touchable widget at a window position. Input events pass their own
     * position — the cached pointer may not have moved yet when a backend reports the press — so a
     * drag only ever starts in the drawing area.
     *
     * @param screenX position in window pixels from the left
     * @param screenY position in window pixels from the top (libGDX's y-down screen space)
     */
    private boolean isPointerOverUi(float screenX, float screenY) {
        if (stage == null) return false;
        tmpPointer.set(screenX, screenY);
        stage.screenToStageCoordinates(tmpPointer);
        return stage.hit(tmpPointer.x, tmpPointer.y, true) != null;
    }

    /**
     * Temporary diagnostic: the widget chain under a click, from the deepest actor up to the root,
     * so a swallowed click names its culprit. TODO: remove with {@link #fsmTrace}.
     *
     * @param screenX event x in window pixels
     * @param screenY event y in window pixels (top-left origin, as delivered by the backend)
     * @return a one-line description; says so explicitly when no actor is under the point
     */
    private String describeUiUnderCursor(int screenX, int screenY) {
        if (stage == null) return "no stage";
        tmpPointer.set(screenX, screenY);
        stage.screenToStageCoordinates(tmpPointer);
        Actor hit = stage.hit(tmpPointer.x, tmpPointer.y, true);
        if (hit == null) return "no touchable actor under the cursor (press fired at the root)";
        StringBuilder sb = new StringBuilder();
        for (Actor a = hit; a != null && sb.length() < 400; a = a.getParent()) {
            if (sb.length() > 0) sb.append(" < ");
            sb.append(a.getClass().getSimpleName());
            if (a instanceof Widget) {
                // The label of a widget is what a user recognises in the log.
                Object text = ((Widget) a).getUserObject();
                sb.append("(name=").append(a.getName()).append(", text=").append(text).append(')');
            }
        }
        return sb.toString();
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
     * Pans the world view by a world-space delta (keyboard and drag) and re-fits the camera.
     * The FSM graph, the HUD and the stage widgets do not move: they are drawn in screen space.
     *
     * @param dxWorld offset added to the camera target's x, world units
     * @param dyWorld offset added to the camera target's y, world units
     */
    private void panByWorld(float dxWorld, float dyWorld) {
        pan.panBy(dxWorld, dyWorld);
        updateCamera();
    }

    /**
     * Keys, drag-panning and graph clicks of the world. It runs after the stage, so a focused
     * widget (the count field) keeps the keyboard and a widget that took the press also owns the
     * drag; the mouse wheel is handled by {@link WheelRouter} instead.
     */
    private class MeshPlayerInput extends InputAdapter {
        /**
         * Below the stage in the input multiplexer: a widget that consumed the click never reaches
         * this point. A click on a graph node is consumed here; a press in the drawing area starts
         * the <b>drag-to-pan</b> gesture of the instance grid without being consumed (the press uses
         * nothing yet, and the multiplexer hands every drag to this listener anyway) — so the empty
         * area of the window is never swallowed by this processor.
         *
         * <p>The event's own position is used (not {@code Gdx.input.getX/Y}) — the hit test then
         * works even when a backend reports the press before it moves the cached pointer, and it
         * flips the y axis itself, because the graph is laid out in the GL bottom-left space.</p>
         */
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Input.Buttons.LEFT) return false;
            int pressesBefore = fsmController != null ? fsmController.pressCount() : 0;
            boolean consumed = clickFsmGraph(screenX, Gdx.graphics.getHeight() - screenY);
            if (fsmTrace && fsmController != null && fsmController.pressCount() == pressesBefore) {
                // The press never arrived: the stage reported it as handled before this listener ran.
                trace("click LOST before the graph: the stage consumed it"
                    + " | stageHit=" + describeUiUnderCursor(screenX, screenY)
                    + " | rootTouchable=" + (stage != null ? stage.getRoot().getTouchable() : "no stage")
                    + TMP_CLICK_CONTEXT(screenX, screenY));
            }
            if (consumed) return true;
            // A press in the drawing area prepares the drag-to-pan gesture but is not consumed: the
            // press itself uses nothing, and touchDragged reaches this listener regardless (the
            // multiplexer offers every drag to each processor). Only a widget or a graph node may
            // swallow a press. The event position is used, not the cached mouse position.
            if (loaded && camera != null && !isPointerOverUi(screenX, screenY)) {
                pan.begin(pointer, screenX, screenY);
            }
            return false;
        }

        /**
         * Pans the instance grid: the pixel delta is converted with the ratio of the camera's
         * viewport (which already contains the zoom), so the grabbed copy stays under the cursor.
         *
         * <p>Only this listener moves the camera — the FSM graph, the HUD and the stage widgets are
         * drawn in screen space and do not follow. A widget that took the press never sends its drag
         * here, because the stage comes first in the multiplexer.</p>
         */
        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (!pan.isDragging() || camera == null) return false;
            float worldPerPixelX = camera.viewportWidth / Math.max(1f, Gdx.graphics.getWidth());
            float worldPerPixelY = camera.viewportHeight / Math.max(1f, Gdx.graphics.getHeight());
            if (!pan.drag(pointer, screenX, screenY, worldPerPixelX, worldPerPixelY)) return false;
            updateCamera();
            return true;
        }

        /** Ends the drag started in {@link #touchDown}; the release is consumed only then. */
        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            return pan.end(pointer);
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.ESCAPE) {
                Gdx.app.exit();
                return true;
            }
            // While a text field of the panel owns the keyboard, letters belong to the field.
            if (controls != null && controls.hasTypingFocus()) return false;
            // Temporary diagnostics toggle for the graph click path (F9). TODO: remove with fsmTrace.
            if (keycode == Input.Keys.F9) {
                fsmTrace = !fsmTrace;
                Gdx.app.log("FsmTrace", "click tracing " + (fsmTrace ? "ON" : "OFF"));
                return true;
            }
            // 1..9 trigger the corresponding state button of the FSM block.
            if (keycode >= Input.Keys.NUM_1 && keycode <= Input.Keys.NUM_9) {
                int index = keycode - Input.Keys.NUM_1;
                if (controls != null && controls.fsmSection() != null
                        && controls.fsmSection().pressState(index)) {
                    return true;
                }
            }
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
                    panByWorld(-cellSize() * 0.2f, 0f);
                    return true;
                case Input.Keys.D:
                    panByWorld(cellSize() * 0.2f, 0f);
                    return true;
                case Input.Keys.W:
                case Input.Keys.UP:
                    panByWorld(0f, cellSize() * 0.2f);
                    return true;
                case Input.Keys.S:
                case Input.Keys.DOWN:
                    panByWorld(0f, -cellSize() * 0.2f);
                    return true;
                case Input.Keys.I:
                    if (controls != null) controls.focusInstanceCount();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void loadFile(int index) {
        // The blend batch belongs to the previous file's geometry: drop it. The machine is rebuilt
        // right after the load, so blendTarget/blendWeight start from its fresh state.
        blend.clear();
        blendTarget = -1;
        reportedBlendError = null;

        
        currentIndex = ((index % exports.size()) + exports.size()) % exports.size();
        String path = exports.pathAt(currentIndex);

        // Keep the bar's export selector in step with the screen: the startup scan builds the
        // initial list, Load…/drag & drop appends to it.
        refreshExportSelector();

        if (!loadClips(path)) {
            instances = InstanceField.empty();
            loadPalette();
            if (controls != null) controls.setStatus("Could not load the file.");
            rebuildFsm();
            return;
        }

        loadPalette();
        rebuildInstanceField();
        if (controls != null) {
            controls.setStatus(anims.isEmpty() ? "No animations in this file."
                : instances.size() + " " + (instances.size() == 1 ? "copy" : "copies")
                    + (placement == InstanceField.Layout.RANDOM ? " (random)" : " (grid)"));
        }
        rebuildFsm();
        // A newly opened file starts at its first animation; the previous file's index may be
        // out of range (or point at an unrelated clip). Going through selectAnimation also puts the
        // machine on that clip: it starts on its inferred idle/first-loop state, which need not be
        // clip 0 (the dino export has walk/run/die and no idle at all).
        if (!anims.isEmpty()) selectAnimation(0);
        else currentAnim = 0;
        time = 0f;
        try {
            Gdx.graphics.setTitle("Mesh Player — " + MeshFileResolver.shortName(path));
        } catch (Throwable ignored) {
            Gdx.app.log("MeshPlayer", "Cannot set title: Mesh Player — "
                + MeshFileResolver.shortName(path));
        }
        Gdx.app.log("MeshPlayer", "Loaded '" + path + "': " + anims.size() + " animation(s).");
    }


    // -----------------------------------------------------------------------
    // Loading (the baking itself lives in MeshClipLoader)
    // -----------------------------------------------------------------------

    /**
     * Loads the export at {@code path} into the screen: disposes the previous file's batches, bakes
     * the new ones through {@link MeshClipLoader}, then refreshes the palette, the instance field,
     * the panel, the machine and the camera inputs.
     *
     * @param path the file to load
     * @return {@code true} when the file was loaded; on failure the error is in {@link #loadError}
     */
    private boolean loadClips(String path) {
        for (PlayerClip clip : anims) if (clip.group != null) clip.group.dispose();
        if (clipLoader != null) {
            clipLoader.dispose();
            clipLoader = null;
        }
        anims = new ArrayList<>();
        export = null;
        vatMesh = null;
        loaded = false;
        loadError = null;

        try {
            clipLoader = new MeshClipLoader(path, MAX_INSTANCE_COUNT);
            MeshClipLoader.Loaded result = clipLoader.result();
            export = result.export;
            vatMesh = result.vatMesh;
            anims = result.clips;
            float[] box = clipLoader.bounds();
            bounds[0] = box[0];
            bounds[1] = box[1];
            bounds[2] = box[2];
            bounds[3] = box[3];
            boundsMinZ = clipLoader.boundsMinZ();
            boundsMaxZ = clipLoader.boundsMaxZ();
            loaded = true;
            return true;
        } catch (RuntimeException e) {
            loadError = e.getMessage() != null ? e.getMessage() : e.toString();
            Gdx.app.error("MeshPlayer", "Failed to load '" + path + "': " + loadError, e);
            for (PlayerClip clip : anims) if (clip.group != null) clip.group.dispose();
            anims = new ArrayList<>();
            export = null;
            vatMesh = null;
            return false;
        }
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

    // -----------------------------------------------------------------------
    // FSM visualiser (the logic lives in FsmController)
    // -----------------------------------------------------------------------

    /** Rebuilds the machine for the clips that are now loaded (success and failure alike). */
    private void rebuildFsm() {
        if (fsmController == null) return;
        List<String> names = animationNames();
        List<VatInstancedMesh> groups = new ArrayList<>(names.size());
        for (PlayerClip anim : anims) groups.add(anim.group);
        fsmController.rebuild(names, groups, controls != null ? controls.fsmSection() : null);
    }

    /** Lets the machine drive one frame and mirrors it into the panel. */
    private void updateFsm(float delta) {
        if (fsmController == null) return;
        if (fsmController.update(delta) && controls != null && controls.fsmSection() != null) {
            controls.fsmSection().sync(fsmController.fsm());
        }
    }

    /** Draws the state graph above the world and the HUD, below the Scene2D stage. */
    private void drawFsmGraph() {
        if (fsmController != null) fsmController.draw(batch, font, viewH);
    }

    /**
     * A click on a graph node starts the transition (called after the stage, so the panel and the
     * nav bar always win).
     *
     * <p>Every rejecting branch logs when {@link #fsmTrace} is on: without the trace the two
     * "nothing happened" cases (the stage took the click, or the hit test missed the node) are
     * indistinguishable from a click on empty space.</p>
     *
     * @param screenX cursor x in pixels (GL bottom-left origin)
     * @param screenY cursor y in pixels (GL bottom-left origin)
     * @return {@code true} when the click hit a node and was consumed
     */
    private boolean clickFsmGraph(float screenX, float screenY) {
        if (fsmController == null) {
            trace("click ignored: no controller");
            return false;
        }
        int before = fsmController.fsm() != null ? fsmController.fsm().currentIndex() : -1;
        // The controller hit-tests with the event's own position (hit test first, then the UI veto),
        // so a miss over a widget is reported as such instead of being masked by the pointer state.
        String[] reason = new String[1];
        boolean hit = fsmController.click(screenX, screenY, reason);
        if (!hit) {
            trace("click NOT consumed: " + reason[0] + TMP_CLICK_CONTEXT(screenX, screenY));
            return false;
        }
        PlayerAnimationFsm fsm = fsmController.fsm();
        int after = fsm != null ? fsm.currentIndex() : -1;
        trace("click consumed: node " + before + " -> " + after + " (" + reason[0]
            + ", blendTarget=" + (fsm != null ? fsm.blendTarget() : -1) + ")"
            + TMP_CLICK_CONTEXT(screenX, screenY));
        if (controls != null && controls.fsmSection() != null) {
            controls.fsmSection().sync(fsmController.fsm());
        }
        return true;
    }

    /**
     * Temporary diagnostic: window and back-buffer size plus the graph's geometry, so a coordinate
     * mismatch (a scaled back buffer makes {@code getHeight()} disagree with the GL viewport) shows
     * up in the log. TODO: remove with {@link #fsmTrace}.
     */
    private String TMP_CLICK_CONTEXT(float screenX, float screenY) {
        Graphics g = Gdx.graphics;
        return String.format(java.util.Locale.ROOT,
            " | event=(%.1f,%.1f) window=%dx%d backBuffer=%dx%d",
            screenX, screenY, g.getWidth(), g.getHeight(), g.getBackBufferWidth(),
            g.getBackBufferHeight());
    }

    /** Logs one step of the graph click path — only while {@link #fsmTrace} is on. TODO: remove. */
    private void trace(String message) {
        if (fsmTrace) Gdx.app.log("FsmTrace", message);
    }

    /** The machine's state, applied to the renderer fields (see {@link FsmTarget}). */
    private final class FsmTarget implements FsmController.Target {
        @Override
        public void applyFsmState(int currentIndex, int blendTarget, float blendWeight,
                                  float localTime) {
            currentAnim = currentIndex;
            MeshPlayer.this.blendTarget = blendTarget;
            MeshPlayer.this.blendWeight = blendWeight;
            time = localTime;
        }

        @Override
        public boolean isPointerOverUi() {
            return MeshPlayer.this.isPointerOverUi();
        }
    }

    /** Forwards the panel's FSM callbacks into the controller. */
    private final class FsmControlsListener implements FsmControlsSection.Listener {

        /** Pushes the machine into the panel after a user action. */
        private void refresh() {
            if (controls != null && controls.fsmSection() != null) {
                controls.fsmSection().sync(fsmController.fsm());
            }
        }

        @Override
        public void stateRequested(int index) {
            if (fsmController == null) return;
            fsmController.requestState(index);
            refresh();
        }

        @Override
        public void fadeSecondsChanged(float seconds) {
            if (fsmController == null) return;
            fsmController.withFsm(m -> m.setFadeSeconds(seconds));
        }

        @Override
        public void pauseToggled() {
            if (fsmController == null) return;
            fsmController.withFsm(m -> m.setPaused(!m.isPaused()));
            refresh();
        }

        @Override
        public void stepFrameRequested() {
            if (fsmController == null) return;
            fsmController.withFsm(PlayerAnimationFsm::stepOneFrame);
            refresh();
        }

        @Override
        public void timeScaleChanged(float scale) {
            if (fsmController == null) return;
            fsmController.withFsm(m -> m.setTimeScale(scale));
        }

        @Override
        public void resetRequested() {
            if (fsmController == null) return;
            blend.clear();
            fsmController.withFsm(PlayerAnimationFsm::reset);
            refresh();
        }
    }

    @Override
    protected void onDispose() {
        blend.dispose();
        for (PlayerClip a : anims) if (a.group != null) a.group.dispose();
        if (clipLoader != null) {
            clipLoader.dispose();
            clipLoader = null;
        }
        if (fsmController != null) {
            fsmController.dispose();
            fsmController = null;
        }
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
}
