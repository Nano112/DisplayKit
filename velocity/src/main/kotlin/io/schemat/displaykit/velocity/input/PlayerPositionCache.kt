package io.schemat.displaykit.velocity.input

import io.schemat.displaykit.math.Vec3d
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * The proxy's only source of player position and facing.
 *
 * A proxy has no world, so everything the raycast hit-testing needs comes
 * from sniffing the client's own movement packets: position, yaw and pitch
 * from the serverbound movement family, sneaking from entity action, and
 * corrections from the clientbound position sync a backend teleport sends.
 *
 * State resets on JOIN_GAME and RESPAWN because a server switch invalidates
 * every coordinate, and is removed on disconnect.
 */
class PlayerPositionCache {

    data class Snapshot(
        val position: Vec3d,
        val yaw: Float,
        val pitch: Float,
        val sneaking: Boolean,
    )

    private class Entry {
        @Volatile var x: Double = 0.0
        @Volatile var y: Double = 0.0
        @Volatile var z: Double = 0.0
        @Volatile var yaw: Float = 0f
        @Volatile var pitch: Float = 0f
        @Volatile var sneaking: Boolean = false
        @Volatile var hasPosition: Boolean = false
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    private fun entry(player: UUID) = entries.computeIfAbsent(player) { Entry() }

    fun updatePosition(player: UUID, x: Double, y: Double, z: Double) {
        val entry = entry(player)
        entry.x = x
        entry.y = y
        entry.z = z
        entry.hasPosition = true
    }

    fun updateRotation(player: UUID, yaw: Float, pitch: Float) {
        val entry = entry(player)
        entry.yaw = yaw
        entry.pitch = pitch
    }

    fun setSneaking(player: UUID, sneaking: Boolean) {
        entry(player).sneaking = sneaking
    }

    /** A server switch made every stored coordinate meaningless. */
    fun reset(player: UUID) {
        entries.remove(player)
    }

    fun remove(player: UUID) {
        entries.remove(player)
    }

    fun snapshot(player: UUID): Snapshot? {
        val entry = entries[player] ?: return null
        if (!entry.hasPosition) return null
        return Snapshot(Vec3d(entry.x, entry.y, entry.z), entry.yaw, entry.pitch, entry.sneaking)
    }

    /**
     * Eye position: feet plus the standing or sneaking eye height. The 1.62
     * and 1.27 constants are the vanilla player values; the proxy cannot see
     * poses beyond sneaking (elytra, swimming), which is an accepted
     * approximation.
     */
    fun eyePosition(player: UUID): Vec3d? {
        val entry = entries[player] ?: return null
        if (!entry.hasPosition) return null
        val eyeHeight = if (entry.sneaking) SNEAKING_EYE_HEIGHT else STANDING_EYE_HEIGHT
        return Vec3d(entry.x, entry.y + eyeHeight, entry.z)
    }

    /** Unit look vector from yaw and pitch, vanilla's view vector math. */
    fun lookDirection(player: UUID): Vec3d? {
        val entry = entries[player] ?: return null
        val yawRad = Math.toRadians(entry.yaw.toDouble())
        val pitchRad = Math.toRadians(entry.pitch.toDouble())
        val cosPitch = cos(pitchRad)
        return Vec3d(
            -sin(yawRad) * cosPitch,
            -sin(pitchRad),
            cos(yawRad) * cosPitch,
        )
    }

    fun clear() {
        entries.clear()
    }

    private companion object {
        const val STANDING_EYE_HEIGHT = 1.62
        const val SNEAKING_EYE_HEIGHT = 1.27
    }
}
