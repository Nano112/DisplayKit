package io.schemat.displaykit.velocity.packet

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3f
import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextAlignment
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualItemDisplay
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.velocity.TestPacketEvents
import io.schemat.displaykit.velocity.state.VelocityBlockStateResolver
import java.util.logging.Logger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests for the metadata layout. The indices, types and flag bytes
 * are the wire contract with the client; a wrong one renders as anything
 * from a missing background to a kicked player, so every one is pinned.
 */
class DisplayMetadataEncoderTest {

    private lateinit var encoder: DisplayMetadataEncoder

    @BeforeTest
    fun boot() {
        TestPacketEvents.boot()
        encoder = DisplayMetadataEncoder(VelocityBlockStateResolver(Logger.getLogger("test")))
    }

    private fun List<EntityData<*>>.at(index: Int): EntityData<*>? = firstOrNull { it.index == index }

    @Test
    fun `text display encodes the full 1_21 layout`() {
        val entity = VirtualTextDisplay()
        entity.text = TextComponent.of("Bonjour")
        entity.backgroundColor = DkColor(0xCC, 0x20, 0x20, 0x30)
        entity.lineWidth = 240
        entity.textOpacity = -1
        entity.hasShadow = true
        entity.isSeeThrough = false
        entity.textAlignment = TextAlignment.CENTER
        entity.viewRange = 1.5f

        val data = encoder.encodeTextDisplay(entity)

        assertEquals(EntityDataTypes.INT, data.at(8)!!.type)
        assertEquals(0, data.at(8)!!.value)
        assertEquals(EntityDataTypes.INT, data.at(9)!!.type)
        assertNull(data.at(10), "teleport duration must be omitted when zero")
        assertEquals(EntityDataTypes.VECTOR3F, data.at(11)!!.type)
        assertEquals(EntityDataTypes.QUATERNION, data.at(13)!!.type)
        assertEquals(EntityDataTypes.VECTOR3F, data.at(12)!!.type)
        assertEquals(EntityDataTypes.QUATERNION, data.at(14)!!.type)
        assertEquals(0.toByte(), data.at(15)!!.value, "FIXED billboard is byte 0")
        assertEquals(-1, data.at(16)!!.value, "null brightness encodes as -1")
        assertEquals(1.5f, data.at(17)!!.value)
        assertNull(data.at(0), "no glowing flag when not glowing")
        assertNull(data.at(22), "no glow color without an override")

        assertEquals(EntityDataTypes.ADV_COMPONENT, data.at(23)!!.type)
        assertEquals(240, data.at(24)!!.value)
        assertEquals(entity.backgroundColor.toARGB(), data.at(25)!!.value)
        assertEquals((-1).toByte(), data.at(26)!!.value)
        assertEquals(0x01.toByte(), data.at(27)!!.value, "shadow only: flags byte 0x01")
    }

    @Test
    fun `text flags combine shadow seethrough and alignment`() {
        val entity = VirtualTextDisplay()
        entity.hasShadow = true
        entity.isSeeThrough = true
        entity.textAlignment = TextAlignment.LEFT

        val data = encoder.encodeTextDisplay(entity)
        assertEquals((0x01 or 0x02 or 0x08).toByte(), data.at(27)!!.value)

        entity.textAlignment = TextAlignment.RIGHT
        entity.hasShadow = false
        assertEquals((0x02 or 0x10).toByte(), encoder.encodeTextDisplay(entity).at(27)!!.value)
    }

    @Test
    fun `glowing emits the shared flag and the override color`() {
        val entity = VirtualTextDisplay()
        entity.glowing = true
        entity.glowColorOverride = DkColor(0xFF, 0xC2, 0x4F, 0x4F)

        val data = encoder.encodeTextDisplay(entity)
        assertEquals(0x40.toByte(), data.at(0)!!.value)
        assertEquals(entity.glowColorOverride!!.toARGB(), data.at(22)!!.value)
    }

    @Test
    fun `brightness packs block and sky nibbles`() {
        val entity = VirtualTextDisplay()
        entity.brightness = Brightness(15, 7)

        val data = encoder.encodeTextDisplay(entity)
        assertEquals((15 shl 4) or 7, data.at(16)!!.value)
    }

    @Test
    fun `teleport duration is emitted only when positive`() {
        val entity = VirtualTextDisplay()
        entity.teleportDuration = 3

        assertEquals(3, encoder.encodeTextDisplay(entity).at(10)!!.value)
    }

    @Test
    fun `block display resolves the state to a global palette id`() {
        val entity = VirtualBlockDisplay()

        val data = encoder.encodeBlockDisplay(entity)
        val state = data.at(23)!!
        assertEquals(EntityDataTypes.BLOCK_STATE, state.type)
        assertTrue((state.value as Int) > 0, "stone must resolve to a real palette id")
    }

    @Test
    fun `item display writes custom model data into floats and strings`() {
        val entity = VirtualItemDisplay()
        entity.itemId = "minecraft:diamond_sword"
        entity.customModelData = 500123

        val data = encoder.encodeItemDisplay(entity)
        val stack = data.at(23)!!.value as ItemStack
        assertEquals("minecraft:diamond_sword", stack.type.name.toString())

        assertEquals(8.toByte(), encoder.encodeItemDisplay(entity).at(24)!!.value, "FIXED transform is byte 8")
    }

    @Test
    fun `transform only encoding stays lean`() {
        val entity = VirtualTextDisplay()
        entity.transformation = Mat4f.identity().translate(1f, 2f, 3f).scale(2f)

        val data = encoder.encodeTransformOnly(entity)
        assertEquals(6, data.size, "start, duration, translation, both rotations, scale")

        val translation = data.at(11)!!.value as Vector3f
        assertEquals(1f, translation.x)
        assertEquals(2f, translation.y)
        assertEquals(3f, translation.z)
        val scale = data.at(12)!!.value as Vector3f
        assertEquals(2f, scale.x)
        val left = data.at(13)!!.value as Quaternion4f
        assertEquals(1f, left.w, "no rotation composed, identity quaternion expected")
        assertFalse(data.any { it.index == 15 }, "billboard is not a transform field")
    }
}
