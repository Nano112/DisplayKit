package io.schemat.displaykit.fabric.pack

import io.schemat.displaykit.pack.PackManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces pack rebuilds behind a player's in-flight push.
 *
 * Minecraft can be waiting on a consent screen when a first surface warms
 * its glyphs. Pushing a replacement at that moment races two pack IDs through
 * one client prompt; the replacement may be discarded while the surface
 * waits for it and eventually opens with tofu glyphs. Record only that the
 * player needs the newest artifact, then send it after the current push has a
 * terminal response.
 */
internal class PackResendQueue {
    private val pending = ConcurrentHashMap.newKeySet<UUID>()

    /** Returns true when the latest artifact can be sent immediately. */
    fun request(playerId: UUID, status: PackManager.PackStatus?): Boolean {
        if (status == PackManager.PackStatus.SENDING) {
            pending.add(playerId)
            return false
        }
        pending.remove(playerId)
        return true
    }

    /** Consume a deferred request after the current artifact terminates. */
    fun take(playerId: UUID): Boolean = pending.remove(playerId)

    fun remove(playerId: UUID) {
        pending.remove(playerId)
    }

    fun clear() {
        pending.clear()
    }
}
