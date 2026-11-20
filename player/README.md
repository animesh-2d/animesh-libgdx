# player

The standalone Animesh **mesh player**: it plays a mesh export so you can check an animation
without starting a game. It is the test bed of `animesh-lib` — everything it draws goes through
the library's public API.

## Run

```bash
./gradlew :player:run                  # plays the bundled example (assets/meshes/fire-export.json)
./gradlew :player:runVatDemo           # VAT benchmark: 100k instances, 1 draw call
./gradlew :player:fatJar               # build/libs/AnimeshPlayer-<version>.jar  (java -jar …)
./gradlew :player:jpackageImage        # build/jpackage/AnimeshPlayer/  (self-contained app)
```

On startup the player looks for a `meshes` directory next to the working directory (the
repository checkout, i.e. `player/assets/meshes`); its exports are scanned and the first one is
played. When no such directory exists — an installed app-image, for example — the **example
export bundled in the jar** is played.

## Controls

| control | action |
|---------|--------|
| **Load…** button | opens a file chooser and plays the selected `*.json` / `*.vat` export |
| drag & drop | dropping an export onto the window plays it too |
| **◀ Prev** / **Next ▶**, ← → P N Space | step through the animations of the loaded file |
| mouse wheel | zoom (top-right corner shows the factor) |
| W A S D / arrow keys | pan |
| **C** | cycle the shader colour override (normal → magenta → white) — pipeline check |
| **Close**, **Esc** | exit |

The HUD shows the file name (top-left), the current animation plus instance count and fps
(bottom-left) and the zoom factor (top-right).

## Bundled assets

```
assets/meshes/fire-export.json   the example export (2 animations: rest, idle)
assets/meshes/fire_texture.png   its model texture (referenced by "textureFile")
assets/skin/                     Scene2D skin (uiskin.json/.atlas/.png)
assets/fonts/NotoSans-Regular.ttf the UI font generated at runtime (gdx-freetype)
packaging/Animesh.ico            the app-image icon (jpackage --icon)
```

`assets` is the module's resource root, so all of it is packaged into the jar — the app-image
therefore needs no files next to the executable. To play another export, either use **Load…**
or drop the file onto the window; its texture must sit next to the JSON, exactly as the editor
exports it.

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
src/main/java/com/jb/rts/player/ui/                       PlayerScreen, NavBar, HudOverlay, UiSkin, UiLayout
src/main/java/com/jb/rts/player/                      VatDemoApplication (100k-instance demo)
src/main/java/com/jb/rts/player/desktop/              MeshPlayerLauncher (main class), VatDemoLauncher
src/test/java                                        frame timing, y-orientation, path resolution
```

The `com.jb.rts.player.ui` classes are a **copy** of the Scene2D chrome the rest of the Animesh
family uses (the editor's own libGDX screens): the library must stay UI- and asset-free, so the
player carries its own. Keeping them in their own package also means the editor can put this jar
on its class path next to its own `com.jb.rts.ui` toolkit without a class collision.
