# Migrating to retained DisplayKit

The removed `FloatingUI` API directly positioned and rebuilt individual display
entities. Compose `SurfaceNode` primitives into a `Surface`, then present it through
`WorldSurfaceSession` or Fabric's `FabricSurfacePresentation`.

## Concept mapping

| Legacy | Retained API |
| --- | --- |
| `FloatingUI` | `Surface` + `WorldSurfaceSession` |
| `addPanel`, `addLabel`, manual offsets | measured nodes and widgets |
| `contentElements.clear()` and rebuild | state mutation + keyed reconciliation |
| `PageManager`, `TabbedContainer` | `TabbedView`, `SwitchNode` |
| hand-built buttons | `ActionButtonView` or `ActionMenuView` |
| feature-specific setting controls | `PropertyField` + `PropertySheetView` / `PagedPropertySheetView` |
| feature-specific rows | `DataList` / `PagedDataListView` |
| feature-owned status/empty labels | `StatusBannerView` / `EmptyStateView` |
| two-click timers and busy flags | `ConfirmAction` / `AsyncActionButtonView` |
| floating `VirtualHotbar` | semantic actions + `InventoryToolbarRenderer` |
| direct scoreboard/boss-event packets | HUD models + Fabric HUD renderers |
| direct virtual-entity maps | `WorldEntityLayer` |
| scattered click priority and debounce | `InteractionContext` |
| feature-to-feature manual close calls | `ExclusiveSessionGroup` |

There is intentionally no source-compatibility shim: keeping two layout,
depth, lifecycle, and input systems alive made correctness depend on which
entry point a consumer happened to choose.

## Migration order

1. Extract stable semantic models and callbacks from the old rendering code.
2. Create one `StateScope` for mutable presentation state.
3. Replace manual coordinates with a `SurfaceWindow` and layout nodes.
4. Replace rebuild loops with keyed widgets and state invalidation.
5. Move lifecycle and pack readiness into `FabricSurfacePresentation`.
6. Put mutually exclusive presentations in one `ExclusiveSessionGroup`.
7. Verify head-on and oblique geometry, every input path, scrolling, dynamic
   updates, distance close, disconnect close, and entity teardown.

When migrating a large property editor or timeline, give the primitive a
bounded viewport (`PagedPropertySheetView`, `TimelineView.maxVisibleTracks`)
instead of allowing a flex column to paint past its window.

Do not mechanically translate old world-space offsets into pixel coordinates.
The retained tree should express rows, columns, stacks, padding, and viewports;
measurement then becomes the single source of truth for paint and hit testing.
