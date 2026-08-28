# DisplayKit Velocity

A Velocity proxy platform for DisplayKit. Everything is packet-only through
[PacketEvents](https://github.com/retrooper/packetevents): the proxy has no
world and no entities, so surfaces are virtual display entities addressed to
each viewer, input is sniffed from the client's own packets, and eye/look for
hit-testing comes from a per-player position cache fed by movement packets.

This module is a **library inside a host Velocity plugin**, not a standalone
plugin. The host provides the proxy, its PacketEvents instance, and a logger.

## Quick start

```java
// Between your PacketEvents load() and init(), so the input listener joins
// the same registration window as your own listeners:
VelocityDisplayKit.init(new VelocityDisplayKitConfig(
    proxyServer,
    PacketEvents.getAPI(),
    logger,
    false // enableResourcePack, see below
));

// On proxy shutdown, before PacketEvents terminates:
VelocityDisplayKit.shutdown();
```

Present a surface the same way the Fabric platform does, swapping
`FabricSurfacePresentation` for `VelocitySurfacePresentation`:

```java
VelocityDisplayKit dk = VelocityDisplayKit.requireInstance();
PlayerRef owner = dk.playerRef(player);

Surface surface = new Surface(220, 250, Vec3d.ZERO, 2.4f);
surface.layout(root -> { /* compose */ });

new VelocitySurfacePresentation(owner, surface,
        SurfaceAnchor.facing(center, look)).present();
```

## Threading

Velocity has no main thread, so the module owns a single "DisplayKit-UI"
thread that plays that role: the tick loop, every surface mutation and every
DisplayKit callback are confined to it. Application code touching DisplayKit
state must go through `DisplayKit.platform.scheduler.runOnMainThread(...)` or
`VelocityDisplayKit.requireInstance().uiThread.dispatch(...)`. Callbacks that
do blocking work (database, HTTP) must hop off it again.

## Version floors and fallback

The proxy speaks each client's own protocol version, and this platform
renders one metadata layout, so it refuses to address old clients rather than
sending packets they would misparse:

- **ENTITIES** (pack-free) rendering needs a **1.21.9+** client
  (sprite object contents).
- Clients below the floor never receive a single DisplayKit packet — the
  `VersionGate` filters them out of every viewer set. Consumers should gate
  UI entry points on `versionGate.isSupported(player)` and fall back to
  whatever non-surface UX they already have.

## Resource pack

Not ported yet. `enableResourcePack = true` logs a warning and continues
pack-free; `RenderMode.AUTO` then renders every surface in ENTITIES mode,
which is DisplayKit's own built-in no-pack path. The `pack` module itself is
platform-neutral and already on the classpath, so a
`VelocityPackIntegration` mirroring the Fabric one (push via
`Player.sendResourcePackOffer`, responses via
`PlayerResourcePackStatusEvent`) is the natural follow-up.

## Entity ids

Virtual entities use `EntityIdAllocator`'s negative id space, disjoint from
the positive ids every backend allocates, so nothing the proxy spawns can
collide with a real entity. Interactions with negative-id entities are
swallowed before they reach the backend. A host plugin that also spawns its
own packet entities should draw ids from the same allocator.

## Known limitations

- Digging and placement packets swallowed while targeting a surface are
  acknowledged by the proxy itself, which rolls the client's prediction back
  without any world state. Clients older than 1.19 send no sequence id and
  have no prediction to roll back, so nothing is owed them.
- Players riding vehicles stop sending movement packets, so hover tracking
  degrades until they dismount.
- Sneak-only eye height is modelled; swimming and gliding poses are not.
- `TextInput` is stubbed: requests complete with null.
