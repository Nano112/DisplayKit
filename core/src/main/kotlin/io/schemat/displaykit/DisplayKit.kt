package io.schemat.displaykit

import io.schemat.displaykit.platform.PlatformProvider

object DisplayKit {
    private var _platform: PlatformProvider? = null

    val platform: PlatformProvider
        get() = _platform ?: throw IllegalStateException("DisplayKit not initialized. Call DisplayKit.init() first.")

    fun init(platform: PlatformProvider) {
        _platform = platform
        platform.logger.info("[DisplayKit] Initialized on ${platform::class.simpleName}")
    }

    fun shutdown() {
        io.schemat.displaykit.ui.InteractionRouter.closeAll()
        _platform = null
    }
}
