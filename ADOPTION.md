# DisplayKit adoption guide

This guide is a practical handoff for integrating DisplayKit into a Fabric
server project. DisplayKit is a general-purpose, server-driven UI library:
players do not install a DisplayKit client mod, and every display is sent with
packets to an explicit audience.

Consumer projects keep ownership of domain state and copy while DisplayKit owns
layout, rendering, input, resource-pack readiness, viewer reconciliation, and
cleanup.

## Compatibility and artifacts

The current release line targets:

- Minecraft 1.21.11;
- Java 21;
- Fabric Loader 0.18.2;
- Fabric API 0.139.4+1.21.11;
- Kotlin 2.1;
- DisplayKit 0.1.0.

For a Fabric server mod, use the complete artifact:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    modImplementation("io.schemat.displaykit:displaykit-fabric-mc1.21.11:0.1.0")
}
```

There is no remote Maven repository configured yet. From a DisplayKit source
checkout, publish the artifacts locally before building a consumer:

```text
./gradlew publishToMavenLocal
```

For server deployment without a source dependency, build and copy the remapped
Fabric jar from `fabric/build/libs` into the server's `mods`
directory. The Fabric jar nests the `core` and `pack` modules.

## Server initialization

DisplayKit's Fabric module initializes itself. A consumer only needs to opt into
the generated resource pack before the server starts. Doing this in the
consumer's `ModInitializer.onInitialize` is early enough:

```kotlin
import io.schemat.displaykit.fabric.FabricDisplayKit
import io.schemat.displaykit.pack.PackConfig

override fun onInitialize() {
    FabricDisplayKit.enableResourcePack = true
    FabricDisplayKit.sharedPackConfig = PackConfig(
        bindAddress = "0.0.0.0",
        publicAddress = "play.example.org",
        port = 8080,
        namespace = "example_ui",
        packDescription = "Example server interface assets",
    )
}
```

`publicAddress` is the hostname placed in URLs sent to players and must not
include a scheme. The configured TCP port must be reachable by every player.
Keep the default 120-second presentation timeout unless production resource
reload measurements show a reason to change it.

The pack pipeline is hash-addressed and deterministic. Presentations wait until
the player applies the newest pack, so consumers should use
`FabricSurfacePresentation` rather than manually pushing packs or opening a
`SurfaceHost`.

## Ownership boundary

Use this split consistently:

| Consumer owns | DisplayKit owns |
| --- | --- |
| application state and localized copy | measurement and ellipsis |
| permissions and persistence | stable primitive identity |
| action authorization and side effects | click, focus, scroll, and paging |
| viewer eligibility rules | packet-only viewer reconciliation |
| external services | pack synchronization and UI teardown |

Resolve translations before assigning labels to view models. When a locale or
translated value changes, update `MutableState`; do not rebuild the window or
manually offset text. DisplayKit re-measures the retained tree.

All state mutations and lifecycle calls must run on Minecraft's server thread.
Fabric callbacks that may originate on Netty should cross through
`ServerThreadDispatcher`.

## First private world panel

The standard lifecycle for a player-specific panel is:

```kotlin
val scope = StateScope()
val window = SurfaceWindow.vanilla(360, 240)
val surface = Surface(window.size.w, window.size.h, Vec3d.ZERO, 3.5f).also {
    it.renderMode = RenderMode.AUTO
    window.configure(it)
}

lateinit var presentation: FabricSurfacePresentation
surface.layout { root ->
    window.build(root, title, onClose = { presentation.close() }) { body ->
        body.addChild(
            DefinitionList(
                "status",
                listOf(
                    DefinitionEntry("owner", ownerLabel, ownerName),
                    DefinitionEntry("members", membersLabel, memberCount.toString()),
                ),
                DefinitionListStyle(width = 300),
            ).node,
        )
    }
}

presentation = FabricSurfacePresentation(
    owner = playerRef,
    surface = surface,
    anchor = SurfaceAnchor.facing(worldPosition, lookDirection),
    stateScope = scope,
    diagnosticLabel = "example-status",
)
presentation.present()
```

In production code, keep the presentation and `StateScope` in a session owner,
and close both when the application closes or replaces the feature. An
`ExclusiveSessionGroup<UUID>` is useful when a command, NPC, and toolbar action
can all open the same logical panel.

## Audience patterns

DisplayKit's displays are virtual: they are not saved as world entities and can
be shown to one player, a selected group, everyone online, or nearby eligible
players.

| Experience | Recommended API |
| --- | --- |
| private window | `FabricSurfacePresentation` with one owner |
| shared keyed labels or markers | `WorldEntityLayer` plus a viewer policy |
| nearby world signage | `WorldViewerPolicies.radius` |
| permission-gated diagnostics | `WorldViewerPolicies.predicate` |
| placement or selection preview | `Hologram.showTo(viewerIds)` |
| server-wide display | `WorldViewerPolicies.fixed(onlineIds)` |
| real inventory action bar | `InventoryToolbarRenderer` |
| sidebar or timed progress | `ScoreboardSidebarRenderer` / `BossBarRenderer` |

Do not spawn a separate persistent Minecraft entity for every viewer. A keyed
world layer retains virtual entity IDs and reconciles its audience independently
from its content.

## Recommended rollout

1. Start with one private panel. This validates pack delivery, reactive updates,
   input, and disconnect cleanup.
2. Move shared commands into `ActionSpec` and `ActionMenuSession`, then render
   the same action model as a surface or inventory toolbar.
3. Add placement previews with `Hologram`; update their origin and tint instead
   of respawning them while a player aims.
4. Add proximity labels or diagnostics with `WorldEntityLayer` and
   radius/predicate viewer policies.
5. Use `CartographyMapView` for spatial navigation and `SkillTreeView` where a
   product needs a pannable graph. Their background, nodes, edges, icons, and
   pan controls are retained primitives rather than custom entities.
6. Add native scoreboard and boss-bar renderers as needed, driven by the same
   stable application keys as world UI.

## Production checklist

- The advertised pack host and port are reachable from outside the server LAN.
- Every resource-pack-backed surface uses `FabricSurfacePresentation`.
- Consumers supply stable keys for actions, rows, nodes, markers, and HUD lines.
- Localized labels are state values; no feature computes text offsets.
- Every session closes on replacement, player disconnect, and server shutdown.
- Long-running work uses `AsyncAction`; destructive work uses `ConfirmAction`.
- Authorization is checked again inside the invoked application action.
- Moving displays retain entities and update transforms instead of clearing and
  rebuilding them each tick.
- Viewer eligibility is expressed with a policy and tested with two clients.
- `./gradlew test` passes in both the library and consumer before deployment.
- Head-on, oblique, click, scroll, pack-reload, reconnect, and teardown flows are
  inspected on the target server version.

## Where to look next

- [README.md](README.md) is the complete public API overview and quick start.
- [ARCHITECTURE.md](ARCHITECTURE.md) defines extension and performance rules.
- [MIGRATION.md](MIGRATION.md) maps removed legacy APIs to retained primitives.
- [VERIFICATION.md](VERIFICATION.md) records the automated and MC-Inspector gate.
- The `showcase` module contains executable picker, map, skill-tree, tab, and
  terminal scenes.

Keep the first integration deliberately narrow: install the artifact, configure
the pack endpoint, and ship one private surface with deterministic lifecycle
cleanup. Expand only after that path has been verified with real external
players.
