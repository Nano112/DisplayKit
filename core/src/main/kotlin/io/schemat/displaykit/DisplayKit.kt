package io.schemat.displaykit

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.InteractionRouter

object DisplayKit {
    private var _platform: PlatformProvider? = null

    val platform: PlatformProvider
        get() = _platform ?: throw IllegalStateException("DisplayKit not initialized. Call DisplayKit.init() first.")

    fun init(platform: PlatformProvider) {
        _platform = platform
        platform.logger.info("[DisplayKit] Initialized on ${platform::class.simpleName}")
    }

    fun createUI(
        owner: PlayerRef,
        center: Vec3d,
        facing: Vec3d = owner.lookDirection(),
        debugMode: Boolean = false
    ): FloatingUI {
        return FloatingUI.create(platform, owner, center, facing, debugMode)
    }

    fun createEntityUI(
        owner: PlayerRef,
        positionProvider: () -> Vec3d,
        facing: Vec3d = owner.lookDirection(),
        maxDistance: Double = 30.0,
        timeoutTicks: Int = 24000,
        debugMode: Boolean = false,
        billboard: Boolean = true
    ): FloatingUI {
        val initial = positionProvider()
        val ui = FloatingUI(
            platform = platform, owner = owner, center = initial, facing = facing,
            maxDistance = maxDistance, timeoutTicks = timeoutTicks,
            debugMode = debugMode, positionProvider = positionProvider,
            billboard = billboard
        )
        InteractionRouter.registerUI(owner.uuid, ui)
        return ui
    }

    fun shutdown() {
        io.schemat.displaykit.ui.InteractionRouter.closeAll()
        _platform = null
    }
}
