package io.schemat.displaykit.fabric.packet

import com.mojang.math.Transformation
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.fabric.state.BlockStateResolver
import io.schemat.displaykit.render.*
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.DyedItemColor
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Encodes VirtualEntity properties into SynchedEntityData DataValue list for
 * ClientboundSetEntityDataPacket.
 *
 * Data indices (Mojang 1.21.x):
 *   Display base:
 *     8  = START_INTERPOLATION (int)
 *     9  = INTERPOLATION_DURATION (int)
 *     11 = TRANSLATION (Vector3f)
 *     12 = SCALE (Vector3f)
 *     13 = LEFT_ROTATION (Quaternionf)
 *     14 = RIGHT_ROTATION (Quaternionf)
 *     15 = BILLBOARD (byte)
 *     16 = BRIGHTNESS (int)
 *     17 = VIEW_RANGE (float)
 *     22 = GLOW_COLOR_OVERRIDE (int)
 *   BlockDisplay:
 *     23 = BLOCK_STATE (BlockState)
 *   TextDisplay:
 *     23 = TEXT (Component)
 *     24 = LINE_WIDTH (int)
 *     25 = BACKGROUND (int ARGB)
 *     26 = TEXT_OPACITY (byte)
 *     27 = TEXT_DISPLAY_FLAGS (byte)
 */
object MetadataEncoder {

    private const val ID_START_INTERPOLATION = 8
    private const val ID_INTERPOLATION_DURATION = 9
    private const val ID_TRANSLATION = 11
    private const val ID_SCALE = 12
    private const val ID_LEFT_ROTATION = 13
    private const val ID_RIGHT_ROTATION = 14
    private const val ID_BILLBOARD = 15
    private const val ID_BRIGHTNESS = 16
    private const val ID_VIEW_RANGE = 17
    private const val ID_GLOW_COLOR_OVERRIDE = 22

    private const val ID_BLOCK_STATE = 23

    private const val ID_TEXT = 23
    private const val ID_LINE_WIDTH = 24
    private const val ID_BACKGROUND = 25
    private const val ID_TEXT_OPACITY = 26
    private const val ID_TEXT_DISPLAY_FLAGS = 27

    private const val ID_ITEM = 23
    private const val ID_ITEM_DISPLAY_TRANSFORM = 24

    /**
     * Decompose a Mat4f transformation matrix into Minecraft's TRS components
     * (Translation, LeftRotation, Scale, RightRotation) and add them to the entries list.
     */
    private fun encodeTransformation(
        entity: VirtualEntity,
        entries: MutableList<SynchedEntityData.DataValue<*>>
    ) {
        val matrix = entity.transformation.joml
        val transform = Transformation(matrix)

        entries.add(SynchedEntityData.DataValue(
            ID_TRANSLATION, EntityDataSerializers.VECTOR3, transform.translation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_LEFT_ROTATION, EntityDataSerializers.QUATERNION, transform.leftRotation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_SCALE, EntityDataSerializers.VECTOR3, transform.scale
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_RIGHT_ROTATION, EntityDataSerializers.QUATERNION, transform.rightRotation
        ))
    }

    /**
     * Encode only transform + interpolation fields (6 entries instead of 10+).
     * Used during animation where blockState, brightness, viewRange etc. are unchanged.
     */
    fun encodeTransformOnly(entity: VirtualEntity): List<SynchedEntityData.DataValue<*>> {
        val entries = mutableListOf<SynchedEntityData.DataValue<*>>()
        entries.add(SynchedEntityData.DataValue(
            ID_START_INTERPOLATION, EntityDataSerializers.INT, entity.startInterpolation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_INTERPOLATION_DURATION, EntityDataSerializers.INT, entity.interpolationDuration
        ))
        encodeTransformation(entity, entries)
        return entries
    }

    fun encodeBlockDisplay(entity: VirtualBlockDisplay): List<SynchedEntityData.DataValue<*>> {
        val entries = mutableListOf<SynchedEntityData.DataValue<*>>()

        entries.add(SynchedEntityData.DataValue(
            ID_START_INTERPOLATION, EntityDataSerializers.INT, entity.startInterpolation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_INTERPOLATION_DURATION, EntityDataSerializers.INT, entity.interpolationDuration
        ))

        // Decompose and send the full transformation matrix
        encodeTransformation(entity, entries)

        entries.add(SynchedEntityData.DataValue(
            ID_BILLBOARD, EntityDataSerializers.BYTE, encodeBillboard(entity.billboard)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_BRIGHTNESS, EntityDataSerializers.INT, encodeBrightness(entity.brightness)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_VIEW_RANGE, EntityDataSerializers.FLOAT, entity.viewRange
        ))

        if (entity.glowColorOverride != null) {
            entries.add(SynchedEntityData.DataValue(
                ID_GLOW_COLOR_OVERRIDE, EntityDataSerializers.INT, entity.glowColorOverride!!.toARGB()
            ))
        }

        val blockState = BlockStateResolver.resolve(entity.blockState)
        entries.add(SynchedEntityData.DataValue(
            ID_BLOCK_STATE, EntityDataSerializers.BLOCK_STATE, blockState
        ))

        return entries
    }

    fun encodeTextDisplay(entity: VirtualTextDisplay): List<SynchedEntityData.DataValue<*>> {
        val entries = mutableListOf<SynchedEntityData.DataValue<*>>()

        entries.add(SynchedEntityData.DataValue(
            ID_START_INTERPOLATION, EntityDataSerializers.INT, entity.startInterpolation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_INTERPOLATION_DURATION, EntityDataSerializers.INT, entity.interpolationDuration
        ))

        // Decompose and send the full transformation matrix
        encodeTransformation(entity, entries)

        entries.add(SynchedEntityData.DataValue(
            ID_BILLBOARD, EntityDataSerializers.BYTE, encodeBillboard(entity.billboard)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_BRIGHTNESS, EntityDataSerializers.INT, encodeBrightness(entity.brightness)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_VIEW_RANGE, EntityDataSerializers.FLOAT, entity.viewRange
        ))

        if (entity.glowColorOverride != null) {
            entries.add(SynchedEntityData.DataValue(
                ID_GLOW_COLOR_OVERRIDE, EntityDataSerializers.INT, entity.glowColorOverride!!.toARGB()
            ))
        }

        val mcText = FabricPlayerRef.toMinecraftText(entity.text)
        entries.add(SynchedEntityData.DataValue(
            ID_TEXT, EntityDataSerializers.COMPONENT, mcText
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_LINE_WIDTH, EntityDataSerializers.INT, entity.lineWidth
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_BACKGROUND, EntityDataSerializers.INT, entity.backgroundColor.toARGB()
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_TEXT_OPACITY, EntityDataSerializers.BYTE, entity.textOpacity
        ))

        var flags: Byte = 0
        if (entity.hasShadow) flags = (flags.toInt() or 0x01).toByte()
        if (entity.isSeeThrough) flags = (flags.toInt() or 0x02).toByte()
        when (entity.textAlignment) {
            TextAlignment.LEFT -> flags = (flags.toInt() or 0x08).toByte()
            TextAlignment.RIGHT -> flags = (flags.toInt() or 0x10).toByte()
            TextAlignment.CENTER -> {}
        }
        entries.add(SynchedEntityData.DataValue(
            ID_TEXT_DISPLAY_FLAGS, EntityDataSerializers.BYTE, flags
        ))

        return entries
    }

    fun encodeItemDisplay(entity: VirtualItemDisplay): List<SynchedEntityData.DataValue<*>> {
        val entries = mutableListOf<SynchedEntityData.DataValue<*>>()

        entries.add(SynchedEntityData.DataValue(
            ID_START_INTERPOLATION, EntityDataSerializers.INT, entity.startInterpolation
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_INTERPOLATION_DURATION, EntityDataSerializers.INT, entity.interpolationDuration
        ))

        // Decompose and send the full transformation matrix
        encodeTransformation(entity, entries)

        entries.add(SynchedEntityData.DataValue(
            ID_BILLBOARD, EntityDataSerializers.BYTE, encodeBillboard(entity.billboard)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_BRIGHTNESS, EntityDataSerializers.INT, encodeBrightness(entity.brightness)
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_VIEW_RANGE, EntityDataSerializers.FLOAT, entity.viewRange
        ))

        if (entity.glowColorOverride != null) {
            entries.add(SynchedEntityData.DataValue(
                ID_GLOW_COLOR_OVERRIDE, EntityDataSerializers.INT, entity.glowColorOverride!!.toARGB()
            ))
        }

        // Item stack with CustomModelData
        val itemStack = createItemStack(entity)
        entries.add(SynchedEntityData.DataValue(
            ID_ITEM, EntityDataSerializers.ITEM_STACK, itemStack
        ))
        entries.add(SynchedEntityData.DataValue(
            ID_ITEM_DISPLAY_TRANSFORM, EntityDataSerializers.BYTE, encodeItemTransform(entity.itemDisplayTransform)
        ))

        return entries
    }

    private fun createItemStack(entity: VirtualItemDisplay): ItemStack {
        val location = Identifier.tryParse(entity.itemId)
            ?: Identifier.fromNamespaceAndPath("minecraft", "leather_horse_armor")
        val item = BuiltInRegistries.ITEM.getValue(location)
        val stack = ItemStack(item)

        // Set CustomModelData if specified (MC 1.21+ uses list-based CustomModelData)
        if (entity.customModelData != 0) {
            // CustomModelData in 1.21+ takes lists of floats, flags, strings, colors
            // For now, we encode the CMD as a single float in the first list
            val floatList = listOf(entity.customModelData.toFloat())
            stack.set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(floatList, emptyList(), emptyList(), emptyList()))
        }

        // Set color if specified (for leather armor)
        if (entity.itemColor != null) {
            val color = entity.itemColor!!
            val rgbColor = (color.red shl 16) or (color.green shl 8) or color.blue
            stack.set(DataComponents.DYED_COLOR, DyedItemColor(rgbColor))
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
}
