> **Update:** the floating `VirtualHotbar` strip described below is
> **deprecated**. Use `io.schemat.displaykit.fabric.hotbar.HotbarMenu` — a menu
> state over the player's REAL inventory hotbar: slots 1-9 become clickable
> button items (originals stashed to disk and restored on close/death/
> disconnect/crash-rejoin). Right-click presses the selected button; scrolling
> fires `HotbarSlot.onScrollTo` for live previews; button items are inert and
> self-healing. Slot 9 is always Exit/Back; >8 entries paginate with arrows at
> slots 7/8. `HotbarSlot`/`HotbarHost` are shared between both hosts, so menus
> written against `HotbarHost` run on either.

# DisplayKit: VirtualHotbar & Hologram

Two primitives for tool-driven building UX (added for hardwired's place/move/
edit tools; both are generic library features).

## VirtualHotbar (`io.schemat.displaykit.ui.hotbar`)

A server-driven, hotbar-like menu strip pinned to the player's view: a row of
icon buttons ~2.5 blocks ahead and below eye height, following the camera
(with reposition hysteresis) and auto-facing the player.

```kotlin
val hotbar = VirtualHotbar(platform, playerRef, listOf(
    HotbarSlot("place", "Place", BlockStateRef.LIME_CONCRETE) { hb ->
        hb.push(placeablesSubmenu)         // submenus stack; back slot appears
    },
    HotbarSlot("edit", "Edit", BlockStateRef.CYAN_CONCRETE) { openEditor() },
    HotbarSlot("locked", "???", enabled = false),
))
hotbar.show()      // hide() / destroy(); auto-hides after idleTimeoutTicks
```

- **Selection = look + right-click.** The slot nearest the crosshair
  highlights (standard hover); right-click selects. Selection is deliberately
  NOT driven by the real held-item slot: scrolling would swap the item the
  player is holding — which fights held-tool workflows (keep holding the
  Place tool while browsing) — and capturing scroll server-side would need
  another packet mixin. Back is an explicit `←` slot (stack depth > 1).
- **Pagination**: content beyond `maxVisible` (default 9) gets `◀`/`▶` edge
  slots with a `page/pages` label; positions stay stable across pages.
  Windowing math lives in `HotbarLayout` (pure, unit-tested).
- **Icons** are block states; item icons can be added later via
  `VirtualItemDisplay` if needed.

## Hologram (`io.schemat.displaykit.ui.Hologram`)

Ghost block-displays for placement previews: footprint cuboids + small
markers (port dots), tintable by validity, movable as one rigid group.

```kotlin
val holo = Hologram(platform)
    .cuboid(Vec3d.ZERO, Vec3f(9f, 5f, 9f))                    // footprint ghost
    .cuboid(Vec3d(0.0, 0.0, 9.0), Vec3f(9f, 1f, 2f),
            BlockStateRef.IRON_BLOCK)                          // real-block preview
    .marker(Vec3d(2.0, 1.0, -1.0), Hologram.SENSOR_MARKER)     // port dot (light blue)
    .marker(Vec3d(4.0, 1.0, -1.0), Hologram.ACTUATOR_MARKER)   // port dot (orange)
holo.showTo(setOf(player.uuid))
holo.tint = Hologram.Tint.INVALID   // red: can't place here
holo.moveTo(aimOrigin)              // batch re-anchor as the player aims
holo.destroy()
```

- **Visual treatment**: block displays can't be alpha-faded, so plain
  cuboids render as white/lime/red **stained glass** per tint with a
  matching **glow outline** (the encoder now sets the entity GLOWING flag —
  new `VirtualEntity.glowing` — so `glowColorOverride` actually shows).
  Explicit-block cuboids keep their block and express validity via glow
  only. All ghosts are inset 0.02 per face to avoid z-fighting.
- **Per-player**: packet-only, viewer-set based (nothing enters the world),
  same idiom as virtual labels.
- `localBounds()` exposes the pure geometry for tests/placement math.

## Sprite text components (`Sprites`)

`io.schemat.displaykit.fabric.text.Sprites` builds atlas-sprite text
components (MC 1.21.9+ `object` content type) — 8×8 inline icons that work in
chat, action bars, item names/lore, titles, and scoreboard lines:

- `Sprites.gui("recipe_book/page_forward")` — any GUI-atlas sprite
- `Sprites.atlas(atlasId, spriteId)` — any atlas
- `pageForward() / pageBackward() / cross()` — verified vanilla sprites,
  used by HotbarMenu's Prev/Next/Exit buttons
- `rmb() / lmb() / scroll()` — input badges (vanilla has no mouse sprites,
  so these are compact `[RMB]`-style text badges; one language everywhere)

Factories return fresh components per call — never share component instances.
