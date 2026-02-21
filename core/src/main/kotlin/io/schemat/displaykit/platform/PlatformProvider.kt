package io.schemat.displaykit.platform

import java.util.UUID
import java.util.logging.Logger

interface PlatformProvider {
    val logger: Logger
    val scheduler: Scheduler
    val packetSender: PacketSender
    val textInput: TextInput
    fun getPlayer(uuid: UUID): PlayerRef?
    fun getOnlinePlayers(): Collection<PlayerRef>
}
