# DisplayKit verification record

This record accompanies the retained-UI consolidation completed on 2026-08-25.
It describes the release gate that was actually run; it is not a substitute for
rerunning the gate after future rendering or protocol changes.

## Standalone publication gate

On 2026-08-26, the extracted repository was built from its own root with its
checked-in Gradle wrapper, settings, version catalog, and Java 21 daemon
criteria. The complete test, binary/source jar, remapped jar, and Maven POM gate
passed without relying on the embedding monorepo. The same gate then passed
again with DisplayKit embedded as `:libs:displaykit:*`, including all repository
consumers. This dual build is the compatibility check for future releases.

## Automated gate

- `./gradlew test` passed across DisplayKit and its in-repository consumers.
- `core`, `pack`, and the remapped Fabric module produced binary and source jars.
- All three Maven POMs generated successfully with transitive API dependencies
  and MIT license metadata.
- Binary jars contain `META-INF/LICENSE_displaykit`; the Fabric jar nests
  `displaykit-core` and `displaykit-pack` for one-file deployment.
- Identical resource-pack inputs produce byte-identical archives and hashes;
  staged byte arrays, archive paths, and escaped pack metadata have regression
  coverage.
- Resource-pack resend coalescing and shutdown-safe server-thread dispatch have
  focused regression coverage.
- `TileMapView` prepares both its normal and one-pixel-inset hover geometry once
  per map; regression coverage proves the first hover reuses the installed
  glyph set instead of growing the pack.
- The unused glass/rounded-corner prototype and its global vanilla shader,
  outline, and post-chain overrides were removed from the public API and pack.
- Production searches found no consumers of the removed page, element,
  `FloatingUI`, or virtual-hotbar stacks. Direct entity packets remain only in
  reusable DisplayKit packet/rendering adapters.

## MC-Inspector matrix

The running Minecraft 1.21.11 client was used through MC-Inspector's real input
and screenshot paths.

| Scene | Checks exercised |
| --- | --- |
| Picker | front, oblique and edge views; header/close depth; buttons; scroll; tabs; close/reopen |
| Cartography map | canvas targeting; arrows; drag/pan; selection; bounded background; first inset-tile hover without pack growth |
| Skill tree | coherent pan; sprite arrows; locked/unlocked nodes; stretched retained edges |
| Tabbed/property UI | tab switching; long property labels; paging; aligned button text and volume |
| Terminal | 45-line overflow; bottom anchoring; wheel scroll; bounded viewport |
| Toolbar | surface action menu; inventory selection by wheel; right-click invocation; restoration |
| Consumer integration | live composition; block-button labels; sprite glyphs; close control |

Packet capture during retained updates showed metadata/transform changes without
spawn/remove churn for unchanged primitives. Warm reopen did not rebuild the
resource pack.

## Pack and teardown fault cases

- A pack rebuild during an older download served each advertised SHA-1 from its
  immutable `/packs/<sha1>.zip` snapshot; the client applied the rebuilt pack
  without a hash mismatch or missing-glyph layout.
- A first join coalesced lazy glyph generation behind consent, renewed its
  progress deadline, applied the newest pack, and opened without tofu or a
  false timeout. A warm join did not rebuild unchanged assets.
- Opening a retained Grid Map, switching its tab, hovering an inset
  tile, and clicking it produced no `surface-hover` growth warning and no pack
  URL after the initial 276-byte bootstrap and one 4,804-byte prepared pack.
- An abrupt client process exit with an active retained surface performed all
  cleanup on the Minecraft server thread; no `StateScope` thread violation was
  logged.
- A connected terminal `stop` drained application player state on `Server thread`,
  produced no Netty-owned UI mutation, shut down the pack HTTP executor, and
  released the game, monitoring, and pack ports before Gradle exited.

Expected local-only client diagnostics were limited to offline-account Mojang
authentication failures and vanilla renderer warnings. No DisplayKit rendering,
input-routing, resource-pack hash, or teardown exception remained.
