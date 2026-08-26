# DisplayKit

[![build](https://github.com/Nano112/DisplayKit/actions/workflows/build.yml/badge.svg)](https://github.com/Nano112/DisplayKit/actions/workflows/build.yml)

> DisplayKit is an independent community project. It is not an official
> Minecraft product and is not approved by or associated with Mojang or
> Microsoft.

DisplayKit is a retained, server-driven Minecraft UI toolkit. It composes
pixel-space primitives into stable packet-only displays, renders the same
semantic actions in world surfaces or the inventory toolbar, and provides
native HUD and world-overlay renderers without mutating persistent world data.

The current API is designed around four boundaries:

1. **Models** — actions, properties, pagination, HUD state, and application state.
2. **Composition** — measured layout nodes and reusable widgets.
3. **Presentation** — a `Surface` hosted by a lifecycle-managed world session.
4. **Platform renderers** — Fabric pack synchronization, inventory toolbar,
   scoreboard sidebar, and boss bar.

Feature code owns domain state. DisplayKit owns measurement, stable identity,
depth, input routing, packet diffs, resource-pack readiness, and teardown.

## Modules

| Module | Responsibility |
| --- | --- |
| `core` | Platform-neutral models, state, layout, surfaces, widgets, actions, overlays |
| `pack` | Generated sprite/font assets and deterministic pack building |
| `fabric` | Minecraft/Fabric packets, input, pack lifecycle, HUD and toolbar renderers |
| `showcase` | Executable examples and visual inspection commands |

## Installation

Ready-to-run server jars are attached to
[GitHub Releases](https://github.com/Nano112/DisplayKit/releases). Use the
`DisplayKit-Fabric-mc<version>-<displaykit-version>.jar` asset for one-file
deployment; it nests `core` and `pack`.

The Fabric artifact is a standalone mod jar with `core` and `pack` nested for
deployment. Its Maven metadata also exposes those modules transitively so
consumer source code can import the complete API:

```kotlin
dependencies {
    modImplementation("io.schemat.displaykit:displaykit-fabric-mc1.21.11:0.1.0")
}
```

Platform-neutral tooling can depend on
`io.schemat.displaykit:displaykit-core:0.1.0`; pack generators can add
`io.schemat.displaykit:displaykit-pack:0.1.0`. Until a remote repository is
configured, build a source checkout with `./gradlew publishToMavenLocal` and
add `mavenLocal()` to the consuming build. Every artifact includes sources,
Maven license metadata, and the MIT license under `META-INF`.

## Fabric quick start

Initialize DisplayKit once from the Fabric integration, construct a `Surface`,
and compose a tree. `FabricSurfacePresentation` is the preferred lifecycle:
it pre-warms glyphs, waits for the client pack, binds state, opens the surface,
and releases every resource when the session closes.

```kotlin
val scope = StateScope()
val window = SurfaceWindow.vanilla(360, 240)
val surface = Surface(window.size.w, window.size.h, Vec3d.ZERO, 3.5f).also {
    it.renderMode = RenderMode.AUTO
    window.configure(it)
}

lateinit var world: WorldSurfaceSession
surface.layout { root ->
    window.build(root, "Machine", onClose = { world.close() }) { body ->
        body.addChild(DefinitionList(
            "status",
            listOf(DefinitionEntry("state", "State", "Ready")),
            DefinitionListStyle(width = 300),
        ).node)
    }
}

val presentation = FabricSurfacePresentation(
    owner = playerRef,
    surface = surface,
    anchor = SurfaceAnchor.facing(position, facing),
    stateScope = scope,
    diagnosticLabel = "machine-console",
)
world = presentation.session
presentation.present()
```

Use a `SurfaceAnchor.dynamic` when the origin or yaw changes. Use
`SurfaceLifecyclePolicy` for distance, timeout, and owner-offline behavior.
The surface tree is re-measured before every retained repaint, so dynamic text,
visibility, and switch pages stay naturally aligned.

## Composition primitives

Layout nodes use integer canvas pixels and never require application-owned
world offsets:

- `FlexNode`, `BoxNode`, `SpacerNode`, `WidgetNode`
- `SwitchNode` for stable keyed pages
- `ScrollNode` and `VirtualCanvasNode` for clipped, pannable content
- `SurfaceWindow`, `TabbedView`, `BlockTabStrip`
- `ActionButtonView` for a sprite face plus shallow block volume
- `DefinitionList`, `DataList`, `PagedDataListView`, `PagerView`
- `PropertySheetView` and `PagedPropertySheetView` over typed fields
- `StatusBannerView`, `EmptyStateView`, `ProgressBarView`
- `AsyncActionButtonView` for confirmation + operation state
- `TileMapView`, `CartographyMapView`, `SkillTreeView`, `TimelineView`

Widgets measure and ellipsize their own text. Chrome, labels, block volume,
hit regions, and focus all derive from the same measured rectangles.

## State and identity

Create state from one `StateScope`; pass lambdas that read it into widgets.
`MutableState`, derived state, and `batch` coalesce multiple mutations into one
repaint. `KeyedColumn`, data rows, switch pages, actions, sidebar lines, and
world-layer entries all use stable application keys. Keep those keys stable
when values or order change.

Use `scope.own(resource)` for subscriptions, timers, or controllers that must
not outlive a UI. `ConfirmAction` and `AsyncAction` register themselves with
their scope. A bound world session closes its state subscription automatically;
close the scope and model sessions with their presentation.

Use an `ExclusiveSessionGroup<K>` when several entry points represent the same
logical UI slot. Claiming a key closes the previous presentation, while stale
leases are harmless. This keeps commands, toolbar actions, and reconnect paths
from stacking duplicate windows without coupling those features to each other.

## Renderer-neutral actions

Describe behavior once with `ActionSpec`, `ActionPage`, and
`ActionMenuSession`. Render it in a world surface with `ActionMenuView`, or in
the real inventory hotbar with `InventoryToolbarRenderer.present`. Both
renderers share focus, paging, visibility, enabled/busy state, icons, submenu
navigation, and invocation semantics.

Use `ConfirmAction(scope, scheduler = platform.scheduler)` for an automatically
expiring two-step destructive action and `AsyncAction` for overlap-safe,
stale-result-safe operation state. `AsyncActionButtonView` composes both when a
surface needs the complete idle/confirm/busy/success/failure control.

Physical input is arbitrated by one `InteractionContext` per player. Platform
adapters submit semantic input and ordered `InteractionCandidate`s for toolbar,
surface, overlay, and world-tool layers. Cross-source duplicate packets are
suppressed once, and `diagnostics()` reports the consumer and attempted path.
Fabric packet, chat, scroll, pack-response, and disconnect callbacks cross into
game state through `ServerThreadDispatcher`; use the same boundary in Fabric
consumers rather than calling `MinecraftServer.execute` directly from Netty.

## Resource-pack deployment

Set `FabricDisplayKit.enableResourcePack` before server startup and configure
`FabricDisplayKit.sharedPackConfig` when clients are not on the same machine.
`PackConfig.publicAddress` must be a hostname or IP reachable by players;
`bindAddress` chooses the listening interface. The port can also be set with
`-Ddisplaykit.pack.port`, and the advertised host with
`-Ddisplaykit.pack.address`. Port `0` selects a free ephemeral port for tests.

Every push uses an immutable `/packs/<sha1>.zip` snapshot. A pack rebuild can
therefore happen while an older client download is in flight without serving
new bytes under the old advertised hash. Completed, failed, timed-out, and
disconnected transfers release their snapshots; server shutdown also stops the
HTTP executor.

Rebuilds requested during consent/download are coalesced behind the active
transfer, then only the newest artifact is sent. Presentations wait for that
whole chain instead of opening against an intermediate font. The quiet-period
deadline defaults to 120 seconds, renews on ACCEPTED and DOWNLOADED progress,
and is configurable with `PackConfig.presentationWaitTimeoutMillis` or
`-Ddisplaykit.pack.presentationWaitTimeoutMillis`.

Pack generation is byte-deterministic: assets are ordered, ZIP metadata is
fixed, caller-owned buffers are snapshotted, and unsafe archive paths are
rejected. Rebuilding unchanged inputs preserves the same SHA-1. DisplayKit does
not replace vanilla global shaders or post-processing chains; resource-pack
features stay scoped to the assets explicitly contributed by providers.

## Properties and structured data

`PropertyField` implementations provide typed get/set behavior and validation:
integer steppers, choices, booleans, and read-only values. Compose them into a
`PropertySheetModel`, then render with `PropertySheetView`; use
`PagedPropertySheetView` for schemas that must stay bounded by a window.

Use `DataEntry.key` for row identity. `DataList` supports selection and
activation; `PagedDataListView` combines it with a bounded `PaginationModel`
and compact sprite arrows.

## HUD and world layers

`SidebarModel` and `ProgressBarModel` are core models. Fabric renders them with
`ScoreboardSidebarRenderer` and `BossBarRenderer`. Sidebar rows are diffed by
key; the renderer uses a private packet-only scoreboard and never touches the
world scoreboard.

`WorldEntityLayer<K, S>` retains packet-only world entities by key, reconciles
viewer sets, batches metadata/transform/position updates, and recreates only
when an in-place update is impossible. `WorldViewerPolicies` provides fixed,
owner, radius, and predicate audiences; `metrics` exposes entity-viewer churn.
Java callers can use `WorldEntityLayer.recreating` or `.retaining` without
Kotlin function types. Use `WorldOverlay` for interactive planar cell grids and
`Hologram` for movable/tintable placement previews.

## Theme tokens

`DisplayTheme` groups colors, spacing, typography, controls, materials, sprite
assets, semantic depth, and motion. Start with `DisplayTheme.VANILLA_DARK` or
construct an application theme, then derive `windowStyle`, `actionMenuStyle`,
and `propertySheetStyle`. Keep feature branding in theme tokens; geometry still
comes from the primitive being composed.

## Rendering and performance

`RenderMode.AUTO` selects generated compositing when the glyph source is
available and otherwise uses entity rendering. Resource-pack-backed Fabric
surfaces should always use `FabricSurfacePresentation`; do not reproduce the
pack synchronization sequence in feature code.

The compositor owns semantic depth. Widgets should use painter operations and
layout composition, not hand-authored Z offsets. Block button volume is shallow
and centered behind its sprite face; text, chrome, hover, and hit regions are
derived from the same primitive geometry.

Uniform fills smaller than the compositor glyph automatically use one stretched
entity. Thin separators, row highlights, and exact progress values therefore do
not need renderer-specific minimums or quantization.

For moving groups, retain entities and update transforms or carrier movement.
Avoid clear/rebuild loops, per-tick entity respawns, unbounded generated glyph
geometry, or changing stable keys.

## Testing and visual verification

Run all library and consumer tests:

```text
./gradlew test
```

The core suite covers measurement, depth allocation, pointer routing, scroll,
stable reconciliation, resource growth bounds, actions, properties, maps,
trees, HUD validation, and world-layer packet diffs. Use the `showcase` server
plus MC-Inspector for final head-on, oblique, click, scroll, and teardown checks;
visual verification complements rather than replaces automated assertions.

For repeatable local inspection, `tools/mcinspector-call.mjs` invokes one
MC-Inspector MCP tool and writes screenshot image blocks to a named file:

```text
node tools/mcinspector-call.mjs screenshot \
  '{"scale":0.5}' /tmp/displaykit.png
```

Use `--list` to inspect the connected client's tools. Calls default to a
30-second timeout; `wait_ticks` derives a longer timeout from its tick count,
and `MCINSPECTOR_TIMEOUT_MS` can override either for long scripts.

See [ARCHITECTURE.md](ARCHITECTURE.md) for extension invariants and the release
gate used by repository consumers, [VERIFICATION.md](VERIFICATION.md) for the
latest automated and MC-Inspector acceptance record, and
[ADOPTION.md](ADOPTION.md) for a consumer-neutral production adoption path
covering private surfaces, shared world displays, holograms, toolbar actions,
and HUDs.

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Bundled font
and Minecraft-derived asset notices are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Compatibility

The coordinate-driven `FloatingUI`, old page/element stack, and floating
hotbar renderer were removed after all repository consumers migrated. Use
[MIGRATION.md](MIGRATION.md) to translate older integrations.

The current Fabric artifact targets Minecraft 1.21.11, Fabric Loader 0.18.2,
Fabric API 0.139.4+1.21.11, Kotlin 2.1, and Java 21. The platform-neutral core
does not depend on Minecraft server classes.
