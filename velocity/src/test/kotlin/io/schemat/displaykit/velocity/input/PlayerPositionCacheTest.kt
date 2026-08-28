package io.schemat.displaykit.velocity.input

import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPositionCacheTest {

    private val cache = PlayerPositionCache()
    private val player = UUID.randomUUID()

    @Test
    fun `no position means no snapshot and no eye`() {
        assertNull(cache.snapshot(player))
        assertNull(cache.eyePosition(player))
        // Rotation alone is not enough to place an eye in the world
        cache.updateRotation(player, 90f, 0f)
        assertNull(cache.eyePosition(player))
    }

    @Test
    fun `eye position adds standing height`() {
        cache.updatePosition(player, 10.0, 64.0, -5.0)
        val eye = cache.eyePosition(player)!!
        assertEquals(10.0, eye.x)
        assertEquals(65.62, eye.y, 1e-9)
        assertEquals(-5.0, eye.z)
    }

    @Test
    fun `sneaking lowers the eye and standing restores it`() {
        cache.updatePosition(player, 0.0, 0.0, 0.0)
        cache.setSneaking(player, true)
        assertEquals(1.27, cache.eyePosition(player)!!.y, 1e-9)
        cache.setSneaking(player, false)
        assertEquals(1.62, cache.eyePosition(player)!!.y, 1e-9)
    }

    @Test
    fun `look direction matches vanilla view vectors`() {
        cache.updatePosition(player, 0.0, 0.0, 0.0)

        // yaw 0 pitch 0: looking south, positive z
        cache.updateRotation(player, 0f, 0f)
        var look = cache.lookDirection(player)!!
        assertTrue(abs(look.x) < 1e-9 && abs(look.y) < 1e-9 && abs(look.z - 1.0) < 1e-9)

        // yaw 90: looking west, negative x
        cache.updateRotation(player, 90f, 0f)
        look = cache.lookDirection(player)!!
        assertTrue(abs(look.x + 1.0) < 1e-9 && abs(look.z) < 1e-9)

        // pitch 90: straight down
        cache.updateRotation(player, 0f, 90f)
        look = cache.lookDirection(player)!!
        assertTrue(abs(look.y + 1.0) < 1e-9)

        // pitch -90: straight up
        cache.updateRotation(player, 0f, -90f)
        look = cache.lookDirection(player)!!
        assertTrue(abs(look.y - 1.0) < 1e-9)
    }

    @Test
    fun `reset drops everything a server switch invalidates`() {
        cache.updatePosition(player, 1.0, 2.0, 3.0)
        cache.setSneaking(player, true)
        cache.reset(player)
        assertNull(cache.snapshot(player))
        // A fresh position after the switch starts standing
        cache.updatePosition(player, 4.0, 5.0, 6.0)
        assertEquals(6.62, cache.eyePosition(player)!!.y, 1e-9)
    }
}
