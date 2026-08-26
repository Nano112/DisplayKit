package io.schemat.displaykit.fabric.pack

import io.schemat.displaykit.pack.PackManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackResendQueueTest {

    @Test
    fun rebuildWaitsForAnInflightPushAndCoalescesToOneFollowUp() {
        val queue = PackResendQueue()
        val player = UUID.randomUUID()

        assertFalse(queue.request(player, PackManager.PackStatus.SENDING))
        assertFalse(queue.request(player, PackManager.PackStatus.SENDING))
        assertTrue(queue.take(player))
        assertFalse(queue.take(player), "multiple rebuilds must produce only one latest-artifact push")
    }

    @Test
    fun terminalPlayerCanReceiveImmediatelyAndDisconnectDropsPendingWork() {
        val queue = PackResendQueue()
        val player = UUID.randomUUID()

        assertTrue(queue.request(player, PackManager.PackStatus.ACCEPTED))
        assertFalse(queue.request(player, PackManager.PackStatus.SENDING))
        queue.remove(player)
        assertFalse(queue.take(player))
    }
}
