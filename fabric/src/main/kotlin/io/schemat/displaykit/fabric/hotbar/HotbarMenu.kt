package io.schemat.displaykit.fabric.hotbar

import io.schemat.displaykit.ui.hotbar.HotbarHost
import io.schemat.displaykit.ui.hotbar.HotbarSlot
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
 * mechanism — the floating [io.schemat.displaykit.ui.hotbar.VirtualHotbar]
 * strip is deprecated in its favor.
 *
 * ### Interactions
 * - **Right-click** (use item / use block / use entity) presses the selected
 *   button. **Left-click** on a block or entity also presses it. Left-click on
 *   AIR does not reach the server without a client mod — right-click is the
 *   primary activation and button labels should assume it.
 * - **Scrolling** (held-slot change) fires [HotbarSlot.onScrollTo] — use for
 *   live previews while browsing (e.g. placement holograms).
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
object HotbarMenu {

    private val logger = LoggerFactory.getLogger("displaykit/hotbar-menu")

    private const val TAG_NONCE = "displaykit_menu"
    private const val TAG_SLOT = "displaykit_menu_slot"

    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var nonceCounter = 1
    private var registered = false
    /** Debounce: use/attack events can fire multiple times per click. */
    private const val CLICK_COOLDOWN_TICKS = 3

    // ── Public API ──────────────────────────────────────────────────────────

    fun isOpen(uuid: UUID): Boolean = sessions.containsKey(uuid)

    fun hostOf(uuid: UUID): HotbarHost? = sessions[uuid]

    /** Open a menu for the player (closing any existing one first). */
    fun open(player: ServerPlayer, rootSlots: List<HotbarSlot>, onClosed: (() -> Unit)? = null) {
        close(player)
        val session = Session(player, rootSlots, onClosed, nonceCounter++)
        sessions[player.uuid] = session
        session.stashAndShow()
    }

    /** Close and restore the player's real hotbar. Safe to call when closed. */
    fun close(player: ServerPlayer) {
        sessions.remove(player.uuid)?.restore()
    }

    // ── Event wiring (idempotent; call once from mod init) ─────────────────

    fun register() {
        if (registered) return
        registered = true

        UseItemCallback.EVENT.register { player, _, hand ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                sessions[player.uuid]?.let { it.pressSelected(); return@register InteractionResult.SUCCESS }
            }
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player, _, hand, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                sessions[player.uuid]?.let { it.pressSelected(); return@register InteractionResult.SUCCESS }
            }
            InteractionResult.PASS
        }
        UseEntityCallback.EVENT.register { player, _, hand, _, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                sessions[player.uuid]?.let { it.pressSelected(); return@register InteractionResult.SUCCESS }
            }
            InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { player, _, hand, _, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                sessions[player.uuid]?.let { it.pressSelected(); return@register InteractionResult.SUCCESS }
            }
            InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { player, _, hand, _, _ ->
            if (player is ServerPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                sessions[player.uuid]?.let { it.pressSelected(); return@register InteractionResult.SUCCESS }
            }
            InteractionResult.PASS
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (sessions.isEmpty()) return@register
            for (session in sessions.values.toList()) session.tick(server)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            sessions.remove(handler.player.uuid)?.restore()
        }
        // Crash recovery: a stash file on join means we never restored
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            recoverStash(server, handler.player)
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

    private class Level(var slots: List<HotbarSlot>, var page: Int = 0)

    private sealed class Cell {
        class Button(val slot: HotbarSlot) : Cell()
        object Prev : Cell()
        object Next : Cell()
        object Exit : Cell()
    }

    private class Session(
        val player: ServerPlayer,
        rootSlots: List<HotbarSlot>,
        val onClosed: (() -> Unit)?,
        val nonce: Int
    ) : HotbarHost {

        private val stack = ArrayDeque<Level>().apply { addLast(Level(rootSlots)) }
        private var stashed: List<ItemStack> = emptyList()
        private var stashedSelected = 0
        private var lastSelected = -1
        private var lastPressTick = 0L
        private var closed = false
        private var sweepCountdown = 0

        // ── HotbarHost ──

        override fun push(slots: List<HotbarSlot>) { stack.addLast(Level(slots)); render() }

        override fun pop() {
            if (stack.size <= 1) { HotbarMenu.close(player); return }
            stack.removeLast(); render()
        }

        override fun replaceCurrent(slots: List<HotbarSlot>) {
            val level = stack.lastOrNull() ?: return
            level.slots = slots
            render()
        }

        override fun popToRoot() {
            while (stack.size > 1) stack.removeLast()
            render()
        }

        override fun close() = HotbarMenu.close(player)

        // ── Open / restore ──

        fun stashAndShow() {
            val inv = player.inventory
            stashed = (0..8).map { inv.getItem(it).copy() }
            stashedSelected = selectedSlot()
            lastSelected = stashedSelected
            writeStashFile(player, stashed, stashedSelected)
            render()
        }

        fun restore() {
            if (closed) return
            closed = true
            val inv = player.inventory
            for (i in 0..8) inv.setItem(i, if (i < stashed.size) stashed[i] else ItemStack.EMPTY)
            setSelectedSlot(player, stashedSelected)
            player.inventoryMenu.broadcastChanges()
            deleteStashFile(player.level().server, player.uuid)
            runCatching { onClosed?.invoke() }
        }

        // ── Layout / rendering ──

        private fun layout(): Array<Cell?> {
            val level = stack.last()
            val cells = arrayOfNulls<Cell>(9)
            val content = level.slots
            if (content.size <= 8) {
                content.take(8).forEachIndexed { i, s -> cells[i] = Cell.Button(s) }
            } else {
                val pages = (content.size + PAGE_SIZE_PAGED - 1) / PAGE_SIZE_PAGED
                level.page = level.page.coerceIn(0, pages - 1)
                content.drop(level.page * PAGE_SIZE_PAGED).take(PAGE_SIZE_PAGED)
                    .forEachIndexed { i, s -> cells[i] = Cell.Button(s) }
                cells[SLOT_PREV] = Cell.Prev
                cells[SLOT_NEXT] = Cell.Next
            }
            cells[SLOT_EXIT] = Cell.Exit
            return cells
        }

        fun render() {
            val inv = player.inventory
            val cells = layout()
            for (i in 0..8) inv.setItem(i, buttonItem(i, cells[i]))
            player.inventoryMenu.broadcastChanges()
        }

        private fun buttonItem(index: Int, cell: Cell?): ItemStack {
            val item = when (cell) {
                null -> ItemStack(Items.GRAY_STAINED_GLASS_PANE).also {
                    it.set(DataComponents.CUSTOM_NAME, Component.literal(" ").withStyle { s -> s.withItalic(false) })
                }
                is Cell.Button -> ItemStack(iconFor(cell.slot)).also {
                    val name = Component.literal(cell.slot.label)
                        .withStyle { s -> s.withItalic(false).withColor(if (cell.slot.enabled) 0xFFFFFF else 0x777777) }
                    it.set(DataComponents.CUSTOM_NAME, name)
                }
                Cell.Prev -> ItemStack(Items.ARROW).also {
                    it.set(DataComponents.CUSTOM_NAME, Component.literal("◀ Prev (${stack.last().page + 1})").withStyle { s -> s.withItalic(false) })
                }
                Cell.Next -> ItemStack(Items.ARROW).also {
                    it.set(DataComponents.CUSTOM_NAME, Component.literal("Next ▶").withStyle { s -> s.withItalic(false) })
                }
                Cell.Exit -> ItemStack(if (stack.size > 1) Items.RED_CONCRETE else Items.BARRIER).also {
                    it.set(
                        DataComponents.CUSTOM_NAME,
                        Component.literal(if (stack.size > 1) "Back" else "Exit").withStyle { s -> s.withItalic(false) }
                    )
                }
            }
            val tag = CompoundTag()
            tag.putInt(TAG_NONCE, nonce)
            tag.putInt(TAG_SLOT, index)
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
            return item
        }

        private fun iconFor(slot: HotbarSlot): net.minecraft.world.item.Item {
            val id = slot.icon.id.substringBefore('[')
            val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id))
            return if (item === Items.AIR) Items.STONE else item
        }

        // ── Interaction ──

        fun pressSelected() {
            val tick = player.level().server.tickCount.toLong()
            if (tick - lastPressTick < CLICK_COOLDOWN_TICKS) return
            lastPressTick = tick
            when (val cell = layout()[selectedSlot().coerceIn(0, 8)]) {
                null -> {}
                is Cell.Button -> if (cell.slot.enabled) runCatching { cell.slot.onSelect(this) }
                    .onFailure { logger.error("menu button ${cell.slot.id} failed", it) }
                Cell.Prev -> { stack.last().page--; render() }
                Cell.Next -> { stack.last().page++; render() }
                Cell.Exit -> pop()
            }
        }

        // ── Tick: scroll detection + self-heal + vacuum ──

        fun tick(server: MinecraftServer) {
            if (closed) return
            if (player.isRemoved || player.hasDisconnected()) {
                HotbarMenu.sessions.remove(player.uuid)
                restore()
                return
            }

            val sel = selectedSlot()
            if (sel != lastSelected) {
                lastSelected = sel
                (layout()[sel.coerceIn(0, 8)] as? Cell.Button)?.slot?.onScrollTo
                    ?.let { runCatching { it(this) } }
            }

            heal()
            if (--sweepCountdown <= 0) {
                sweepCountdown = 10
                vacuumDroppedButtons()
            }
        }

        /** Re-impose menu items in 0-8; purge tagged items everywhere else. */
        private fun heal() {
            val inv = player.inventory
            val cells = layout()
            var dirty = false
            for (i in 0..8) {
                val current = inv.getItem(i)
                if (buttonIndexOf(current) != i || (current.isEmpty && cells[i] != null)) {
                    inv.setItem(i, buttonItem(i, cells[i]))
                    dirty = true
                }
            }
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

    /** A stash file on join = we crashed mid-menu; give the items back. */
    private fun recoverStash(server: MinecraftServer, player: ServerPlayer) {
        val file = stashFile(server, player.uuid)
        if (!Files.exists(file)) return
        runCatching {
            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val ops = player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)
            for (i in 0..8) {
                val stack = tag.read("s$i", ItemStack.OPTIONAL_CODEC, ops).orElse(ItemStack.EMPTY)
                player.inventory.setItem(i, stack)
            }
            setSelectedSlot(player, tag.getIntOr("sel", 0))
            player.inventoryMenu.broadcastChanges()
            logger.info("recovered stashed hotbar for ${player.gameProfile.name}")
        }.onFailure { logger.warn("stash recovery failed for ${player.uuid}", it) }
        deleteStashFile(server, player.uuid)
    }
}
