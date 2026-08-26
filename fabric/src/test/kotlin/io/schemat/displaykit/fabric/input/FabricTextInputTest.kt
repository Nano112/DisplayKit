package io.schemat.displaykit.fabric.input

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.TextComponent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FabricTextInputTest {

    @Test
    fun networkCaptureDefersCallbackToTheProvidedServerDispatcher() {
        val input = FabricTextInput()
        val player = FakePlayer()
        var result: String? = null
        var queued: Runnable? = null
        input.requestInput(player, "") { result = it }

        assertTrue(input.handleChatMessage(player.uuid, "updated") { queued = it })
        assertNull(result, "network capture must not invoke application state on the caller thread")

        queued!!.run()
        assertEquals("updated", result)
    }

    private class FakePlayer : PlayerRef {
        override val uuid: UUID = UUID.randomUUID()
        override val name: String = "test"
        override fun eyePosition() = Vec3d.ZERO
        override fun lookDirection() = Vec3d(0.0, 0.0, 1.0)
        override fun isOnline() = true
        override fun sendMessage(message: TextComponent) = Unit
    }
}
