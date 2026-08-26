# DisplayKit architecture and extension contract

DisplayKit has one dependency direction: application models describe state and
commands; retained primitives measure and paint them; presentations own world
lifecycle; platform adapters translate packets and native Minecraft UI.

## Non-negotiable invariants

1. A node's measured rectangle is the source of truth for paint and hit tests.
2. Semantic depth is allocated by the surface. Feature code does not offset
   neighboring layers along the surface normal.
3. Application keys survive value and order changes. Reconciliation, not
   clear/rebuild, owns entity identity.
4. One physical input reaches one ordered `InteractionContext` dispatch.
5. Every timer, subscription, async action, presentation, and entity layer has
   an explicit owner and idempotent close path.
6. Renderer constraints terminate at painter or adapter boundaries. A widget
   must not know a source glyph's tile size or a packet field encoding.
7. Models are renderer-neutral. Inventory, world-surface, HUD, and future
   renderers must not become the application's state machine.

## Adding a primitive

- Accept semantic state and callbacks; do not accept world coordinates.
- Validate public geometry and identity at construction.
- Measure a bounded natural size and honor parent constraints.
- Paint only inside the assigned rectangle or an explicit child viewport.
- Use `identity` for independent mixed-media entities and stable keys for rows.
- Expose style as validated data, with theme-derived defaults where useful.
- Add behavior tests, invalid-input tests, and reconciliation/depth tests when
  the primitive emits independent entities.
- Add a showcase scene when layout, depth, animation, or input needs visual QA.

## Lifecycle

`StateScope` is the application-side ownership boundary. Use `own` for custom
resources. `FabricSurfacePresentation` binds scope invalidation, resource-pack
readiness, the `WorldSurfaceSession`, and cleanup. `WorldEntityLayer` owns
packet-only non-window entities and viewer diffs. All close methods are safe to
call repeatedly.

State and presentation mutation is server-thread-owned. Fabric connection
events are not assumed to arrive on that thread: adapters capture immutable
identifiers at the event boundary and cross through `ServerThreadDispatcher`.
The dispatcher rechecks ownership inside the queued wrapper because Minecraft
may run `execute` inline after shutdown begins. Primary cleanup is drained from
`SERVER_STOPPING` on the owner thread; later disconnect callbacks are
idempotent. Async work must return through the platform scheduler before
mutating a scope or world presentation.

Use `ExclusiveSessionGroup` above presentations that share a logical slot (for
example, one world window per player). A claim owns exactly one resource;
replacement closes the previous claim, and releasing an old lease cannot
unregister its replacement. The group is coordination, not rendering policy,
so feature modules remain independently composable.

Resource-pack pushes are content-addressed leases. `PackManager` pins the exact
SHA-1 snapshot advertised to each client and releases it on success, failure,
deadline, or disconnect. The HTTP server never serves mutable bytes from a
versioned URL, and its executor is owned by server lifecycle. Pack builders
produce deterministic archives and must not install global vanilla shader or
post-processing overrides as an implicit default; experimental client effects
belong in explicit opt-in providers with their own compatibility contract.
Rebuilds during an active transfer are coalesced and the presentation waiter is
released only after the newest artifact reaches a terminal state. Consent and
download progress renew a configurable quiet-period deadline.

## Release gate

- `./gradlew test` succeeds for every module and consumer.
- No production consumer references removed UI/page/hotbar stacks.
- Direct packet-only entity maps are replaced by `WorldEntityLayer` unless the
  code is itself a reusable DisplayKit primitive.
- Showcase and representative consumer scenes pass MC-Inspector head-on, oblique, click,
  scroll/drag, state-update, close/reopen, range, and disconnect checks.
- Logs contain no rendering, resource-pack, input-routing, or teardown errors.
- README, migration guide, examples, mod metadata, and license match the API.

The most recent executed matrix is recorded in [VERIFICATION.md](VERIFICATION.md).
