# DisplayKit: inventory toolbar and holograms

## Inventory toolbar

Use semantic `ActionPage`/`ActionSpec` state with
`InventoryToolbarRenderer`. The renderer temporarily presents actions in the
player's real inventory hotbar, persists and restores displaced items, uses
vanilla item sprites, supports scroll focus and right-click activation, and
owns Back/Exit plus paging.

```kotlin
val actions = ActionMenuSession(ActionPage("tools", listOf(
    ActionSpec.submenu("place", "Place") { placeActions },
    ActionSpec("edit", "Edit", onInvoke = ActionHandler { openEditor() }),
    ActionSpec("locked", "???", enabled = false),
)))
InventoryToolbarRenderer.present(player, actions)
```

Application navigation lives entirely in the action session; the inventory
toolbar is only one renderer for that state.

When a world tool and toolbar share the same physical click, submit both as
`InteractionCandidate`s to the player's `InteractionContext`. Use
`activateSelectedNavigation` as the toolbar candidate; application code should
never inspect inventory slots or renderer cell kinds to infer intent.

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
