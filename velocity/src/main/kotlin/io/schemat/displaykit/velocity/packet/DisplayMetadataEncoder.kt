package io.schemat.displaykit.velocity.packet

import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemDyeColor
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3f
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.ItemDisplayTransform
import io.schemat.displaykit.render.TextAlignment
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualItemDisplay
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.velocity.state.VelocityBlockStateResolver
import io.schemat.displaykit.velocity.text.AdventureText

/**
 * Encodes VirtualEntity properties into PacketEvents EntityData lists for
 * the entity metadata packet.
 *
 * A 1:1 port of the Fabric MetadataEncoder onto PacketEvents types; the data
 * indices are the same Mojang 1.21.x layout and are documented there:
 *
 *   Display base:
 *     0  = SHARED_FLAGS (byte, 0x40 = glowing)
 *     8  = START_INTERPOLATION (int)
 *     9  = INTERPOLATION_DURATION (int)
 *     10 = TELEPORT_DURATION (int, only when > 0)
 *     11 = TRANSLATION (Vector3f)
 *     12 = SCALE (Vector3f)
 *     13 = LEFT_ROTATION (Quaternionf)
 *     14 = RIGHT_ROTATION (Quaternionf)
 *     15 = BILLBOARD (byte)
 *     16 = BRIGHTNESS (int)
 *     17 = VIEW_RANGE (float)
 *     22 = GLOW_COLOR_OVERRIDE (int)
 *   BlockDisplay: 23 = BLOCK_STATE
 *   TextDisplay:  23 = TEXT, 24 = LINE_WIDTH, 25 = BACKGROUND,
 *                 26 = TEXT_OPACITY, 27 = TEXT_DISPLAY_FLAGS
 *   ItemDisplay:  23 = ITEM, 24 = ITEM_DISPLAY_TRANSFORM
 */
class DisplayMetadataEncoder(private val blockStates: VelocityBlockStateResolver) {

    fun encode(entity: VirtualEntity, viewer: User): List<EntityData<*>> =
        encode(entity, viewer.clientVersion ?: ClientVersion.getLatest())

    fun encode(entity: VirtualEntity, version: ClientVersion): List<EntityData<*>> = when (entity) {
        is VirtualBlockDisplay -> encodeBlockDisplay(entity, version)
        is VirtualTextDisplay -> encodeTextDisplay(entity)
        is VirtualItemDisplay -> encodeItemDisplay(entity)
        else -> encodeShared(entity)
    }

    /**
     * Transform and interpolation fields only, for animation frames where
     * nothing else changed.
     */
    fun encodeTransformOnly(entity: VirtualEntity): List<EntityData<*>> {
        val entries = mutableListOf<EntityData<*>>()
        entries.add(EntityData(ID_START_INTERPOLATION, EntityDataTypes.INT, entity.startInterpolation))
        entries.add(EntityData(ID_INTERPOLATION_DURATION, EntityDataTypes.INT, entity.interpolationDuration))
        if (entity.teleportDuration > 0) {
            entries.add(EntityData(ID_TELEPORT_DURATION, EntityDataTypes.INT, entity.teleportDuration))
        }
        encodeTransformation(entity, entries)
        return entries
    }

    fun encodeBlockDisplay(entity: VirtualBlockDisplay, version: ClientVersion): List<EntityData<*>> {
        val entries = encodeShared(entity)
        entries.add(
            EntityData(ID_BLOCK_STATE, EntityDataTypes.BLOCK_STATE, blockStates.resolve(entity.blockState, version))
        )
        return entries
    }

    fun encodeTextDisplay(entity: VirtualTextDisplay): List<EntityData<*>> {
        val entries = encodeShared(entity)

        entries.add(EntityData(ID_TEXT, EntityDataTypes.ADV_COMPONENT, AdventureText.toAdventure(entity.text)))
        entries.add(EntityData(ID_LINE_WIDTH, EntityDataTypes.INT, entity.lineWidth))
        entries.add(EntityData(ID_BACKGROUND, EntityDataTypes.INT, entity.backgroundColor.toARGB()))
        entries.add(EntityData(ID_TEXT_OPACITY, EntityDataTypes.BYTE, entity.textOpacity))

        var flags = 0
        if (entity.hasShadow) flags = flags or 0x01
        if (entity.isSeeThrough) flags = flags or 0x02
        when (entity.textAlignment) {
            TextAlignment.LEFT -> flags = flags or 0x08
            TextAlignment.RIGHT -> flags = flags or 0x10
            TextAlignment.CENTER -> {}
        }
        entries.add(EntityData(ID_TEXT_DISPLAY_FLAGS, EntityDataTypes.BYTE, flags.toByte()))

        return entries
    }

    fun encodeItemDisplay(entity: VirtualItemDisplay): List<EntityData<*>> {
        val entries = encodeShared(entity)
        entries.add(EntityData(ID_ITEM, EntityDataTypes.ITEMSTACK, createItemStack(entity)))
        entries.add(
            EntityData(
                ID_ITEM_DISPLAY_TRANSFORM, EntityDataTypes.BYTE,
                encodeItemTransform(entity.itemDisplayTransform)
            )
        )
        return entries
    }

    private fun encodeShared(entity: VirtualEntity): MutableList<EntityData<*>> {
        val entries = mutableListOf<EntityData<*>>()

        entries.add(EntityData(ID_START_INTERPOLATION, EntityDataTypes.INT, entity.startInterpolation))
        entries.add(EntityData(ID_INTERPOLATION_DURATION, EntityDataTypes.INT, entity.interpolationDuration))
        if (entity.teleportDuration > 0) {
            entries.add(EntityData(ID_TELEPORT_DURATION, EntityDataTypes.INT, entity.teleportDuration))
        }

        encodeTransformation(entity, entries)

        entries.add(EntityData(ID_BILLBOARD, EntityDataTypes.BYTE, encodeBillboard(entity.billboard)))
        entries.add(EntityData(ID_BRIGHTNESS, EntityDataTypes.INT, encodeBrightness(entity.brightness)))
        entries.add(EntityData(ID_VIEW_RANGE, EntityDataTypes.FLOAT, entity.viewRange))

        if (entity.glowing) {
            entries.add(EntityData(ID_SHARED_FLAGS, EntityDataTypes.BYTE, 0x40.toByte()))
        }
        entity.glowColorOverride?.let { override ->
            entries.add(EntityData(ID_GLOW_COLOR_OVERRIDE, EntityDataTypes.INT, override.toARGB()))
        }

        return entries
    }

    /**
     * The matrix is the only source of truth, exactly as on Fabric: the
     * VirtualEntity's separate translation and scale fields are dirty-tracked
     * for diffing but never encoded.
     */
    private fun encodeTransformation(entity: VirtualEntity, entries: MutableList<EntityData<*>>) {
        val transform = TransformDecomposer.decompose(entity.transformation.joml)

        entries.add(
            EntityData(
                ID_TRANSLATION, EntityDataTypes.VECTOR3F,
                Vector3f(transform.translation.x, transform.translation.y, transform.translation.z)
            )
        )
        entries.add(
            EntityData(
                ID_LEFT_ROTATION, EntityDataTypes.QUATERNION,
                Quaternion4f(
                    transform.leftRotation.x, transform.leftRotation.y,
                    transform.leftRotation.z, transform.leftRotation.w
                )
            )
        )
        entries.add(
            EntityData(
                ID_SCALE, EntityDataTypes.VECTOR3F,
                Vector3f(transform.scale.x, transform.scale.y, transform.scale.z)
            )
        )
        entries.add(
            EntityData(
                ID_RIGHT_ROTATION, EntityDataTypes.QUATERNION,
                Quaternion4f(
                    transform.rightRotation.x, transform.rightRotation.y,
                    transform.rightRotation.z, transform.rightRotation.w
                )
            )
        )
    }

    private fun createItemStack(entity: VirtualItemDisplay): ItemStack {
        val itemType = ItemTypes.getByName(entity.itemId)
            ?: requireNotNull(ItemTypes.getByName("minecraft:leather_horse_armor")) {
                "PacketEvents item registry is missing the fallback item"
            }

        val stack = ItemStack.builder().type(itemType).amount(1).build()

        if (entity.customModelData != 0) {
            // Write the value into BOTH the floats and the strings list:
            // range_dispatch item definitions read the float while select
            // definitions (what the pack providers emit) match the string.
            // Only populating floats left every select-based definition
            // falling through to its fallback model.
            stack.setComponent(
                ComponentTypes.CUSTOM_MODEL_DATA_LISTS,
                ItemCustomModelData(
                    listOf(entity.customModelData.toFloat()),
                    emptyList(),
                    listOf(entity.customModelData.toString()),
                    emptyList(),
                )
            )
        }

        entity.itemColor?.let { color ->
            val rgb = (color.red shl 16) or (color.green shl 8) or color.blue
            stack.setComponent(ComponentTypes.DYED_COLOR, ItemDyeColor(rgb))
        }

        return stack
    }

    private fun encodeItemTransform(transform: ItemDisplayTransform): Byte = when (transform) {
        ItemDisplayTransform.NONE -> 0
        ItemDisplayTransform.THIRDPERSON_LEFTHAND -> 1
        ItemDisplayTransform.THIRDPERSON_RIGHTHAND -> 2
        ItemDisplayTransform.FIRSTPERSON_LEFTHAND -> 3
        ItemDisplayTransform.FIRSTPERSON_RIGHTHAND -> 4
        ItemDisplayTransform.HEAD -> 5
        ItemDisplayTransform.GUI -> 6
        ItemDisplayTransform.GROUND -> 7
        ItemDisplayTransform.FIXED -> 8
    }

    private fun encodeBillboard(billboard: Billboard): Byte = when (billboard) {
        Billboard.FIXED -> 0
        Billboard.VERTICAL -> 1
        Billboard.HORIZONTAL -> 2
        Billboard.CENTER -> 3
    }

    private fun encodeBrightness(brightness: Brightness?): Int {
        if (brightness == null) return -1
        return (brightness.block shl 4) or brightness.sky
    }

    companion object {
        const val ID_SHARED_FLAGS = 0
        const val ID_START_INTERPOLATION = 8
        const val ID_INTERPOLATION_DURATION = 9
        const val ID_TELEPORT_DURATION = 10
        const val ID_TRANSLATION = 11
        const val ID_SCALE = 12
        const val ID_LEFT_ROTATION = 13
        const val ID_RIGHT_ROTATION = 14
        const val ID_BILLBOARD = 15
        const val ID_BRIGHTNESS = 16
        const val ID_VIEW_RANGE = 17
        const val ID_GLOW_COLOR_OVERRIDE = 22

        const val ID_BLOCK_STATE = 23

        const val ID_TEXT = 23
        const val ID_LINE_WIDTH = 24
        const val ID_BACKGROUND = 25
        const val ID_TEXT_OPACITY = 26
        const val ID_TEXT_DISPLAY_FLAGS = 27

        const val ID_ITEM = 23
        const val ID_ITEM_DISPLAY_TRANSFORM = 24
    }
}
