package io.schemat.displaykit.render

import java.util.concurrent.atomic.AtomicInteger

object EntityIdAllocator {
    private val counter = AtomicInteger(-1_000_000)

    fun next(): Int = counter.decrementAndGet()
}
