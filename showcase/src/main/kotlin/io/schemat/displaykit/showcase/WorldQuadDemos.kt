package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.sprite.SpriteDisplay
import io.schemat.displaykit.sprite.SpriteGeometry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.joml.Matrix4f
import org.joml.Quaternionf

/**
 * `/dk demo scale` and `/dk demo block` — the world-quad render mode.
 */
object WorldQuadDemos {

    private const val LIFETIME_TICKS = 20L * 60

    fun register() {
        ShowcaseMod.registerDemo("scale", ::demoScale)
        ShowcaseMod.registerDemo("block", ::demoBlock)
    }

    /**
     * Places a 182x22 hotbar and a 16x16 item side by side. The hotbar must
     * measure 182/16 = 11.375 blocks wide and 22/16 = 1.375 tall; the item
     * must be exactly one block square.
     */
    private fun demoScale(player: ServerPlayer) {
        val index = SpriteIndex.bundled
        val hotbar = index.get(SpriteId("gui", "hud/hotbar")) ?: return
        val item = index.get(SpriteId("items", "item/diamond_sword")) ?: return

        val base = Vec3d(player.x, player.y + 2.0, player.z + 4.0)
        val viewers = setOf(player.uuid)

        val quads = listOf(
            SpriteDisplay.create(hotbar, base, scale = 1f),
            SpriteDisplay.create(item, base.copy(x = base.x + 8.0), scale = 1f)
        )

        for (quad in quads) {
            DisplayKit.platform.packetSender.spawnEntity(quad, viewers)
            DisplayKit.platform.packetSender.updateMetadata(quad, viewers)
        }

        val size = SpriteGeometry.worldSize(hotbar, 1f)
        player.sendSystemMessage(
            Component.literal(
                "Hotbar sprite: ${hotbar.width}x${hotbar.height}px " +
                    "-> ${"%.3f".format(size.x)} x ${"%.3f".format(size.y)} blocks. " +
                    "Item is 1x1. Despawns in 60s."
            )
        )
        despawnLater(quads.map { it.entityId }, viewers)
    }

    /**
     * Six oriented sprite quads forming a cube — proves world quads accept
     * arbitrary rotation, not just billboarding.
     */
    private fun demoBlock(player: ServerPlayer) {
        val face = SpriteIndex.bundled.get(SpriteId("blocks", "block/crafting_table_front")) ?: return
        val center = Vec3d(player.x, player.y + 2.0, player.z + 4.0)
        val viewers = setOf(player.uuid)
        val half = 0.5f

        // yaw, pitch, and the outward offset for each of the six faces
        val faces = listOf(
            Triple(0f, 0f, Vec3d(0.0, 0.0, -half.toDouble())),        // north
            Triple(180f, 0f, Vec3d(0.0, 0.0, half.toDouble())),       // south
            Triple(90f, 0f, Vec3d(-half.toDouble(), 0.0, 0.0)),       // west
            Triple(270f, 0f, Vec3d(half.toDouble(), 0.0, 0.0)),       // east
            Triple(0f, 90f, Vec3d(0.0, half.toDouble(), 0.0)),        // up
            Triple(0f, -90f, Vec3d(0.0, -half.toDouble(), 0.0))       // down
        )

        val entities = faces.map { (yaw, pitch, offset) ->
            val quad = SpriteDisplay.create(
                face,
                Vec3d(center.x + offset.x, center.y + offset.y, center.z + offset.z),
                scale = 1f,
                billboard = Billboard.FIXED
            )
            val s = SpriteGeometry.scaleFor(face, 1f)
            val t = SpriteGeometry.centeringTranslation(s)
            quad.transformation = Mat4f(
                Matrix4f()
                    .rotate(Quaternionf().rotationYXZ(
                        Math.toRadians(yaw.toDouble()).toFloat(),
                        Math.toRadians(pitch.toDouble()).toFloat(),
                        0f
                    ))
                    .translate(t.x, t.y, t.z)
                    .scale(s.x, s.y, s.z)
            )
            DisplayKit.platform.packetSender.spawnEntity(quad, viewers)
            DisplayKit.platform.packetSender.updateMetadata(quad, viewers)
            quad
        }

        player.sendSystemMessage(
            Component.literal("Sprite cube: 6 quads = 6 entities. Despawns in 60s.")
        )
        despawnLater(entities.map { it.entityId }, viewers)
    }

    private fun despawnLater(ids: List<Int>, viewers: Set<java.util.UUID>) {
        DisplayKit.platform.scheduler.scheduleDelayed(LIFETIME_TICKS) {
            DisplayKit.platform.packetSender.destroyEntities(ids, viewers)
        }
    }
}
