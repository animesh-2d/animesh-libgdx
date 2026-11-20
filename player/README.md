# player

Download AnimeshPlayer-windows.zip from the Releases page, extract it, and run AnimeshPlayer.exe (on Windows, click More info → Run anyway if SmartScreen prompts).

## Run

```bash
./gradlew :player:run                  # plays the bundled example (assets/meshes/fire-export.json)
./gradlew :player:runVatDemo           # VAT benchmark: 100k instances, 1 draw call
./gradlew :player:fatJar               # build/libs/AnimeshPlayer-<version>.jar  (java -jar …)
./gradlew :player:jpackageImage        # build/jpackage/AnimeshPlayer/  (self-contained app)
```

On startup the player looks for a `meshes` directory next to the working directory (the
repository checkout, i.e. `player/assets/meshes`); its exports are scanned and the first one is
played. The **export selector** on the left of the navigation bar then lists every export the
player knows: the scanned files, the **examples bundled in the jar** (all a downloaded player has,
see [Bundled assets](#bundled-assets)) and everything opened with **Load…** / drag & drop. If an
export fails to load, the error is reported in the window and the navigation bar stays usable:
**Load…** opens another file (drag & drop works too), so a broken export never leaves you stuck.

## Controls

| control | action |
|---------|--------|
| **export selector** (drop-down, bottom-right) | switch to another known export — the bundled examples, the files scanned at startup and everything opened since |
| **Load…** button | opens a file chooser and plays the selected `*.json` / `*.vat` export |
| drag & drop | dropping an export onto the window plays it too |
| **◀ Prev** / **Next ▶**, ← → P N Space | step through the animations of the loaded file |
| **Count (Enter)** (panel) | number of copies to draw — type a number and confirm with **Enter**; **I** focuses the field (max 10 000) |
| **Size deviation (%)** (panel) | randomises each copy's size by up to ± the given percentage of its authored size |
| **Placement** (panel) | **Grid** or **Random**; instances are always sorted by descending y, so lower copies are drawn in front |
| **Blend with** / **Blend weight (%)** (panel) | blends the current animation with another clip of the same file (e.g. walk → run); `— none —` switches it off. **B** steps the blend target, **[** / **]** change the weight by 10 % |
| **Colours** (panel) | one row per colour of the loaded file (the JSON's `colors`); editing a row recolours only the mesh parts that use it, live while the picker is dragged. A `.vat` file has no palette and says so; a **textured** export (like the bundled fire) keeps sampling its texture, so only the palette of an untextured export is visible |
| mouse wheel | zooms the drawing area (top-right corner shows the factor); over the parameter panel or the navigation bar it belongs to the UI — it scrolls the panel's form instead |
| W A S D / arrow keys | pan |
| **Close**, **Esc** | exit |

The collapsible parameter panel sits below the file name and can be reduced to a single line with
its ▼ button. The HUD shows the file name (top-left), the current animation — plus the blend target
and weight while blending — the instance count and fps (bottom-left) and the zoom factor
(top-right).

## Bundled assets

```
assets/meshes/fire-export.json      the fire example (2 animations: rest, idle)
assets/meshes/fire_texture.png      its model texture (referenced by "textureFile")
assets/skin/                        Scene2D skin (uiskin.json/.atlas/.png)
assets/fonts/NotoSans-Regular.ttf   the UI font generated at runtime (gdx-freetype)
packaging/Animesh.ico               the app-image icon (jpackage --icon)
```

`assets` is the module's resource root, so all of it is packaged into the jar.

The other watchable examples are **not copied** into this module: `../test-assets/meshes` — the
real editor output the library's tests read — is bundled straight into the jar by the `examples`
source set in [`build.gradle`](build.gradle), landing there as `meshes/<file>.json`:

```
meshes/fire-export.json             rest + idle, textured (from ./assets)
meshes/dino_longneck-export.json    skinned: walk + die
meshes/tree_redwood-export.json     pose animation, no bones
meshes/test animation-export.json   skinned, animated vertex colours
```

`MeshPlayer.BUNDLED_EXAMPLES` names them (the first one is played on startup) and the export
selector offers them; `BundledExamplesTest` fails when that list and the folder drift apart or when
the Gradle wiring stops putting a file on the class path. Only the **fat jar / app-image** carries
them — the plain `player` jar is published for the editor's OpenGL preview and stays small.

`assets` and the `examples` source set together mean the app-image needs no files next to the
executable. To play another export, either use **Load…** or drop the file onto the window; its
texture must sit next to the JSON, exactly as the editor exports it.

## Packaging

`./gradlew :player:jpackageImage` builds one fat jar and hands it to the `jpackage` of the Java
17 toolchain (never the one on `PATH`), producing an app-image with its own runtime:

```
player/build/jpackage/AnimeshPlayer/
  AnimeshPlayer.exe        the player (Windows; bin/AnimeshPlayer on Linux/macOS)
  app/AnimeshPlayer-1.0.jar
  runtime/                 private JDK runtime (java.base, java.desktop, java.logging, jdk.unsupported)
```

* `java.desktop` is required for the Swing file chooser behind **Load…**,
  `jdk.unsupported` for GLFW's `Unsafe` usage.
* The icon is only applied on Windows (jpackage accepts `.ico` there); the other platforms use
  their default icon.
* The player always starts **fullscreen at the monitor's native resolution** in GL 3.3 — the VAT
  pipeline needs `glVertexAttribDivisor`, vertex texture fetch and `RGBA32F` textures.

## Module layout

```
src/main/java/com/jb/rts/MeshPlayer.java              the screen (ApplicationAdapter subclass)
src/main/java/com/jb/rts/player/                        InstanceField (grid/random, y z-order, sizes),
                                                        AnimationBlend (weighted clip preview),
                                                        MeshFileResolver (path/classpath resolution)
src/main/java/com/jb/rts/player/ui/                     PlayerScreen, NavBar, HudOverlay, UiSkin, UiLayout,
                                                        ControlPanel, MeshPlayerControls, ColorPicker,
                                                        ColorPickerDialog
src/main/java/com/jb/rts/player/                       VatDemoApplication (100k-instance demo)
src/main/java/com/jb/rts/player/desktop/              MeshPlayerLauncher (main class), VatDemoLauncher
src/test/java                                        frame timing, y-orientation, path resolution,
                                                     instance layout, instance-count entry,
                                                     palette recolour and wheel/UI routing
                                                     (live GL, opt-in)
```

The `com.jb.rts.player.ui` classes are a **copy** of the Scene2D chrome the rest of the Animesh
family uses (the editor's own libGDX screens): the library must stay UI- and asset-free, so the
player carries its own. Keeping them in their own package also means the editor can put this jar
on its class path next to its own `com.jb.rts.ui` toolkit without a class collision. The colour
picker is the family's colour editor, so editing a palette colour in the player looks and behaves
exactly like the one in the editor. `MeshPlayerControls` is the player's own panel on top of the
copied `ControlPanel`; `InstanceField` and `AnimationBlend` are GL-free (the former test-covered), so
the player's layout and blending logic do not depend on a window.

## Tests

```bash
./gradlew :player:test                    # GL-free: frame timing, orientation, export resolution,
                                          # instance layout/count entry, colour-table titles
./gradlew :player:test -Panimesh.gl=true  # + VatPaletteGlTest and WheelRoutingGlTest (need a GPU,
                                          # open a hidden window)
```

The two `*GlTest` classes need a real GL context. `VatPaletteGlTest` draws a mesh, edits a palette
colour, redraws and compares the framebuffer — the only way to catch a forgotten VBO upload in the
VAT palette refresh (a libGDX `Mesh` streams its vertices on `bind`, and the instanced batch draws
through its own VAO). `WheelRoutingGlTest` builds the real skin, nav bar and panel on a stage and
checks the input routing: `stage.hit(…)` must find nothing in the drawing area (so the wheel there
zooms the world) and the panel must hold the stage's scroll focus only while the cursor is over it.
Both are **opt-in** so that a headless CI needs no display; GLFW also requires the OS main thread on
macOS, so run them on Windows/Linux.

