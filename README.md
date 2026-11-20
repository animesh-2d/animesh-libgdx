# Animesh — 2D mesh animation for libGDX

Animesh plays the **mesh exports** of the Animesh 2D mesh & animation editor in any libGDX
project. Every animation is baked once into **Vertex Animation Textures (VAT)** and drawn with
hardware instancing, so thousands of animated 2D meshes cost **one draw call**.

```
mesheditor export (.json)  ──►  animesh-lib  ──►  your libGDX game (desktop / Android / iOS)
                                   │
                                   └──►  player  ──►  standalone app-image (jpackage)
```

| module | what it is |
|--------|------------|
| **`animesh-lib`** | The published library: loads a mesh export (JSON) or a baked `.vat` file and plays it. Its **only** dependency is libGDX core — no JSON library, no natives, no UI, no assets. Version **1.0**. |
| **`player`** | The standalone desktop player: plays the bundled example export, opens any other export with its **Load…** button (or drag & drop) and ships as a self-contained `jpackage` app-image. Bundles a small copy of the Scene2D chrome so the library stays UI-free. |

## Quick start

```bash
./gradlew :animesh-lib:build          # jar + sources + javadoc, runs the tests
./gradlew :player:run                 # play the bundled example export
./gradlew :player:jpackageImage       # standalone app-image -> player/build/jpackage/AnimeshPlayer
./gradlew :animesh-lib:publishToMavenLocal   # install com.jb.rts:animesh-lib:0.1 in ~/.m2
```

Depend on the library from Gradle (Kotlin DSL shown, Groovy identical):

```kotlin
repositories {
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
}

dependencies {
  implementation("com.github.animesh-2d.animesh-libgdx:animesh-lib:v0.1")
}

```

Maven:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.animesh-2d.animesh-libgdx</groupId>
  <artifactId>animesh-lib</artifactId>
  <version>v0.1</version>
</dependency>
```

## Requirements

* **Java 17** (the published jar targets 17, so it also runs on newer JDKs).
* **OpenGL ES 3.0 / OpenGL 3.x** at runtime (`Gdx.gl30 != null`) — instancing, vertex texture
  fetch and `RGBA32F` textures. On desktop configure the backend with GL 3.3, e.g.
  `config.setOpenGLEmulation(GLEmulation.GL30, 3, 3)`.
* The library itself needs no assets. A textured export expects its picture next to the JSON
  (`"textureFile"`); the player bundles `fire_texture.png` with its example.

## Documentation

| document | content |
|----------|---------|
| [`animesh-lib/README.md`](animesh-lib/README.md) | the library as a black box: entry points, usage, memory ownership |
| [`animesh-lib/docs/MESH_BLEND.md`](animesh-lib/docs/MESH_BLEND.md) | the export format and the classic CPU pose blender |
| [`animesh-lib/docs/VAT_INSTANCING.md`](animesh-lib/docs/VAT_INSTANCING.md) | the VAT pipeline: bone/colour/step textures, shaders, limits |
| [`player/README.md`](player/README.md) | the player: controls, load button, packaging, icon |

## Repository layout

```
animesh-lib/           the published library (com.jb.rts:animesh-lib)
  src/main/java        com.jb.rts.mesh.*  (blend = loading/CPU blending, vat = GPU playback)
  src/test/java        unit tests (no GL context needed)
  docs/                format + pipeline documentation
player/                the standalone player
  assets/              skin, fonts and the example export (bundled into the jar)
  packaging/           Animesh.ico (app-image icon)
  src/main/java        com.jb.rts.MeshPlayer, com.jb.rts.player, com.jb.rts.player.ui
test-assets/           real exports used by the tests of both modules
```

`test-assets/` is not shipped — it only feeds the tests that verify real editor output (the
tests read it as `../test-assets/meshes/...` relative to the module directory).

## Versioning & CI

* The version lives in **`gradle.properties`** (`animeshVersion=1.0`) and is used for the library
  jar and the player app-image alike. Bump it there; nothing else hard-codes a version.
* [`.github/workflows/build.yml`](.github/workflows/build.yml) builds and tests the library on
  Linux, then builds the player (tests + `jpackageImage`) on Windows and uploads both artifacts.
  Pushing a `v*` tag also publishes the jar to GitHub Packages.

## License

[MIT](LICENSE)
