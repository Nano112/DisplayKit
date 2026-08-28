package io.schemat.displaykit.velocity

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.NettyManager
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl

/**
 * The registries behind EntityDataTypes, EntityTypes and ItemTypes load
 * lazily and consult the installed API for its settings and server version.
 * Tests install this minimal stand-in once, which is enough for every
 * registry the encoder touches; anything a real proxy would provide throws.
 */
object TestPacketEvents {

    @Synchronized
    fun boot() {
        if (PacketEvents.getAPI() != null) return
        PacketEvents.setAPI(FakeApi)
        FakeApi.load()
    }

    private object FakeApi : PacketEventsAPI<Any>() {
        private val nettyManager: NettyManager = NettyManagerImpl()

        private val serverManager = object : ServerManager {
            override fun getVersion(): ServerVersion = ServerVersion.getLatest()
        }

        override fun load() {}
        override fun isLoaded(): Boolean = true
        override fun init() {}
        override fun isInitialized(): Boolean = true
        override fun terminate() {}
        override fun isTerminated(): Boolean = false
        override fun getPlugin(): Any = this
        override fun getServerManager(): ServerManager = serverManager
        override fun getProtocolManager(): ProtocolManager = throw UnsupportedOperationException("test stub")
        override fun getPlayerManager(): PlayerManager = throw UnsupportedOperationException("test stub")
        override fun getNettyManager(): NettyManager = nettyManager
        override fun getInjector(): ChannelInjector = throw UnsupportedOperationException("test stub")
    }
}
