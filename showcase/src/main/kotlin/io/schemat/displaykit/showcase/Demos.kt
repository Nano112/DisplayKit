package io.schemat.displaykit.showcase

/**
 * Registration point for the individual demos.
 *
 * Each sprite primitive lands together with the demo that visually proves it,
 * so this grows one function per task.
 */
object Demos {
    fun registerAll() {
        WorldQuadDemos.register()
        TintDemo.register()
        CanvasDemos.register()
    }
}
