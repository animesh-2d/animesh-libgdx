# FSM visualiser (mesh player)

The mesh player can drive its animation from a **gdx-ai state machine** and show that machine as an
interactive node graph. This page documents the black-box contract; you never need to read the code
to use it.

```
 .json / .vat export ──► Anim list ──► PlayerAnimationFsm ──► currentAnim / blendTarget / blendWeight
                                        (rules per clip)             │
                                                                     ▼
                                          VatInstancedMesh + AnimationBlend (one draw call)
                                        │
                                        └──► FsmGraphOverlay (nodes, arrows, live colours)
                                             FsmControlsSection (buttons, sliders, readout)
```

## Turning it on

| Where | How |
|---|---|
| Panel | **FSM** section → `FSM Mode (F)` check box |
| Keyboard | <kbd>F</kbd> |

With **FSM Mode off** the player behaves exactly as before: **Prev/Next** (or <kbd>←</kbd>/<kbd>→</kbd>)
step through the clips and **Blend with / Blend weight** mix two of them manually.

With **FSM Mode on** the machine owns the animation and the manual blend rows are dimmed (still
visible, but disabled). The graph appears in the lower-left corner, above the 3D view and below the
panel and the navigation bar.

## Manual paging works in both modes

**Prev/Next** (and <kbd>←</kbd>/<kbd>→</kbd>) is *not* a transition: it always works, whatever the
current state's rule says, and it never flashes a node red. With FSM Mode on the machine still
**follows** it — the picked clip becomes the active state, its clock restarts, and the crossfade is
dropped (a hard cut). That keeps the graph honest in both directions:

- the green node is always the clip that is on screen, and
- clicking any *other* node afterwards is a real transition from that clip.

A load places the machine on the clip the player starts drawing (animation 0), not on the inferred
`idle` state, so graph and screen agree from the first frame even for a file without an `idle` clip
(the bundled dino has `walk`, `run`, `die`).

## Diagnosing a click that does nothing

A graph click travels through four gates; a click that changes nothing has to die at one of them.
Press <kbd>F9</kbd> (or start with `-Danimesh.traceFsm=true`) and every left click is logged under
the `FsmTrace` tag — no rebuild needed, and it costs nothing while off:

| Log line | Meaning |
|---|---|
| `click ignored: fsmMode=false, controller=true` | **Not a bug in the click path.** FSM Mode is off, so the graph does not drive the animation yet; the click is then correctly left to the world (which is why the next line can also say the stage/chain did not hand it on). Press <kbd>F</kbd> or tick `FSM Mode (F)` and click again. |
| `click LOST before the graph: the stage consumed it \| stageHit=… \| rootTouchable=…` | The press never reached the graph listener. `stageHit` names the actor chain under the cursor (deepest first), `rootTouchable` the stage root's touchability. If `stageHit` reports *no touchable actor* while `rootTouchable=enabled`, the press was fired at the stage root — see the first pitfall below. Check the `fsmMode` flag in the same line first: with FSM mode off this message is expected. |
| `click NOT consumed: hit no node (clicked X,Y; nodes 0=x/y … r=26)` | The press arrived but missed every node body. Compare the clicked point with the node centres: if the click was visually on a node, the coordinates are being scaled (see below). |
| `click NOT consumed: node N hit, but the pointer is over a widget` | The node was hit, but a widget claims the pointer, so the widget keeps the click. |
| `click consumed: node A -> B …` | The click worked: the machine went from state `A` to state `B` (`blendTarget` shows the clip being faded out). |
| `click consumed: node A -> A … node A requested, but it is already the active state` | Correct behaviour: clicking the green node does not restart its phase (use **Reset** for that). |
| `click consumed: … node N requested, but the rules blocked it` | The target was refused by its rule; the node flashes red. See *Rules*. |

The trace also prints `window=…` and `backBuffer=…`. When these differ (HiDPI scaling), event
coordinates and the graph geometry are in different pixel spaces and the graph is drawn at one place
while being clickable at another; that is the first thing to check if every click misses.

## The FSM mode toggle

**The checkbox and the <kbd>F</kbd> key are the only ways in.** The graph may be drawn while the
machine is still off, so "the graph is visible" does not mean "clicks transition". The trace says so
explicitly (`fsmMode=false`), and clicks are then correctly left to the 3D world.

`F` and the checkbox share one path (`MeshPlayerControls.toggleFsmMode` → `fsmModeChanged` →
`MeshPlayer.setFsmMode`). Panel state buttons and the <kbd>1</kbd>…<kbd>9</kbd> hotkeys only act while
FSM mode is on.

## Rules — inferred, not configured

You load arbitrary exports, so the rules come from the clip **names** (normalised: lower case, first
word only, so `Walk_01`, `WALK` and `die (hit)` all match):

| Rule | Names (`AnimationRule`) | Behaviour |
|---|---|---|
| **Looping** | `idle`, `walk`, `run`, `sprint`, `defend`, `block`, `guard`, `rest`, `fly`, `swim`, `hover`, `loop`, `breathe`, `crawl` | Plays forever; may go to any other state. |
| **One-Shot → Idle** | `attack`, `hit`, `hurt`, `damage`, `shoot`, `throw`, `punch`, `kick`, `jump`, `jumping`, `leap`, `dodge`, `roll`, `cast`, `spell`, `skill`, `emote`, `wave`, `taunt` | Plays once, **blocks locomotion** (`idle`/`walk`/`run`/`sprint`) until `localTime >= duration`, then returns to the state it interrupted (or `idle`). Another action may interrupt it. |
| **Terminal** | `die`, `death`, `dead`, `dying`, `corpse`, `collapse` | Plays once, **holds the final frame** and rejects every exit. |

Anything else uses the clip's own `looping` flag; a single-frame clip is always a one-shot (a
one-pose loop looks identical, so it would read as a frozen state).

A one-shot whose clip has **no authored duration** (`duration() == 0`, e.g. a legacy pose bake) has
no end to detect, so it stays until you force a transition or press **Reset**.

## What the graph shows

Nodes are the loaded clips on a circle, the first one at the top. An arrow is drawn for every
*directed* pair; a bidirectional pair (`walk ↔ run`) is split into two parallel arrows so both tips
are visible.

**Crowded graphs hide arrows instead of squeezing them.** The nodes have a fixed pixel size, so with
many clips the gap between two neighbours becomes narrower than an arrow needs. From about **thirteen
clips** on (26 px nodes on a 132 px ring), an arrow between two immediate neighbours is no longer
drawn — it collapses to a dot inside its source node, which the two node bodies cover anyway. Nothing
pokes out of the ring and the graph stays drawable for any clip count; arrows between states that are
two or more steps apart are still shown (even at 24 clips), so the shape of the graph remains readable.

| Colour | Meaning |
|---|---|
| **green** | the active state |
| **amber** | the state being faded out; the running transition's arrow is amber with a dot walking along it |
| **white** | reachable right now |
| **dim gray** | blocked by the current rule |
| **red flash** (0.35 s) | this node was clicked while blocked (`REJECT_FLASH_SECONDS`) |

**Click a node** to transition to it — a blocked click is refused and flashes red instead of
changing state. **Hover** a node for a tooltip with its rule and clip length.

The click is part of the screen's input chain: the stage (panel, navigation bar, drop-downs) sees it
first, so a widget always wins. Only a click that really hits a node is consumed; a click anywhere
else in the window falls through and keeps its original meaning.

## Panel controls

| Control | Effect |
|---|---|
| **state buttons** | One per loaded clip (`1  idle`, `2  walk`, …), three per row. Green = active, dim = locked. Hotkeys <kbd>1</kbd>…<kbd>9</kbd> press the first nine. |
| **Bypass FSM Rules (Force Blend)** | Ignores every rule: any state may interrupt any other, including out of `die`. |
| **Crossfade (s)** | 0 – 0.60 s, applied to the **next** transition; a running fade keeps the length it started with. |
| **Pause / Resume** | Freezes the clip clocks (the graph keeps animating). |
| **+1 Frame** | Advances the clock by `1/60 s` **while paused** only. |
| **Time scale (x)** | 0 – 2× playback speed. |
| **Reset** | Back to the start state, all clocks at 0, no crossfade — the way out of a terminal state without forcing. |
| **readout** | Current clip, `← outgoing N%` during a fade, and `localTime / duration`. |

## Pitfalls

**The stage root must be `childrenOnly`.** A `Stage`'s root is a `Group`, and a `Group` is
`Touchable.enabled` by default (only `Table` defaults to `childrenOnly`). `Stage.touchDown` fires a
press at the root when no actor is under the cursor, and a listener on the root marks it handled — so
the stage returns `true` for clicks on **empty space** and every later input processor (the graph's
click handler) never sees them. The graph lives on empty space by design, so this silently disabled
*every* node click while the graph still drew perfectly. `PlayerScreen.create()` sets
`stage.getRoot().setTouchable(Touchable.childrenOnly)`; `MeshPlayerFsmGlTest` fails if that is
removed. The same trap applies to any actor added to the stage that is not a widget: give it
`childrenOnly` or `disabled`, never leave a `Group`/`Container` at `enabled` over the 3D view.

**`ShapeRenderer` batches must never leak.** `ShapeRenderer.begin()` throws
`IllegalStateException("Call end() before beginning a new shape batch.")` when the instance is
already inside a batch. `MeshPlayer` deliberately catches drawing exceptions to keep the window
alive, so a single leaked `begin()` would repeat that error on *every* later frame.
`FsmGraphOverlay` therefore pairs each pass with its own `finally { end(); }`, and closes a stray
batch left over from an earlier frame instead of throwing again.

**Arrow heads go in the filled pass.** `ShapeType.Line` silently drops `triangle()`, so the heads
are drawn in the filled pass and only the shafts in the line pass.

**The shape renderer needs a pixel projection.** The graph geometry is in screen pixels;
`FsmController` rebuilds the projection only when the window size changes, so a resize moves the
graph correctly and no `Matrix4` is allocated per frame.

**The graph never touches the screen edges.** Its centre is *derived* from the geometry — the
`FSM_GRAPH_MARGIN_X/Y` (100 px) plus the line width, node radius and ring radius — in
`UiLayout.fsmGraphCenterX/Y`. Do not hand-tune a centre: `FsmGraphLayoutTest` asserts that the
outermost node keeps its 100 px margin for 1..16 states and after a resize.

## Where the code lives

The feature is deliberately spread over small classes, so `MeshPlayer` stays readable:

| Class | Responsibility |
|---|---|
| `com.jb.rts.player.AnimationRule` | infers `Looping` / `One-Shot` / `Terminal` from a clip name |
| `com.jb.rts.player.DynamicAnimState` | one gdx-ai state node per loaded clip |
| `com.jb.rts.player.PlayerAnimationFsm` | the machine: rules, crossfade bookkeeping, rejection flash |
| `com.jb.rts.player.FsmController` | owns the machine + graph + `ShapeRenderer`, writes the screen's fields |
| `com.jb.rts.player.ui.FsmGraphLayout` | node/arrow geometry (GL-free, unit-tested; `moveTo` re-anchors without allocation) |
| `com.jb.rts.player.ui.FsmGraphOverlay` | draws the graph, colours, tooltip, hit test (`place` re-anchors on resize) |
| `com.jb.rts.player.ui.FsmControlsSection` | the panel block (buttons, sliders, readout) |
| `com.jb.rts.MeshPlayer` | wires it up: `updateFsm`, `drawFsmGraph`, `clickFsmGraph`, `setFsmMode` |

## How the renderer is driven

The machine never touches GPU resources. Each frame the player copies three values it already had:

| Field | From |
|---|---|
| `currentAnim` | `fsm.currentIndex()` |
| `blendTarget` | `fsm.blendTarget()` — the outgoing clip, or `-1` |
| `blendWeight` | `fsm.blendWeight()` — the crossfade progress |
| `time` | `fsm.localTime()` — wrapped for looping clips, clamped for terminal ones |

The existing `AnimationBlend` batch and the single instanced draw call are unchanged, so **every
instance on screen (1 or 10 000) transitions together**. While a fade runs, the incoming clip's
clock is held at its first frame and only restarted when the fade commits, so nothing pops.

## Lifecycle

- The machine is rebuilt on **every load** (`loadFile`), on success *and* failure, so it never refers
  to clips of a previous file. The graph is re-laid for the new state count.
- `ShapeRenderer` is created in `onCreate()` and disposed in `onDispose()`, together with the rest of
  the screen's resources.
- The graph hit test is skipped while the cursor is over a widget (`isPointerOverUi()`), so the panel
  and the nav bar always win.

## See also

- [`MESH_ANIMATION.md`](MESH_ANIMATION.md) — clip timing, crossfade and the library-side unit FSM
- [`VAT_INSTANCING.md`](VAT_INSTANCING.md) — the instanced rendering pipeline
- [`MESH_BLEND.md`](MESH_BLEND.md) — the export format
