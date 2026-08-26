package io.schemat.displaykit.fabric.hotbar

import io.schemat.displaykit.action.ActionIcon
import io.schemat.displaykit.action.ActionInteraction
import io.schemat.displaykit.action.ActionMenuChange
import io.schemat.displaykit.action.ActionMenuListener
import io.schemat.displaykit.action.ActionMenuSession
import io.schemat.displaykit.action.ActionSpec
import io.schemat.displaykit.action.ActionSource
import io.schemat.displaykit.action.ActionTrigger
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import io.schemat.displaykit.ui.InteractionCandidate
import io.schemat.displaykit.ui.InteractionContexts
import io.schemat.displaykit.ui.InteractionInput
import io.schemat.displaykit.ui.InteractionLayer
import io.schemat.displaykit.ui.SemanticInteraction
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A menu state over the player's REAL inventory hotbar: while open, slots 0-8
 * are replaced with clickable button items (the player's actual items are
 * stashed and restored on close). This is the primary DisplayKit menu
 * mechanism. DisplayKit no longer ships a competing floating-hotbar renderer.
 *
 * ### Interactions
 * - **Right-click** (use item / use block / use entity) presses the selected
 *   button. **Left-click** on a block or entity also presses it. Left-click on
 *   AIR does not reach the server without a client mod — right-click is the
 *   primary activation and button labels should assume it.
 * - **Scrolling** (held-slot change) focuses the selected [ActionSpec], so its
 *   semantic focus handler can drive live previews.
 * - Button items are inert and self-healing: they can't be dropped, moved, or
 *   kept — a per-tick reconciler re-imposes the menu row, purges tagged items
 *   from the rest of the inventory/cursor, and vacuums tagged item entities
 *   near the player.
 *
 * ### Layout
 * Content ≤ 8 → slots 0-7; more → 6 per page with ◀/▶ at slots 6/7. Slot 8 is
 * always Exit (root) / Back (submenu).
 *
 * ### Item safety
 * On open, the stashed hotbar is ALSO written to
 * `<world>/displaykit/hotbar-stash/<uuid>.nbt`; if the server crashes mid-menu
 * the stash is restored on the player's next join. Restore also runs on
 * disconnect, death (before drops), and dimension change. The file is deleted
 * after every successful restore.
 */
internal object HotbarMenu {

    private val logger = LoggerFactory.getLogger("displaykit/hotbar-menu")

    private const val TAG_NONCE = "displaykit_menu"
    private const val TAG_SLOT = "displaykit_menu_slot"

    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var nonceCounter = 1
    private var registered = false
    /**
     * Duplicate-click suppression. One physical click can reach us twice via
     * DIFFERENT paths (an interaction-entity click routed by an arbiter plus
     * a vanilla use/attack event) within ~2 ticks — those are rejected. Rapid
     * genuine presses arrive via the SAME path and only need to be on a later
     * tick, so spamming Back works at up to one press per tick (the old
     * blanket 3-tick cooldown ate every other deliberate press).
     */
    private const val CROSS_SOURCE_COOLDOWN_TICKS = 2

    // ── Public API ──────────────────────────────────────────────────────────

    fun isOpen(uuid: UUID): Boolean = sessions.containsKey(uuid)

    /** Renderer-independent action state currently presented to this player. */
    fun actionSession(uuid: UUID): ActionMenuSession? = sessions[uuid]?.actions

    /**
     * Present a renderer-independent action session in the player's inventory
     * toolbar. Closing either side closes the other and restores the stash.
     */
    fun open(
        player: ServerPlayer,
        actions: ActionMenuSession,
        onClosed: (() -> Unit)? = null
    ) {
        require(!actions.isClosed) { "Cannot present a closed action session." }
        close(player)
        val session = Session(player, actions, onClosed, nonceCounter++)
        sessions[player.uuid] = session
        session.stashAndShow()
    }

    /** Close and restore the player's real hotbar. Safe to call when closed. */
    fun close(player: ServerPlayer) {
        sessions.remove(player.uuid)?.restore()
    }

    /**
     * Press the currently selected button. External click arbiters (e.g. a
     * world overlay whose interaction entities swallow the click before our
     * callbacks see it) route "this click means press" decisions here.
     */
    @JvmOverloads
    fun pressSelected(uuid: UUID, source: String = "arbiter") {
        val session = sessions[uuid] ?: return
        InteractionContexts.forPlayer(uuid).arbitrate(
            InteractionInput(SemanticInteraction.INVOKE, "hotbar:$source", "toolbar:invoke"),
            listOf(InteractionCandidate(InteractionLayer.TOOLBAR, "inventory-toolbar") {
                session.pressSelected(source)
                true
            }),
        )
    }

    /**
     * Consume only an explicit toolbar navigation control (previous, next,
     * back or exit). This keeps arbitration semantic: applications do not
     * inspect inventory slots or renderer-specific cell kinds.
     */
    fun pressSelectedNavigation(uuid: UUID, source: String = "arbiter"): Boolean =
        sessions[uuid]?.pressSelectedNavigation(source) == true

    private fun dispatchPhysicalPress(
        player: ServerPlayer,
        physicalKey: String,
        source: String,
    ): Boolean {
        val session = sessions[player.uuid] ?: return false
        return InteractionContexts.forPlayer(player.uuid).dispatch(
            InteractionInput(
                SemanticInteraction.INVOKE,
                source = source,
                physicalKey = physicalKey,
            ),
            listOf(InteractionCandidate(InteractionLayer.TOOLBAR, "inventory-toolbar") {
                session.pressSelected(source)
                true
            }),
        ).consumed
    }

    /**
     * Move the selection highlight WITHOUT firing the action's focus handler —
     * for arbiters that rebuild a page and manage their own selection state.
     */
    fun selectSlot(uuid: UUID, index: Int) {
        sessions[uuid]?.selectSilently(index.coerceIn(0, 8))
    }

    /**
     * Escape hatch: sneak-press pops to the root; a second sneak-press within
     * a second closes and restores outright. Frozen menus must always have a
     * way out that needs no working buttons.
     */
    fun sneakReset(uuid: UUID) { sessions[uuid]?.sneakReset() }

    /** Optional performance sinks supplied by the host application. */
    @JvmStatic var perfTime: ((String, Long) -> Unit)? = null
    @JvmStatic var perfCount: ((String, Long) -> Unit)? = null

    // ── Event wiring (idempotent; call once from mod init) ─────────────────

    fun register() {
        if (registered) return
        registered = true

        UseItemCallback.EVENT.register { player, _, hand ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                if (dispatchPhysicalPress(player, "click:right", "fabric:hotbar-use-item")) {
                    return@register InteractionResult.SUCCESS
                }
            }
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player, _, hand, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                if (dispatchPhysicalPress(player, "click:right", "fabric:hotbar-use-block")) {
                    return@register InteractionResult.SUCCESS
                }
            }
            InteractionResult.PASS
        }
        // Clicks on Interaction entities belong to DisplayKit's UI routing
        // (world overlays, panels) — those arbiters call pressSelected(uuid)
        // themselves when the click should mean "press". Handling them here
        // too would double-fire every ground/UI click.
        UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND &&
                entity !is net.minecraft.world.entity.Interaction
            ) {
                if (dispatchPhysicalPress(player, "click:right", "fabric:hotbar-use-entity")) {
                    return@register InteractionResult.SUCCESS
                }
            }
            InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { player, _, hand, _, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                if (dispatchPhysicalPress(player, "click:left", "fabric:hotbar-attack-block")) {
                    return@register InteractionResult.SUCCESS
                }
            }
            InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND &&
                entity !is net.minecraft.world.entity.Interaction
            ) {
                if (dispatchPhysicalPress(player, "click:left", "fabric:hotbar-attack-entity")) {
                    return@register InteractionResult.SUCCESS
                }
            }
            InteractionResult.PASS
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (sessions.isEmpty()) return@register
            val t0 = System.nanoTime()
            for (session in sessions.values.toList()) session.tick(server)
            perfTime?.invoke("hotbar.tick", (System.nanoTime() - t0) / 1000)
        }

        // Disconnect restores in-memory but KEEPS the stash file: if player
        // data was already saved this tick (crash shutdown saves players
        // BEFORE the disconnect event fires), only join recovery can undo it.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            ServerThreadDispatcher.dispatch(server) {
                sessions.remove(playerId)?.restore(deleteFile = false)
            }
        }
        // Server stopping: restore every open session NOW. Fabric fires this
        // at the head of MinecraftServer.stopServer, i.e. BEFORE the
        // "Saving players" pass — so restored hotbars are what gets persisted
        // even on a crash-initiated shutdown. Files kept as belt-and-braces.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            if (sessions.isNotEmpty()) {
                logger.info("server stopping — restoring ${sessions.size} open hotbar menu(s)")
                for (uuid in sessions.keys.toList()) {
                    sessions.remove(uuid)?.restore(deleteFile = false)
                }
            }
        }
        // Crash recovery: a stash file on join means the restored state was
        // never persisted — give the items back. Then, regardless of stash,
        // purge any button-tagged items that survived in the saved inventory.
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            recoverStash(server, handler.player)
            sweepTaggedItems(handler.player)
        }
        // Restore BEFORE vanilla death drops so the real items drop, not buttons
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayer) sessions.remove(entity.uuid)?.restore()
            true
        }
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, _, _ ->
            sessions.remove(player.uuid)?.restore()
        }
    }

    // ── Session ─────────────────────────────────────────────────────────────

    private const val PAGE_SIZE_PAGED = 6
    private const val SLOT_PREV = 6
    private const val SLOT_NEXT = 7
    private const val SLOT_EXIT = 8
    private sealed class Cell {
        class Button(val action: ActionSpec) : Cell()
        object Prev : Cell()
        object Next : Cell()
        object Exit : Cell()
    }

    private class Session(
        val player: ServerPlayer,
        val actions: ActionMenuSession,
        val onClosed: (() -> Unit)?,
        val nonce: Int
    ) {
        private var actionSubscription: AutoCloseable? = null
        private var stashed: List<ItemStack> = emptyList()
        private var stashedSelected = 0
        private var lastSelected = -1
        private var lastPressTick = -10L
        private var lastSneakTick = Long.MIN_VALUE
        private var lastPressSource = ""
        private var closed = false
        private var sweepCountdown = 0

        private fun pop() {
            if (actions.depth <= 1) { HotbarMenu.close(player); return }
            actions.pop()
        }

        private fun popToRoot() {
            actions.popToRoot()
        }

        // ── Open / restore ──

        fun stashAndShow() {
            val inv = player.inventory
            // A stash may never contain button items (would re-inject them on
            // restore); anything tagged in the hotbar at open time is leftover
            // from a failed cleanup and gets stashed as empty.
            stashed = (0..8).map { i ->
                val st = inv.getItem(i)
                if (buttonIndexOf(st) != null) ItemStack.EMPTY else st.copy()
            }
            stashedSelected = selectedSlot()
            lastSelected = stashedSelected
            writeStashFile(player, stashed, stashedSelected)
            actionSubscription = actions.subscribe(ActionMenuListener { snapshot ->
                when (snapshot.change) {
                    ActionMenuChange.CLOSE -> HotbarMenu.close(player)
                    ActionMenuChange.FOCUS -> Unit
                    else -> runCatching { render() }.onFailure {
                        logger.error(
                            "action menu render failed (page={}) — closing safely",
                            snapshot.currentPage.id,
                            it
                        )
                        HotbarMenu.close(player)
                    }
                }
            })
            render()
            // One-time discoverability for the escape hatch
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Stuck? Sneak-click resets the menu")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), true)
        }

        /**
         * @param deleteFile normal closes delete the crash stash; DISCONNECT /
         *   SERVER_STOPPING restores KEEP it — if the player's data was saved
         *   before this restore ran (exactly what happens in a crash-shutdown:
         *   saveAll runs first, the disconnect event after), the buttons are
         *   already persisted and only the join-side recovery can fix them.
         *   Re-applying an already-restored stash on join is a no-op, so
         *   keeping the file is always safe.
         */
        fun restore(deleteFile: Boolean = true) {
            if (closed) return
            closed = true
            actionSubscription?.close()
            actionSubscription = null
            if (!actions.isClosed) actions.close()
            val inv = player.inventory
            for (i in 0..8) inv.setItem(i, if (i < stashed.size) stashed[i] else ItemStack.EMPTY)
            setSelectedSlot(player, stashedSelected)
            player.inventoryMenu.broadcastChanges()
            if (deleteFile) deleteStashFile(player.level().server, player.uuid)
            runCatching { onClosed?.invoke() }
        }

        // ── Layout / rendering ──

        private fun layout(): Array<Cell?> {
            val cells = arrayOfNulls<Cell>(9)
            val visibleCount = actions.currentPage.visibleActions().size
            if (visibleCount <= 8) {
                actions.window(8).actions.forEachIndexed { i, action ->
                    cells[i] = Cell.Button(action)
                }
            } else {
                val window = actions.window(PAGE_SIZE_PAGED)
                window.actions.forEachIndexed { i, action ->
                    cells[i] = Cell.Button(action)
                }
                if (window.hasPrevious) cells[SLOT_PREV] = Cell.Prev
                if (window.hasNext) cells[SLOT_NEXT] = Cell.Next
            }
            cells[SLOT_EXIT] = Cell.Exit
            return cells
        }

        /**
         * Delta-render: only slots whose content actually differs are written.
         * Every real slot write is observed by vanilla's per-tick
         * `broadcastChanges` and fires `InventoryChangeTrigger` → recipe/
         * advancement awards — a nested-trigger path with a known CME race
         * (crash-2026-07-03_11.29.32). Rewriting identical buttons on every
         * page change/scroll hammered that path; writing only true deltas
         * shrinks the trigger surface to the unavoidable minimum.
         */
        fun render() {
            val inv = player.inventory
            val cells = layout()
            var written = 0
            for (i in 0..8) {
                val target = buttonItem(i, cells[i])
                if (!ItemStack.matches(inv.getItem(i), target)) {
                    inv.setItem(i, target)
                    written++
                }
            }
            if (written > 0) {
                player.inventoryMenu.broadcastChanges()
                perfCount?.invoke("hotbar.slotWrites", written.toLong())
            }
        }

        private fun buttonItem(index: Int, cell: Cell?): ItemStack {
            val item = when (cell) {
                null -> ItemStack(Items.GRAY_STAINED_GLASS_PANE).also {
                    it.set(DataComponents.CUSTOM_NAME, Component.literal(" ").withStyle { s -> s.withItalic(false) })
                }
                is Cell.Button -> ItemStack(iconFor(cell.action)).also {
                    val label = if (cell.action.busy) "${cell.action.label}…" else cell.action.label
                    val name = Component.literal(label)
                        .withStyle { s -> s.withItalic(false).withColor(if (cell.action.invokable) 0xFFFFFF else 0x777777) }
                    it.set(DataComponents.CUSTOM_NAME, name)
                }
                Cell.Prev -> ItemStack(Items.ARROW).also {
                    val window = actions.window(PAGE_SIZE_PAGED)
                    it.set(
                        DataComponents.CUSTOM_NAME,
                        io.schemat.displaykit.fabric.text.Sprites.pageBackward()
                            .append(Component.literal(" Prev (${window.pageIndex + 1}/${window.pageCount})"))
                            .withStyle { s -> s.withItalic(false) }
                    )
                }
                Cell.Next -> ItemStack(Items.ARROW).also {
                    it.set(
                        DataComponents.CUSTOM_NAME,
                        Component.literal("Next ")
                            .append(io.schemat.displaykit.fabric.text.Sprites.pageForward())
                            .withStyle { s -> s.withItalic(false) }
                    )
                }
                Cell.Exit -> ItemStack(if (actions.depth > 1) Items.RED_CONCRETE else Items.BARRIER).also {
                    it.set(
                        DataComponents.CUSTOM_NAME,
                        io.schemat.displaykit.fabric.text.Sprites.cross()
                            .append(Component.literal(if (actions.depth > 1) " Back" else " Exit"))
                            .withStyle { s -> s.withItalic(false) }
                    )
                }
            }
            val tag = CompoundTag()
            tag.putInt(TAG_NONCE, nonce)
            tag.putInt(TAG_SLOT, index)
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
            return item
        }

        private fun iconFor(action: ActionSpec): net.minecraft.world.item.Item = iconFor(action.icon)

        private fun iconFor(icon: ActionIcon): net.minecraft.world.item.Item = when (icon) {
            ActionIcon.Default -> Items.STONE
            is ActionIcon.Item -> registryItem(icon.item.itemId)
            is ActionIcon.Block -> registryItem(icon.state.id.substringBefore('['))
            is ActionIcon.Sprite -> iconFor(icon.fallback)
            is ActionIcon.Text -> iconFor(icon.fallback)
        }

        private fun registryItem(id: String): net.minecraft.world.item.Item {
            val item = runCatching { BuiltInRegistries.ITEM.getValue(Identifier.parse(id)) }
                .getOrDefault(Items.AIR)
            return if (item === Items.AIR) Items.STONE else item
        }

        // ── Interaction ──

        fun sneakReset() {
            val tick = player.level().server.tickCount.toLong()
            if (tick - lastSneakTick < 20) {
                logger.info("sneak-reset x2: closing menu for {}", player.gameProfile.name)
                HotbarMenu.close(player)
                return
            }
            lastSneakTick = tick
            logger.info("sneak-reset: popToRoot for {}", player.gameProfile.name)
            popToRoot()
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Menu reset — sneak-click again to exit"), true)
        }

        fun pressSelected(source: String = "arbiter") {
            // Escape hatch has absolute priority over any button/page state
            if (player.isShiftKeyDown) { sneakReset(); return }
            val tick = player.level().server.tickCount.toLong()
            val dup = if (source == lastPressSource) tick <= lastPressTick
                else tick - lastPressTick < CROSS_SOURCE_COOLDOWN_TICKS
            if (dup) {
                logger.debug("press rejected (dup): slot={} source={} lastTick={} tick={}",
                    selectedSlot(), source, lastPressTick, tick)
                return
            }
            lastPressTick = tick
            lastPressSource = source
            logger.debug("press: slot={} kind={} source={}", selectedSlot(), selectedCellKind(), source)
            when (val cell = layout()[selectedSlot().coerceIn(0, 8)]) {
                null -> {}
                is Cell.Button -> if (cell.action.invokable) runCatching {
                    actions.invoke(
                        cell.action.id,
                        ActionInteraction(
                            ActionSource.INVENTORY_TOOLBAR,
                            ActionTrigger.SECONDARY_CLICK,
                            player.uuid
                        )
                    )
                }
                    .onFailure {
                        logger.error("menu action ${cell.action.id} failed (page=${actions.currentPageId}) — recovering to root", it)
                        runCatching { popToRoot() }.onFailure { _ -> HotbarMenu.close(player) }
                    }
                Cell.Prev -> actions.previousPage(PAGE_SIZE_PAGED)
                Cell.Next -> actions.nextPage(PAGE_SIZE_PAGED)
                Cell.Exit -> pop()
            }
        }

        fun pressSelectedNavigation(source: String): Boolean {
            if (player.isShiftKeyDown) {
                pressSelected(source)
                return true
            }
            return when (layout()[selectedSlot().coerceIn(0, 8)]) {
                Cell.Prev, Cell.Next, Cell.Exit -> {
                    pressSelected(source)
                    true
                }
                else -> false
            }
        }

        // ── Tick: scroll detection + self-heal + vacuum ──

        fun tick(server: MinecraftServer) {
            if (closed) return
            if (player.isRemoved || player.hasDisconnected()) {
                HotbarMenu.sessions.remove(player.uuid)
                // Player may already be saved — keep the stash for join recovery
                restore(deleteFile = false)
                return
            }

            val sel = selectedSlot()
            if (sel != lastSelected) {
                lastSelected = sel
                val focusedId = (layout()[sel.coerceIn(0, 8)] as? Cell.Button)?.action?.id
                runCatching {
                    actions.focus(
                        focusedId,
                        ActionInteraction(
                            ActionSource.INVENTORY_TOOLBAR,
                            ActionTrigger.SCROLL_FOCUS,
                            player.uuid
                        )
                    )
                }.onFailure {
                    logger.error("menu focus handler failed (action={})", focusedId, it)
                }
            }

            // Self-heal + purge every few ticks, not every tick: render() is
            // delta-only, so drift is rare, and each inventory write feeds the
            // advancement-trigger path we're minimizing (see render()).
            if (--sweepCountdown <= 0) {
                sweepCountdown = 5
                heal()
                vacuumDroppedButtons()
            }
        }

        /** Re-impose menu items in 0-8 (delta only); purge tagged items everywhere else. */
        private fun heal() {
            render()
            val inv = player.inventory
            var dirty = false
            for (i in 9 until inv.containerSize) {
                if (buttonIndexOf(inv.getItem(i)) != null) { inv.setItem(i, ItemStack.EMPTY); dirty = true }
            }
            val carried = player.containerMenu.carried
            if (buttonIndexOf(carried) != null) { player.containerMenu.setCarried(ItemStack.EMPTY); dirty = true }
            if (dirty) player.inventoryMenu.broadcastChanges()
        }

        private fun vacuumDroppedButtons() {
            val nearby = player.level().getEntitiesOfClass(
                ItemEntity::class.java, player.boundingBox.inflate(8.0)
            )
            for (e in nearby) if (buttonIndexOf(e.item) != null) e.discard()
        }

        private fun buttonIndexOf(stack: ItemStack): Int? {
            if (stack.isEmpty) return null
            val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val tag = data.copyTag()
            if (!tag.contains(TAG_NONCE)) return null
            return tag.getIntOr(TAG_SLOT, -1).takeIf { it >= 0 }
        }

        private fun selectedSlot(): Int = player.inventory.selectedSlot

        /** Move the highlight without firing onScrollTo (see [HotbarMenu.selectSlot]). */
        fun selectedCellKind(): String? = when (layout()[selectedSlot().coerceIn(0, 8)]) {
            null -> null
            is Cell.Button -> "button"
            Cell.Prev -> "prev"
            Cell.Next -> "next"
            Cell.Exit -> "exit"
        }

        fun selectSilently(index: Int) {
            lastSelected = index
            setSelectedSlot(player, index)
        }
    }

    // ── Selected-slot access (isolated: mapping-sensitive) ─────────────────

    private fun setSelectedSlot(player: ServerPlayer, slot: Int) {
        player.inventory.selectedSlot = slot
        player.connection.send(
            net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(slot)
        )
    }

    // ── Crash-safe stash file ───────────────────────────────────────────────

    private fun stashDir(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("displaykit/hotbar-stash")

    private fun stashFile(server: MinecraftServer, uuid: UUID): Path =
        stashDir(server).resolve("$uuid.nbt")

    private fun writeStashFile(player: ServerPlayer, items: List<ItemStack>, selected: Int) {
        runCatching {
            val server = player.level().server
            val tag = CompoundTag()
            tag.putInt("sel", selected)
            val ops = player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)
            items.forEachIndexed { i, st ->
                tag.store("s$i", ItemStack.OPTIONAL_CODEC, ops, st)
            }
            Files.createDirectories(stashDir(server))
            NbtIo.writeCompressed(tag, stashFile(server, player.uuid))
        }.onFailure { logger.warn("could not write hotbar stash for ${player.uuid}", it) }
    }

    private fun deleteStashFile(server: MinecraftServer, uuid: UUID) {
        runCatching { Files.deleteIfExists(stashFile(server, uuid)) }
    }

    /**
     * Join-time defense in depth: delete every button-tagged item anywhere in
     * the saved inventory (main 36 + armor + offhand). Tagged items must never
     * survive as real items, stash or no stash.
     */
    private fun sweepTaggedItems(player: ServerPlayer) {
        val inv = player.inventory
        var purged = 0
        for (i in 0 until inv.containerSize) {
            val st = inv.getItem(i)
            if (st.isEmpty) continue
            val tag = st.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: continue
            if (tag.contains(TAG_NONCE)) {
                inv.setItem(i, ItemStack.EMPTY)
                purged++
            }
        }
        if (purged > 0) {
            player.inventoryMenu.broadcastChanges()
            logger.info("purged $purged stray menu button item(s) from ${player.gameProfile.name}'s inventory on join")
        }
    }

    /** A stash file on join = we crashed mid-menu; give the items back. */
    private fun recoverStash(server: MinecraftServer, player: ServerPlayer) {
        val file = stashFile(server, player.uuid)
        if (!Files.exists(file)) return
        runCatching {
            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val ops = player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)
            // Delta-apply: files also survive ORDERLY disconnects (see the
            // DISCONNECT handler), where the restore usually persisted fine —
            // then this is a no-op and shouldn't churn slots or log.
            var changed = 0
            for (i in 0..8) {
                val stack = tag.read("s$i", ItemStack.OPTIONAL_CODEC, ops).orElse(ItemStack.EMPTY)
                if (!ItemStack.matches(player.inventory.getItem(i), stack)) {
                    player.inventory.setItem(i, stack)
                    changed++
                }
            }
            if (changed > 0) {
                setSelectedSlot(player, tag.getIntOr("sel", 0))
                player.inventoryMenu.broadcastChanges()
                logger.info("recovered stashed hotbar for ${player.gameProfile.name} ($changed slot(s))")
            }
        }.onFailure { logger.warn("stash recovery failed for ${player.uuid}", it) }
        deleteStashFile(server, player.uuid)
    }
}
