package io.schemat.displaykit.platform

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextComponent
import java.util.UUID

interface PlayerRef {
    val uuid: UUID
    val name: String
    fun eyePosition(): Vec3d
    fun lookDirection(): Vec3d
    fun isOnline(): Boolean
    fun sendMessage(message: TextComponent)
}
