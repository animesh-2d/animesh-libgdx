# FSM visualiser (mesh player)

The mesh player **always** drives its animation from a **gdx-ai state machine** and shows that
machine as an interactive node graph. There is no mode switch and no "bypass" box — the machine is
the animation system. This page documents the black-box contract; you never need to read the code to
use it.

```
 .json / .vat export ──► Anim list ──► PlayerAnimationFsm ──► currentAnim / blendTarget / blendWeight
                                        (rules per clip)             │
                                                                     ▼
                                           VatInstancedMesh + AnimationBlend (one draw call)
                                         │
                                         └──► FsmGraphOverlay (nodes, arrows, live colours)
                                              FsmControlsSection (buttons, spinner, readout)
```

## What is on screen

| Where | What |
|---|---|
| lower-left | the **state graph**: one node per loaded clip, one arrow per directed transition |
| panel → **FSM** | one trigger button per clip, the **Crossfade (s)** spinner, the transport, the readout |
| bottom-right bar | export selector, **Load…**, **Close** (no animation paging buttons — the graph does that) |

## Transitions are never blocked

The rules are **descriptive, not restrictive**. A click on a node, a state button or a hotkey always
transitions — a visualiser has to let you try every transition of a file. What the rules still decide:

1. **What the graph paints** — a target the rule allows is **green**, one it refuses is **red**. This
   is the only place the rules show, so "red but clickable" is intentional.
2. **Where the machine returns to** after a one-shot clip finished (the locomotion state it
   interrupted, or `idle`).
3. **How a clip's clock is read** — looping clips wrap, terminal clips hold their last frame.

A load places the machine on the clip the player starts drawing (animation 0), not on the inferred
`idle` state, so graph and screen agree from the first frame even for a file without an `idle` clip
(the bundled dino has `walk`, `run`, `die`).

## Paging with the keyboard

<kbd>←</kbd>/<kbd>→</kbd> (also <kbd>P</kbd>/<kbd>N</kbd>, <kbd>Space</kbd>) step through the clips
as a **hard cut**, not as a transition: the machine follows the pick, the clip restarts at frame 0
and any crossfade is dropped. That keeps the graph honest in both directions:

- the bright green node is always the clip that is on screen, and
- clicking any *other* node afterwards is a real transition from that clip.

## Rules — inferred, not configured

You load arbitrary exports, so the rules come from the clip **names** (normalised: lower case, first
word only, so `Walk_01`, `WALK` and `die (hit)` all match):

| Rule | Names (`AnimationRule`) | Behaviour |
|---|---|---|
| **Looping** | `idle`, `walk`, `run`, `sprint`, `defend`, `block`, `guard`, `rest`, `fly`, `swim`, `hover`, `loop`, `breathe`, `crawl` | Plays forever; every target is green. |
| **One-Shot → Idle** | `attack`, `hit`, `hurt`, `damage`, `shoot`, `throw`, `punch`, `kick`, `jump`, `jumping`, `leap`, `dodge`, `roll`, `cast`, `spell`, `skill`, `emote`, `wave`, `taunt` | Plays once; **paints** `idle`/`walk`/`run`/`sprint` red while it runs, then returns to the state it interrupted (or `idle`). Another action may take over (green). |
| **Terminal** | `die`, `death`, `dead`, `dying`, `corpse`, `collapse` | Plays once, holds the final frame, paints every exit red and never leaves by itself (use **Reset**). |

Anything else uses the clip's own `looping` flag; a single-frame clip is always a one-shot (a
one-pose loop looks identical, so it would read as a frozen state).

A one-shot whose clip has **no authored duration** (`duration() == 0`, e.g. a legacy pose bake) has
no end to detect, so it stays until you transition away or press **Reset**.

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
| **bright green** | the active state (the clip on screen) |
| **green** | a **legal** target: the active state's rule allows this transition |
| **red** | an **illegal** target: the rule refuses it — the graph's only "no" signal |
| **amber** | the state being faded out; the running transition's arrow is amber with a dot walking along it |

Arrows use the same coding (green = legal, red = illegal, amber = the crossfade in flight).

**Click a node** to transition to it — always accepted, whatever the colour. **Hover** a node for a
tooltip with its rule, clip length and whether the rule calls it `legal` or `blocked by rule`.

The click is part of the screen's input chain: the stage (panel, navigation bar, drop-downs) sees it
first, so a widget always wins. Only a click that really hits a node is consumed; a click anywhere
else in the window falls through and keeps its original meaning.

## Panel controls

| Control | Effect |
|---|---|
| **state buttons** | One per loaded clip (`1  idle`, `2  walk`, …), three per row. Bright green = active, green = legal from here, red = the rule refuses it (the button still works). Hotkeys <kbd>1</kbd>…<kbd>9</kbd> press the first nine. |
| **Crossfade (s)** | Numeric **spinner** (`-` / field / `+`): **0 – 60 s in 100 ms steps**, default **0.2 s**. Type a value (both `.` and `,` work) and confirm with **Enter**, or focus elsewhere; the value is snapped onto the 100 ms grid and clamped, so `0.17` becomes `0.2` and `75` becomes `60`. Applies to the **next** transition; a running fade keeps the length it started with. Back to 0.2 s after every load. |
| **Pause / Resume** | Freezes the clip clocks (the graph keeps animating). |
| **+1 Frame** | Advances the clock by `1/60 s` **while paused** only. |
| **Time scale (x)** | 0 – 2× playback speed. |
| **Reset** | Back to the start state, all clocks at 0, no crossfade — the way out of a terminal state. |
| **readout** | Current clip, `← outgoing N%` during a fade, and `localTime / duration`. |

## Diagnosing a click that does nothing

A graph click travels through four gates; a click that changes nothing has to die at one of them.
Press <kbd>F9</kbd> (or start with `-Danimesh.traceFsm=true`) and every left click is logged under
the `FsmTrace` tag — no rebuild needed, and it costs nothing while off:

| Log line | Meaning |
|---|---|
| `click LOST before the graph: the stage consumed it \| stageHit=… \| rootTouchable=…` | The press never reached the graph listener. `stageHit` names the actor chain under the cursor (deepest first), `rootTouchable` the stage root's touchability. If `stageHit` reports *no touchable actor* while `rootTouchable=enabled`, the press was fired at the stage root — see the first pitfall below. |
| `click NOT consumed: hit no node (clicked X,Y; nodes 0=x/y … r=26)` | The press arrived but missed every node body. Compare the clicked point with the node centres: if the click was visually on a node, the coordinates are being scaled (see below). |
| `click NOT consumed: node N hit, but the pointer is over a widget` | Correct behaviour: the panel or the nav bar owns that pixel, so the widget gets the click. |
| `click consumed: node A -> B …` | The click worked: the machine went from state `A` to state `B` (`blendTarget` shows the clip being faded out). |
| `click consumed: node A -> A … node A requested, but it is already the active state` | Correct behaviour: clicking the bright green node does not restart its phase (use **Reset** to replay it). |

The trace also prints `window=…` and `backBuffer=…`. When these differ (HiDPI scaling), event
coordinates and the graph geometry are in different pixel spaces and the graph is drawn at one place
while being clickable at another; that is the first thing to check if every click misses.

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
| `com.jb.rts.player.PlayerAnimationFsm` | the machine: rule verdict, crossfade bookkeeping, clocks |
| `com.jb.rts.player.FsmController` | owns the machine + graph + `ShapeRenderer`, writes the screen's fields |
| `com.jb.rts.player.ui.FsmGraphLayout` | node/arrow geometry (GL-free, unit-tested; `moveTo` re-anchors without allocation) |
| `com.jb.rts.player.ui.FsmGraphOverlay` | draws the graph, colours, tooltip, hit test (`place` re-anchors on resize) |
| `com.jb.rts.player.ui.FsmControlsSection` | the panel block (buttons, crossfade spinner, transport, readout) |
| `com.jb.rts.player.ui.NumberSpinner` | the `-` / field / `+` numeric entry (snap + clamp, GL-free arithmetic) |
| `com.jb.rts.MeshPlayer` | wires it up: `updateFsm`, `drawFsmGraph`, `clickFsmGraph` |

## How the renderer is driven

The machine never touches GPU resources. Each frame the player copies the values it already had:

| Field | From |
|---|---|
| `currentAnim` | `fsm.currentIndex()` |
| `blendTarget` | `fsm.blendTarget()` — the outgoing clip, or `-1` |
| `blendWeight` | `fsm.blendWeight()` — the **outgoing** clip's share: `1` when the fade starts, `0` when it has finished |
| `time` | `fsm.localTime()` — wrapped for looping clips, clamped for terminal ones |

`AnimationBlend` mixes `0 = current clip, 1 = target clip`, and the target of a crossfade *is* the
clip being faded out — hence the direction of `blendWeight` above. Feeding the fade progress here
instead would render the crossfade backwards (the outgoing pose snapping back in at the end).

The incoming clip's clock **restarts when the fade begins** and runs while the fade is drawn, so both
clips are on screen for the whole transition and the incoming one is simply picked up where the fade
left it. Committing the fade never rewinds it — a 60 s fade therefore ends exactly where it was
playing, with no pop. The existing `AnimationBlend` batch and the single instanced draw call are
unchanged, so **every instance on screen (1 or 10 000) transitions together**.

## Lifecycle

- The machine is rebuilt on **every load** (`loadFile`), on success *and* failure, so it never refers
  to clips of a previous file. The graph is re-laid for the new state count and the crossfade spinner
  snaps back to the default, because the new machine starts with `DEFAULT_FADE_SECONDS`.
- `ShapeRenderer` is created in `onCreate()` and disposed in `onDispose()`, together with the rest of
  the screen's resources.
- The graph hit test is skipped while the cursor is over a widget (`isPointerOverUi()`), so the panel
  and the nav bar always win.

## See also

- [`MESH_ANIMATION.md`](MESH_ANIMATION.md) — clip timing, crossfade and the library-side unit FSM
- [`VAT_INSTANCING.md`](VAT_INSTANCING.md) — the instanced rendering pipeline
- [`MESH_BLEND.md`](MESH_BLEND.md) — the export format

